// Author: Michael Schatz, Jeremy Lewi
package contrail.avro;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.avro.mapred.AvroCollector;
import org.apache.avro.mapred.AvroJob;
import org.apache.avro.mapred.AvroMapper;
import org.apache.avro.mapred.AvroReducer;
import org.apache.avro.mapred.Pair;
import org.apache.avro.specific.SpecificData;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import contrail.graph.EdgeDirection;
import contrail.graph.EdgeTerminal;
import contrail.graph.GraphNode;
import contrail.graph.TailData;
import contrail.sequences.DNAStrand;
import contrail.sequences.StrandsForEdge;
import contrail.sequences.StrandsUtil;


/**
 * The first map-reduce stage for a probabilistic algorithm for merging linear
 * chains.
 *
 * Suppose we have a linear chain of nodes A->B->C->D...-E. All of these
 * nodes can be merged into one node through a series of local merges,
 * e.g A + B = AB, C+D= DC, .. AB+DC = ABDC, etc... When performing these
 * merges in parallel we need to make sure that a node doesn't get merged
 * twice. For example, if B is merged into A, we shouldn't simultaneously
 * merge B into C. Another challenge, is that when we merge two nodes we
 * need to send messages to those nodes which have been merged away, letting
 * them know the new strand and node id that corresponds to the node
 * which is been merged away.
 *
 * In this stage, we identify which nodes will be sent to other nodes to be
 * merged. Furthermore, edges are updated so they point to what will be the
 * new merged node. The actual merge, however, doesn't happen until the next
 * stage.
 *
 * Some key aspects for the merge are:
 * 1. Each compressible node is randomly assigned a state of Up or Down.
 * 2. The Up/Down state is determined from a global seed and the nodeid.
 *    Thus any node can compute the state for any other node.
 * 3. Down nodes preserve i) their id and ii) their strand.
 *    Thus, if a down node stores the sequence A and is merged with its
 *    neighbor storing B. The merged node will always store the sequence
 *    AB as the forward strand of the merged node. This means, the sequence
 *    stored in nodes after the merge may NOT BE the Canonical sequence.
 *
 *    This is necessary, to allow edge updates to be properly propogated in all
 *    case.
 *
 * The mapper does the following.
 * 1. Randomly assign up and down states to nodes.
 * 2. Identify special cases, in which a down state may be converted to an up
 *    state to allow some additional merges.
 * 3. Form edge update messages.
 *    Suppose we have  A->B->C
 *    If B is merged into A. Then the mapper constructs a message to inform
 *    C of the new id and strand for node B so that it can move its edges.
 * 4. Output each node, keyed by its id in the graph. If the node is an up
 *    node to be merged, then the mapper identifies it as such and marks
 *    it along with which node it will be merged with.
 *
 * The reducer does the following:
 * 1. Apply the edge updates to the node.
 * 2. Output each node, along with information about which node it will be
 *    merged with if applicable.
 *
 * The conditions for deciding when to merge an up node into a down node are
 * as follows.
 * 1. If a down node is in between two down nodes, and the node has the
 *    smallest id of its neighbors convert the node to an up node and merge it.
 * 2. If a down node is compressible along a single strand, and its compressible
 *    neighbor is a down node, and the node has the smaller node id. Convert
 *    it to an up node.
 * 3. If node is an up node merge it with one of its neighboring down nodes.
 */
public class PairMarkAvro extends Stage {
  private static final Logger sLogger = Logger.getLogger(PairMarkAvro.class);

  /**
   * A wrapper class for the CompressibleNodeData schema.
   */
  private static class CompressibleNode {
    private CompressibleNodeData data;
    private GraphNode node;

    public CompressibleNode() {
      // data gets set with setData.
      data = null;
      node = new GraphNode();
    }
    public void setData(CompressibleNodeData new_data) {
      data = new_data;
      node.setData(new_data.getNode());
    }

    public GraphNode getNode() {
      return node;
    }

    /**
     * Whether the given strand of the node can be compressed.
     * @param strand
     * @return
     */
    public boolean canCompress(DNAStrand strand){
      if (data.getCompressibleStrands() == CompressibleStrands.BOTH) {
        return true;
      }
      if (strand == DNAStrand.FORWARD &&
          data.getCompressibleStrands() == CompressibleStrands.FORWARD) {
        return true;
      }
      if (strand == DNAStrand.REVERSE &&
          data.getCompressibleStrands() == CompressibleStrands.REVERSE) {
        return true;
      }
      return false;
    }
  }

  /**
   * Convert the enumeration CompressibleStrands to the equivalent DNAStrand
   * enumeration if possible.
   * @param strands
   * @return
   */
  protected static DNAStrand compressibleStrandsToDNAStrand(
      CompressibleStrands strands) {
    switch (strands) {
      case BOTH:
        return null;
      case NONE:
        return null;
      case FORWARD:
        return DNAStrand.FORWARD;
      case REVERSE:
        return DNAStrand.REVERSE;
      default:
        return null;
    }
  }

  /**
   * Convert DNAStrand to an instance of CompressibleStrands.
   */
  protected static CompressibleStrands dnaStrandToCompressibleStrands(
      DNAStrand strand) {
    switch (strand) {
      case FORWARD:
        return CompressibleStrands.FORWARD;
      case REVERSE:
        return CompressibleStrands.REVERSE;
      default:
        return null;
    }
  }

  protected static class PairMarkMapper extends
  AvroMapper<CompressibleNodeData, Pair<CharSequence, PairMarkOutput>> {
    private CompressibleNode compressible_node;
    private CoinFlipper flipper;
    private NodeInfoForMerge node_info_for_merge;
    public void configure(JobConf job) {
      compressible_node = new CompressibleNode();
      flipper = new CoinFlipper(Long.parseLong(job.get("randseed")));
      out_pair = new Pair<CharSequence, PairMarkOutput>(
          "", new PairMarkOutput());
      node_info_for_merge = new NodeInfoForMerge();
    }

    public TailData getBuddy(CompressibleNode node, DNAStrand strand) {
      if (node.canCompress(strand)) {
        return node.getNode().getTail(strand, EdgeDirection.OUTGOING);
      }
      return null;
    }

    // The output for the mapper.
    private Pair<CharSequence, PairMarkOutput> out_pair;

    // Container class for storing information about which edge in this node
    // to compress.
    private static class EdgeToCompress {
      public EdgeToCompress(EdgeTerminal terminal, DNAStrand dna_strand) {
        other_terminal = terminal;
        strand = dna_strand;
      }
      // This is the terminal for the edge we are going to compress.
      public EdgeTerminal other_terminal;

      // This is the strand of this node which is connected to
      // other_terminal and which gets compressed.
      public DNAStrand strand;
    }

    // Handle a node which is a assigned Up by the coin toss.
    // Since the node is assigned Up we can merge it into one of its
    // tails if that tails is assigned Down.
    private EdgeToCompress processUpNode(
        CompressibleNode node, TailData fbuddy, TailData rbuddy) {
      // Prefer Merging forward if we can.
      // We can only merge in a single direction at a time.
      if (fbuddy != null) {
        CoinFlipper.CoinFlip f_flip = flipper.flip(fbuddy.terminal.nodeId);

        if (f_flip == CoinFlipper.CoinFlip.Down) {
          // We can compress the forward strand.
          return new EdgeToCompress(fbuddy.terminal, DNAStrand.FORWARD);
        }
      }

      // If we can't compress the forward strand, see if
      // we can compress the reverse strand.
      if (rbuddy != null) {
        CoinFlipper.CoinFlip r_flip = flipper.flip(rbuddy.terminal.nodeId);

        if (r_flip == CoinFlipper.CoinFlip.Down) {
          return new EdgeToCompress(rbuddy.terminal, DNAStrand.REVERSE);
        }
      }

      // Can't do a merge.
      return null;
    }

    // Send update messages to the neighbors.
    private void updateEdges(
        EdgeToCompress edge_to_compress,
        AvroCollector<Pair<CharSequence, PairMarkOutput>> collector)
            throws IOException {
      EdgeUpdateForMerge edge_update = new EdgeUpdateForMerge();
      edge_update.setOldId(compressible_node.getNode().getNodeId());
      edge_update.setOldStrand(edge_to_compress.strand);
      edge_update.setNewId(edge_to_compress.other_terminal.nodeId);
      edge_update.setNewStrand(edge_to_compress.other_terminal.strand);

      List<EdgeTerminal> incoming_terminals =
          compressible_node.getNode().getEdgeTerminals(
              edge_to_compress.strand, EdgeDirection.INCOMING);

      for (EdgeTerminal terminal: incoming_terminals){
        out_pair.key(terminal.nodeId);
        out_pair.value().setPayload(edge_update);
        collector.collect(out_pair);
      }
    }

    // If this node was randomly assigned a state of Down then we can
    // potentially convert it to Up and merge it with some of its neighbors.
    // If a node is assigned Down normally we won't do anything because
    // if its connected to a Up node the Up Node will be sent by the mapper
    // to a Down node its connected to and the Reducer will merge the two
    // nodes. However, suppose we have a sequence of two or more Down nodes
    // in a row. Then normally none of the nodes would get merged.
    // However, we can potentially convert the down node to an Up Node
    // and do a merge.
    // To ensure two adjacent nodes aren't both forced to up, we only convert
    // the node if it has the smallest node id among its two neighbors.
    private boolean convertDownToUp(
        GraphNode node, TailData fbuddy, TailData rbuddy) {
      // If a down node is between two down nodes and it has the smallest
      // id then we can convert it to up.
      if ((rbuddy != null) && (fbuddy != null)) {
        // We have tails for both strands of this node.
        CoinFlipper.CoinFlip f_flip = flipper.flip(fbuddy.terminal.nodeId);
        CoinFlipper.CoinFlip r_flip = flipper.flip(rbuddy.terminal.nodeId);

        if (f_flip == CoinFlipper.CoinFlip.Down &&
            r_flip == CoinFlipper.CoinFlip.Down &&
            (node.getNodeId().compareTo(
                fbuddy.terminal.nodeId) < 0) &&
                (node.getNodeId().compareTo(
                    rbuddy.terminal.nodeId) < 0)) {
          // Both neighbors are down nodes and this node has the smallest
          // id among the trio, therefore we force this node to be up.
          return true;
        }
        return false;
      }

      // If the node is compressible along a single strand and its neighbors
      // are down nodes, and the node has the smallest id then we can
      // convert it to up.
      String neighbor = null;

      if (fbuddy != null) {
        neighbor = fbuddy.terminal.nodeId;
      } else if (rbuddy != null) {
        neighbor = rbuddy.terminal.nodeId;
      } else {
        // Its not compressible. This code should never be reached
        // because convertDownToUp should only be invoked if its compressible
        // along at least one strand.
        return false;
      }

      CoinFlipper.CoinFlip flip = flipper.flip(neighbor);
      if (flip == CoinFlipper.CoinFlip.Down && (
          node.getNodeId().compareTo(neighbor) < 0)) {
        return true;
      }
      return false;
    }

    /**
     * Compute the state for this node.
     * @param fbuddy
     * @param rbuddy
     * @return
     */
    private CoinFlipper.CoinFlip computeState(
        GraphNode node, TailData fbuddy, TailData rbuddy) {
      CoinFlipper.CoinFlip coin = flipper.flip(node.getNodeId());
      // If this node is randomly assigned Down, see if it can be converted
      // to up.
      if (coin == CoinFlipper.CoinFlip.Up) {
        return coin;
      }

      if (convertDownToUp(node, fbuddy, rbuddy)) {
        return CoinFlipper.CoinFlip.Up;
      }
      return coin;
    }

    public void map(CompressibleNodeData node_data,
        AvroCollector<Pair<CharSequence, PairMarkOutput>> collector,
        Reporter reporter) throws IOException {
      compressible_node.setData(node_data);
      // Check if either the forward or reverse strand can be merged.
      TailData fbuddy = getBuddy(compressible_node, DNAStrand.FORWARD);
      TailData rbuddy = getBuddy(compressible_node, DNAStrand.REVERSE);

      if (fbuddy == null && rbuddy == null) {
        // Node can't be compressed so output the node and we are done.
        out_pair.key(node_data.getNode().getNodeId());
        node_info_for_merge.setCompressibleNode(node_data);
        node_info_for_merge.setStrandToMerge(CompressibleStrands.NONE);
        out_pair.value().setPayload(node_info_for_merge);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
        return;
      }
      reporter.incrCounter("Contrail", "compressible", 1);


      CoinFlipper.CoinFlip coin = computeState(
          compressible_node.getNode(), fbuddy, rbuddy);

      if (coin == CoinFlipper.CoinFlip.Down) {
        // Just output this node since this is a Down node
        // any node to be merged with this node will be sent to this node.
        out_pair.key(compressible_node.getNode().getNodeId());
        node_info_for_merge.setCompressibleNode(node_data);
        node_info_for_merge.setStrandToMerge(CompressibleStrands.NONE);
        out_pair.value().setPayload(node_info_for_merge);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
        return;
      }

      // Check if this node can be sent to one of its neighbors to be merged.
      EdgeToCompress edge_to_compress =
          processUpNode(compressible_node, fbuddy, rbuddy);

      if (edge_to_compress == null) {
        // This node doesn't get sent to another node be merged.
        out_pair.key(node_data.getNode().getNodeId());
        node_info_for_merge.setCompressibleNode(node_data);
        node_info_for_merge.setStrandToMerge(CompressibleStrands.NONE);
        out_pair.value().setPayload(node_info_for_merge);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
        return;
      }

      updateEdges(edge_to_compress, collector);
      out_pair.key(node_data.getNode().getNodeId());
      node_info_for_merge.setCompressibleNode(node_data);
      node_info_for_merge.setStrandToMerge(
          dnaStrandToCompressibleStrands(edge_to_compress.strand));
      out_pair.value().setPayload(node_info_for_merge);
      collector.collect(out_pair);
      reporter.incrCounter("Contrail", "nodes_to_merge", 1);
    }

    /**
     * Sets the coin flipper. This is primarily intended for use by the
     * unittest.
     */
    public void setFlipper(CoinFlipper flipper) {
      this.flipper = flipper;
    }
  }

  protected static class PairMarkReducer extends
    AvroReducer <CharSequence, CompressibleNodeData, NodeInfoForMerge> {

    // The output for the reducer.
    private NodeInfoForMerge output_node;

    // The length of the KMers.
    //private int K;

    public void configure(JobConf job) {
      //K = Integer.parseInt(job.get("K"));
      output_node = new NodeInfoForMerge();
    }

    /**
     * This function returns a list of the messages to update edges for the
     * nodes which have been merged.
     * @param node: The node that has been merged. This is the node
     *   we get a list of edges that need to be updated.
     * @param strand: The strand of node that has been merged.
     * @param new_nodeid: The id for the new node that represents node.
     * @param new_strand: The strand of the merged node corresponding to
     *   strand of node.
     * @return: A list of the update messages.
     */
//    protected List<EdgeUpdateAfterMerge> updateMessagesForEdge(
//        GraphNode node, DNAStrand strand, String new_nodeid,
//        DNAStrand new_strand) {
//      List<EdgeUpdateAfterMerge> edge_updates =
//          new ArrayList<EdgeUpdateAfterMerge> ();
//      // For the source node, we need to update the incoming edges
//      // to the strand that was merged.
//      List<EdgeTerminal> incoming_terminals =
//          node.getEdgeTerminals(strand, EdgeDirection.INCOMING);
//
//      for (EdgeTerminal terminal: incoming_terminals) {
//        EdgeUpdateAfterMerge update = new EdgeUpdateAfterMerge();
//        update.setOldStrands(StrandsUtil.form(terminal.strand, strand));
//        update.setNewStrands(StrandsUtil.form(terminal.strand, new_strand));
//
//        update.setOldTerminalId(node.getNodeId());
//        update.setNewTerminalId(new_nodeid);
//
//        update.setNodeToUpdate(terminal.nodeId);
//
//        edge_updates.add(update);
//      }
//      return edge_updates;
//    }

    /**
     * Determines whether the merged node resulting from chain is further
     * compressible.
     * @param chain: The chain of nodes which are merged together.
     * @param merged_strand: Which strand corresponds to merging chain
     *   together.
     * @return: Which strands if any of the merged node are compressible.
     */
//    protected CompressibleStrands isCompressible(
//        ArrayList<ChainLink> chain, DNAStrand merged_strand) {
//      // Now we need to determine whether the merged node is compressible.
//      // The merged node is compressible if the ends of the chain are
//      // compressible in both directions.
//      ArrayList<DNAStrand> compressible_strands = new ArrayList<DNAStrand>();
//
//      if (chain.get(0).node.getCompressibleStrands() ==
//          CompressibleStrands.BOTH) {
//        // Get the strand of node 0 that wasn't compressed.
//        DNAStrand strand =
//            DNAStrandUtil.flip(chain.get(0).compressible_strand);
//        // We need to flip the strand if merged_strand is different
//        // from the strand for node 0.
//        if (chain.get(0).compressible_strand != merged_strand) {
//          strand = DNAStrandUtil.flip(strand);
//        }
//        compressible_strands.add(strand);
//      }
//
//      int tail = chain.size() - 1;
//      if (chain.get(tail).node.getCompressibleStrands() ==
//          CompressibleStrands.BOTH) {
//        // Get the strand of the last node that wasn't compressed.
//        // The last node would have been compressed along the incoming
//        // edge, so we can still compress it along the outgoing
//        // edge.
//        DNAStrand strand = chain.get(tail).compressible_strand;
//        // We need to flip the strand if merged_strand is different
//        // from the strand for the last node.
//        if (chain.get(tail).compressible_strand != merged_strand) {
//          strand = DNAStrandUtil.flip(strand);
//        }
//        compressible_strands.add(strand);
//      }
//
//      switch (compressible_strands.size()) {
//        case 0:
//          return CompressibleStrands.NONE;
//        case 1:
//          if (compressible_strands.get(0) == DNAStrand.FORWARD) {
//            return CompressibleStrands.FORWARD;
//          } else {
//            return CompressibleStrands.REVERSE;
//          }
//        case 2:
//          // Sanity check. The two strands should not be equal.
//          if (compressible_strands.get(0) == compressible_strands.get(1)) {
//            throw new RuntimeException(
//                "There is a bug in the code. The two strands should not be " +
//                "the same.");
//          }
//          return CompressibleStrands.BOTH;
//        default:
//          throw new RuntimeException("This code should not be reached.");
//      }
//    }

    public void reduce(
        CharSequence nodeid, Iterable<PairMarkOutput> iterable,
        AvroCollector<NodeInfoForMerge> collector, Reporter reporter)
            throws IOException {
      Iterator<PairMarkOutput> iter = iterable.iterator();

      boolean seen_node = false;
      GraphNode graph_node = null;
      // The nodes to merge.
      ArrayList<EdgeUpdateForMerge> edge_updates =
          new ArrayList<EdgeUpdateForMerge>();
      while(iter.hasNext()) {
        PairMarkOutput mark_output = iter.next();
        if (mark_output.getPayload() instanceof NodeInfoForMerge) {
          // Sanity check there should be a single instance of NodeInfoForMerge.
          if (seen_node) {
            throw new RuntimeException(
                "There are two nodes for nodeid: " + nodeid);
          }
          // Make a copy of the payload. We can't just use AVRO methods to
          // make the copy because of issues with Avro and copying byte
          // buffers.
          NodeInfoForMerge source =
              (NodeInfoForMerge) mark_output.getPayload();

          // We need to make a copy of the node because iterable
          // will reuse the same instance when next is called.
          // Because of https://issues.apache.org/jira/browse/AVRO-1045 we
          // can't use the Avro methods for copying the data.
          graph_node =
              new GraphNode(source.getCompressibleNode().getNode()).clone();
          source.getCompressibleNode().setNode(null);
          output_node = (NodeInfoForMerge) SpecificData.get().deepCopy(
              source.getSchema(), source);
          output_node.getCompressibleNode().setNode(graph_node.getData());
          seen_node = true;
        } else {
          EdgeUpdateForMerge edge_update =
              (EdgeUpdateForMerge) mark_output.getPayload();
          edge_update =
              (EdgeUpdateForMerge) SpecificData.get().deepCopy(
                  edge_update.getSchema(), edge_update);
          edge_updates.add(edge_update);
        }
      }

      if (!seen_node) {
        throw new RuntimeException(
            "There is no node to output for nodeid: " + nodeid);
      }


      for (EdgeUpdateForMerge edge_update: edge_updates) {
        EdgeTerminal old_terminal = new EdgeTerminal(
            edge_update.getOldId(), edge_update.getOldStrand());

        DNAStrand strand = graph_node.findStrandWithEdgeToTerminal(
            old_terminal, EdgeDirection.OUTGOING);

        if (strand == null) {
          throw new RuntimeException(
              "Node: " + nodeid + " has recieved a message to update edge " +
              "to terminal:" + old_terminal + " but no edge could be found " +
              "that terminal.");
        }

        EdgeTerminal new_terminal = new EdgeTerminal(
            edge_update.getNewId().toString(), edge_update.getNewStrand());

        graph_node.moveOutgoingEdge(strand, old_terminal, new_terminal);
      }

      collector.collect(output_node);
    }
  }

  /**
   * Get the options required by this stage.
   */
  protected List<Option> getCommandLineOptions() {
    List<Option> options = super.getCommandLineOptions();
    options.addAll(ContrailOptions.getInputOutputPathOptions());

    // Add options specific to this stage.
    options.add(OptionBuilder.withArgName("randseed").hasArg().withDescription(
        "seed for the random number generator [required]").create("randseed"));
    return options;
  }

  @Override
  protected void parseCommandLine(CommandLine line) {
    super.parseCommandLine(line);
    if (line.hasOption("inputpath")) {
      stage_options.put("inputpath", line.getOptionValue("inputpath"));
    }
    if (line.hasOption("outputpath")) {
      stage_options.put("outputpath", line.getOptionValue("outputpath"));
    }
    if (line.hasOption("K")) {
      stage_options.put("K", Long.valueOf(line.getOptionValue("K")));
    }
    if (line.hasOption("randseed")) {
      stage_options.put(
          "randseed", Long.valueOf(line.getOptionValue("randseed")));
    }
  }

  public int run(String[] args) throws Exception {
    sLogger.info("Tool name: PairMarkAvro");
    parseCommandLine(args);
    return run();
  }

  @Override
  protected int run() throws Exception {
    String[] required_args = {"inputpath", "outputpath", "K", "randseed"};
    checkHasOptionsOrDie(required_args);

    String inputPath = (String) stage_options.get("inputpath");
    String outputPath = (String) stage_options.get("outputpath");
    long K = (Long)stage_options.get("K");
    long randseed = (Long)stage_options.get("randseed");

    sLogger.info(" - input: "  + inputPath);
    sLogger.info(" - output: " + outputPath);
    sLogger.info(" - K: " + K);
    sLogger.info(" - randseed: " + randseed);
    JobConf conf = new JobConf(PairMarkAvro.class);
    conf.setJobName("PairMarkAvro " + inputPath + " " + K);

    initializeJobConfiguration(conf);

    FileInputFormat.addInputPath(conf, new Path(inputPath));
    FileOutputFormat.setOutputPath(conf, new Path(outputPath));

    CompressibleNodeData compressible_node = new CompressibleNodeData();
    Pair<CharSequence, CompressibleNodeData> map_output =
        new Pair<CharSequence, CompressibleNodeData>
          ("", new CompressibleNodeData());
    PairMarkOutput reducer_output = new PairMarkOutput();
    AvroJob.setInputSchema(conf, compressible_node.getSchema());
    AvroJob.setMapOutputSchema(conf, map_output.getSchema());
    AvroJob.setOutputSchema(conf, reducer_output.getSchema());

    AvroJob.setMapperClass(conf, PairMarkMapper.class);
    AvroJob.setReducerClass(conf, PairMarkReducer.class);

    if (stage_options.containsKey("writeconfig")) {
      writeJobConfig(conf);
    } else {
      // Delete the output directory if it exists already
      Path out_path = new Path(outputPath);
      if (FileSystem.get(conf).exists(out_path)) {
        // TODO(jlewi): We should only delete an existing directory
        // if explicitly told to do so.
        sLogger.info("Deleting output path: " + out_path.toString() + " " +
            "because it already exists.");
        FileSystem.get(conf).delete(out_path, true);
      }

      long starttime = System.currentTimeMillis();
      JobClient.runJob(conf);
      long endtime = System.currentTimeMillis();

      float diff = (float) ((endtime - starttime) / 1000.0);

      System.out.println("Runtime: " + diff + " s");
    }
    return 0;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new PairMarkAvro(), args);
    System.exit(res);
  }
}
