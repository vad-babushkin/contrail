/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package contrail.stages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.mapred.AvroCollector;
import org.apache.avro.mapred.AvroJob;
import org.apache.avro.mapred.AvroMapper;
import org.apache.avro.mapred.AvroReducer;
import org.apache.avro.mapred.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import contrail.graph.EdgeDirection;
import contrail.graph.EdgeTerminal;
import contrail.graph.GraphNode;
import contrail.graph.GraphNodeData;
import contrail.graph.GraphUtil;
import contrail.sequences.DNAStrand;
import contrail.sequences.DNAUtil;
import contrail.sequences.Sequence;
import contrail.stages.GraphCounters.CounterName;

/**
 * This stage finds bubbles created by sequencing errors.
 *
 * Sequencing errors in the middle of reads creates "bubbles". A bubble
 * is the graph X->{A,B}->Y where X->{A,B} mean X has an edge to each node
 * in the set {A,B}. Similarly, {A,B}-> Y means each node in the set {A, B}
 * has an edge to node Y.
 *
 * If the sequences for A and B are sufficiently similar, then we can pop the
 * bubble by only keeping the node with higher coverage.
 *
 * Popping bubbles is done in two stages.
 *  1. FindBubblesAvro finds these potential bubbles.
 *  2. PopBubblesAvro removes edges in the graph to the deleted nodes.
 *
 * FindBubbles looks for the bubbles in the graph; i.e. those nodes
 *  1. that have indegree=1 and outdegree=1,
 *  2. their Sequence length is less than some threshold,
 *  3. the edit distance between the sequences for nodes A and B are less than
 *     some THRESHOLD.
 *
 * The Mapper finds potential bubbles (e.g nodes A & B) by looking for nodes
 * which satisfy criterion #1 & #2 above. These nodes are then shipped to
 * the major neighbor for that node. By convention, for any node we define
 * its major neighbor to be the neighbor with the largest node id
 * (lexicographically).
 *
 * In the Reducer nodes which could be bubbles are grouped with their major
 * neighbor. For example, nodes A & B would be grouped with node Y in the
 * reducer. If nodes A & B can be merged then the node with lower coverage is
 * deleted and the edges of node Y are updated. The reducer then outputs
 * messages that are used in PopBubblesAvro to tell node X to delete its
 * edges to node B which was deleted.
 *
 * Special Cases:
 * 1. Palindromes. A bubble can be created by a palindrome, a sequence
 *    which equals its reverse complement. Palindromes can be created when
 *    two sequences are merged. This can create a bubble
 *    X->{A, R(A)} -> Y  where A=R(A). Unfortunately, the distributed algorithm
 *    for doing pair wise merges means we can't deal with palindromes when
 *    doing the merge.
 *
 *    The reducer identifies bubbles formed by palindromes. If such a bubble
 *    is detected then the bubble is popped by ensuring all edges from
 *    the major neighbor target the forward strand of the node and all edges
 *    from the minor target the reverse strand of the palindrome.
 *
 * 2. Major and minor node are the same. For example suppose we have the
 *    graph  X->{A, B}->R(X). For the most part we can handle this like
 *    any another bubble. Except that we can pop the bubble in the reducer
 *    since we have access to the minor node.
 *
 * Important: The graph must be maximally be compressed otherwise this code
 * won't work.
 */

public class FindBubblesAvro extends Stage   {
  private static final Logger sLogger = Logger.getLogger(FindBubblesAvro.class);

  public static final Schema MAP_OUT_SCHEMA =
      Pair.getPairSchema(Schema.create(Schema.Type.STRING),
          (new GraphNodeData()).getSchema());

  public static final Schema REDUCE_OUT_SCHEMA =
      new FindBubblesOutput().getSchema();

  public final static CounterName NUM_BUBBLES =
      new CounterName("Contrail", "find-bubbles-num-bubbles");

  public final static CounterName NUM_PALINDROMES =
      new CounterName("Contrail", "find-bubbles-num-palindromes");

  protected Map<String, ParameterDefinition> createParameterDefinitions() {
    HashMap<String, ParameterDefinition> defs =
        new HashMap<String, ParameterDefinition>();
    defs.putAll(super.createParameterDefinitions());

    // Add options specific to this stage.
    ParameterDefinition bubble_edit_rate =
        new ParameterDefinition("bubble_edit_rate",
            "We consider two sequences to be the same if their edit distance " +
            "is less then or equal to length * bubble_edit_rate.",
            Float.class, new Float(0));

    ParameterDefinition bubble_length_threshold =
        new ParameterDefinition("bubble_length_threshold",
            "A threshold for sequence lengths. Only sequence's with lengths " +
            "less than this value can be bubbles.",
            Integer.class, new Integer(0));

    for (ParameterDefinition def:
      new ParameterDefinition[] {
        bubble_length_threshold, bubble_edit_rate}) {
      defs.put(def.getName(), def);
    }
    for (ParameterDefinition def:
      ContrailParameters.getInputOutputPathOptions()) {
      defs.put(def.getName(), def);
    }
    return Collections.unmodifiableMap(defs);
  }


  /**
   * The mapper identifies potential bubbles.
   */
  public static class FindBubblesAvroMapper extends
  AvroMapper<GraphNodeData, Pair<CharSequence,GraphNodeData>>    {
    int bubbleLenThresh = 0;
    GraphNode node = null;

    private Pair<CharSequence, GraphNodeData> outPair;

    public void configure(JobConf job)    {
      FindBubblesAvro stage= new FindBubblesAvro();
      Map<String, ParameterDefinition> definitions =
          stage.getParameterDefinitions();
      bubbleLenThresh =
          (Integer) (definitions.get(
              "bubble_length_threshold").parseJobConf(job));

      node = new GraphNode();
      outPair = new Pair<CharSequence, GraphNodeData>("", new GraphNodeData());
    }

    public void map(GraphNodeData graphData,
        AvroCollector<Pair<CharSequence, GraphNodeData>> output,
        Reporter reporter) throws IOException   {
      node.setData(graphData);

      int sequenceLength = graphData.getSequence().getLength();
      int outDegree = node.degree(DNAStrand.FORWARD, EdgeDirection.OUTGOING);
      int inDegree = node.degree(DNAStrand.FORWARD, EdgeDirection.INCOMING);
      // check if node can't be a bubble
      if ((sequenceLength >= bubbleLenThresh) || (outDegree != 1) ||
          (inDegree != 1))  {
        outPair.set(node.getNodeId(), graphData);
        output.collect(outPair);
        return;
      }

      // Identify the major neighbor.
      EdgeTerminal in =
          node.getEdgeTerminals(
              DNAStrand.FORWARD, EdgeDirection.INCOMING).get(0);
      EdgeTerminal out =
          node.getEdgeTerminals(
              DNAStrand.FORWARD, EdgeDirection.OUTGOING).get(0);

      CharSequence majorID = GraphUtil.computeMajorID(in.nodeId, out.nodeId);
      outPair.set(majorID, graphData);
      output.collect(outPair);
    }
  }


  /**
   * The reducer.
   */
  public static class FindBubblesAvroReducer
      extends AvroReducer<CharSequence, GraphNodeData, FindBubblesOutput> {
    private int K;
    public float bubbleEditRate;
    private GraphNode majorNode = null;
    private GraphNode bubbleNode = null;
    private FindBubblesOutput output = null;
    private BubbleUtil bubbleUtil = null;

    public void configure(JobConf job) {
      FindBubblesAvro stage = new FindBubblesAvro();
      Map<String, ParameterDefinition> definitions =
          stage.getParameterDefinitions();

      bubbleEditRate= (Float)(
          definitions.get("bubble_edit_rate").parseJobConf(job));
      K = (Integer)(definitions.get("K").parseJobConf(job));
      majorNode = new GraphNode();
      bubbleNode = new GraphNode();
      output = new FindBubblesOutput();
      output.setDeletedNeighbors(new ArrayList<CharSequence>());
      output.setPalindromeNeighbors(new ArrayList<CharSequence>());
      bubbleUtil = new BubbleUtil();
    }

    /**
     * This class is used for processing nodes which could be bubbles.
     *
     * For any node which might be a bubble, we identify the strand such
     * that there is an outgoing edge from the forward strand of its major
     * neighbor. We use this strand when computing the edit distance.
     */
    private class BubbleMetaData implements Comparable<BubbleMetaData>   {
      GraphNode node;

      // A boolean variable used for marking nodes which will be deleted because
      // they are sufficiently similar to another node.
      boolean popped;

      // The properly aligned sequence which should be used for computing
      // the edit distance.
      Sequence alignedSequence;

      String minorID;

      private Boolean isPalindromeValue;

      // This boolean indicates we have the special case where the
      // two terminals for the bubble are different strands of the major
      // node.
      boolean noMinor;

      BubbleMetaData(GraphNodeData nodeData, String major) {
        node = new GraphNode();
        node.setData(nodeData);
        popped = false;
        noMinor = false;
        Set<String> neighborIds = node.getNeighborIds();

        if (neighborIds.size() > 2) {
          throw new RuntimeException(
              String.format(
                  "Bubble has more than 2 neighbors. This is a bug. Number " +
                      "of neighbors is %d.", neighborIds.size()));
        }

        if (neighborIds.size() == 1) {
          // We have the special case where the two terminals for the
          // bubble are the same node.
          minorID = major;
          noMinor = true;
        } else {
          for (String neighborId : neighborIds) {
            if (!neighborId.equals(major)) {
              minorID = neighborId;
              break;
            }
          }
        }

        // Find the strand of this node which is the terminal for an outgoing
        // edge of the forward strand of the major node.
        DNAStrand strandFromMajor;
        EdgeTerminal terminal =
            node.getEdgeTerminals(
                DNAStrand.FORWARD, EdgeDirection.INCOMING).get(0);
        if (terminal.nodeId.toString().equals(major.toString())) {
          strandFromMajor = DNAStrand.FORWARD;
        } else {
          strandFromMajor = DNAStrand.REVERSE;
        }

        alignedSequence = DNAUtil.sequenceToDir(
            node.getSequence(), strandFromMajor);
      }

      public Boolean isPalindrome() {
        if (isPalindromeValue == null) {
          isPalindromeValue = DNAUtil.isPalindrome(alignedSequence);
        }
        return isPalindromeValue;
      }

      /**
       * A positive result means coverage(this) < coverage(other).
       * Conversely, a negative result means coverage(this) > coverage(other).
       *
       * We define it this way so the nodes will be sorted in decreasing order
       * of coverage.
       */
      public int compareTo(BubbleMetaData o) {
        BubbleMetaData co = o;
        return (int)(co.node.getCoverage() - node.getCoverage());
      }
    }

    /**
     * For every pair of nodes (u, v) we compute the edit distance between the
     * sequences. If the edit distance is less than edit_distance_threshold
     * then the sequences are sufficiently similar and we can mark the node
     * v to be removed where node v is the node with smaller coverage.
     */
    protected void ProcessMinorList(
        List<BubbleMetaData> minor_list, Reporter reporter,
        CharSequence minor)  {
      // Sort potential bubble nodes in order of decreasing coverage.
      Collections.sort(minor_list);

      for (int i = 0; i < minor_list.size(); i++)   {
        BubbleMetaData highCoverageNode = minor_list.get(i);
        if(highCoverageNode.popped)  {
          continue;
        }
        for (int j = i+1; j < minor_list.size(); j++)   {
          BubbleMetaData lowCoverageNode = minor_list.get(j);
          if(lowCoverageNode.popped)  {
            continue;
          }

          int distance = highCoverageNode.alignedSequence.computeEditDistance(
              lowCoverageNode.alignedSequence);

          int threshold = (int) Math.max(
              highCoverageNode.node.getSequence().size(),
              lowCoverageNode.node.getSequence().size() * bubbleEditRate);

          reporter.incrCounter("Contrail", "bubbleschecked", 1);

          if (distance <= threshold)  {
            // Found a bubble!
            reporter.incrCounter(NUM_BUBBLES.group, NUM_BUBBLES.tag, 1);
            int lowLength =
                lowCoverageNode.node.getSequence().size()- K + 1;

            // Since lowCoverageNode is the bubble that will be popped;
            // we need to update coverage of highCoverageNode to reflect the
            // fact that we consider lowCoverageNode to be the same sequence
            // as highCoverageNode except for the read errors.
            float extraCoverage =
                lowCoverageNode.node.getCoverage() * lowLength;
            lowCoverageNode.popped = true;


            int highLength = highCoverageNode.node.getSequence().size()
                             - K + 1;
            float support = highCoverageNode.node.getCoverage() * highLength +
                            extraCoverage;
            highCoverageNode.node.setCoverage(support / highLength);
            majorNode.removeNeighbor(lowCoverageNode.node.getNodeId());
          }
        }
      }
    }

    /**
     * Check every non-popped node to see if its a palindrome. If it is
     * a palindrome then make sure all edges to it are to the forward strand.
     *
     * @param minor_list
     * @param reporter
     */
    void processPalindromes (
        List<BubbleMetaData> minorList, Reporter reporter) {
      for (BubbleMetaData bubbleData: minorList) {
        if (bubbleData.popped) {
          continue;
        }
        if (!bubbleData.isPalindrome()) {
          continue;
        }
        reporter.incrCounter(NUM_PALINDROMES.group, NUM_PALINDROMES.tag, 1);

        bubbleUtil.fixEdgesToPalindrome(
            majorNode, bubbleData.node.getNodeId(), true);
        bubbleUtil.fixEdgesFromPalindrome(bubbleData.node);
      }
    }

    // Output the messages to the minor node.
    void outputMessagesToMinor(
        List<BubbleMetaData> minor_list, CharSequence minor,
        AvroCollector<FindBubblesOutput> collector) throws IOException {
      output.setNode(null);
      output.setMinorNodeId("");
      output.getDeletedNeighbors().clear();
      output.getPalindromeNeighbors().clear();

      ArrayList<CharSequence> deletedNeighbors = new ArrayList<CharSequence>();
      ArrayList<CharSequence> palindromeNeighbors =
          new ArrayList<CharSequence>();


      for(BubbleMetaData bubbleMetaData : minor_list) {
        if(bubbleMetaData.popped) {
          deletedNeighbors.add(bubbleMetaData.node.getNodeId());
        } else {
          if (bubbleMetaData.isPalindrome()) {
            palindromeNeighbors.add(bubbleMetaData.node.getNodeId());
          }
          // This is a non-popped node so output the node.
          output.setNode(bubbleMetaData.node.getData());
          collector.collect(output);
        }
      }

      if (minor_list.get(0).noMinor) {
        // For these bubbles the minor node is the same as the major node
        // so we don't need to send any messages to a minor node.
        return;
      }
      // Output the messages to the minor node.
      output.setNode(null);
      output.setMinorNodeId(minor);
      output.setDeletedNeighbors(deletedNeighbors);
      output.setPalindromeNeighbors(palindromeNeighbors);
      collector.collect(output);
    }

    public void reduce(CharSequence nodeID, Iterable<GraphNodeData> iterable,
        AvroCollector<FindBubblesOutput> collector, Reporter reporter)
            throws IOException {
      // We group BubbleMetaData based on the minor neighbor for the node.
      Map<String, List<BubbleMetaData>> bubblelinks =
          new HashMap<String, List<BubbleMetaData>>();
      int sawNode = 0;
      Iterator<GraphNodeData> iter = iterable.iterator();

      // We want a string version for use with equals.
      String majorID = nodeID.toString();

      while(iter.hasNext())   {
        GraphNodeData nodeData = iter.next();

        if (majorID.equals(nodeData.getNodeId().toString())) {
          // This node is the major node.
          majorNode.setData(nodeData);
          majorNode = majorNode.clone();
          ++sawNode;
        } else {
          bubbleNode.setData(nodeData);
          bubbleNode = bubbleNode.clone();

          BubbleMetaData bubbleData = new BubbleMetaData(
              bubbleNode.getData(), majorID);

          reporter.incrCounter("Contrail", "linkschecked", 1);
          // see if the hashmap has that particular ID of edge terminal;
          // if not then add it
          if (!bubblelinks.containsKey(bubbleData.minorID)) {
            List<BubbleMetaData> list = new ArrayList<BubbleMetaData>();
            bubblelinks.put(bubbleData.minorID, list);
          }
          bubblelinks.get(bubbleData.minorID.toString()).add(bubbleData);
        }
      }

      if (sawNode == 0)    {
        Formatter formatter = new Formatter(new StringBuilder());
        formatter.format(
            "ERROR: No node was provided for nodeId %s. This can happen if " +
            "the graph isn't maximally compressed before calling FindBubbles",
            majorID);
        throw new IOException(formatter.toString());
      }

      if (sawNode > 1) {
        Formatter formatter = new Formatter(new StringBuilder());
        formatter.format("ERROR: nodeId %s, %d nodes were provided",
            majorID, sawNode);
        throw new IOException(formatter.toString());
      }


      for (String minorID : bubblelinks.keySet())   {
        List<BubbleMetaData> minorBubbles = bubblelinks.get(minorID);
        int choices = minorBubbles.size();
        reporter.incrCounter("Contrail", "minorchecked", 1);
        if (choices <= 1) {
          // Check if this bubble is a palindrome.
          if (!DNAUtil.isPalindrome(minorBubbles.get(0).alignedSequence)) {
            // Check if we have a chain. We have a change if the major node
            // has outdegree 1 from the forward
            if (majorNode.degree(
                    DNAStrand.FORWARD, EdgeDirection.OUTGOING) == 1) {
              // We have a chain, i.e A->X->B and not a bubble
              // A->{X,Y,...}->B this shouldn't happen and probably means
              // the graph wasn't maximally compressed.
              throw new RuntimeException(
                  "We found a chain and not a bubble. This probably means " +
                  "the graph wasn't maximally compressed before running " +
                  "FindBubbles.");
            }
          }
        } else {
          // marks nodes to be deleted for a particular list of minorID
          ProcessMinorList(minorBubbles, reporter, minorID);
        }
        // After popping bubbles, we check any nodes which are still alive
        // if they are palindromes.
        processPalindromes(minorBubbles, reporter);
        reporter.incrCounter("Contrail", "edgeschecked", choices);
        outputMessagesToMinor(minorBubbles, minorID, collector);
      }

      // Output the major node.
      output.setNode(majorNode.getData());
      output.setMinorNodeId("");
      output.getDeletedNeighbors().clear();
      output.getPalindromeNeighbors().clear();
      collector.collect(output);
    }
  }

  // Run Tool
  ///////////////////////////////////////////////////////////////////////////

  public RunningJob runJob() throws Exception {
    String[] requiredArgs = {
        "inputpath", "outputpath", "bubble_edit_rate",
        "bubble_length_threshold"};
    checkHasParametersOrDie(requiredArgs);

    String inputPath = (String) stage_options.get("inputpath");
    String outputPath = (String) stage_options.get("outputpath");
    int K = (Integer)stage_options.get("K");

    sLogger.info("Tool name: FindBubbles");
    sLogger.info(" - input: "  + inputPath);
    sLogger.info(" - output: " + outputPath);

    int bubbleLengthThreshold =
        (Integer)stage_options.get("bubble_length_threshold");
    if (bubbleLengthThreshold <= K) {
      sLogger.warn(
          "FindBubbles will not run because bubble_length_threshold<=K so no " +
          "nodes will be considered bubbles.");
      return null;
    }

    Configuration base_conf = getConf();
    JobConf conf = null;
    if (base_conf != null) {
      conf = new JobConf(getConf(), this.getClass());
    }
    else {
      conf = new JobConf(this.getClass());
    }
    conf.setJobName("FindBubbles " + inputPath + " " + K);

    initializeJobConfiguration(conf);

    FileInputFormat.addInputPath(conf, new Path(inputPath));
    FileOutputFormat.setOutputPath(conf, new Path(outputPath));

    GraphNodeData graph_data = new GraphNodeData();
    AvroJob.setInputSchema(conf, graph_data.getSchema());
    AvroJob.setMapOutputSchema(conf, FindBubblesAvro.MAP_OUT_SCHEMA);
    AvroJob.setOutputSchema(conf, FindBubblesAvro.REDUCE_OUT_SCHEMA);

    AvroJob.setMapperClass(conf, FindBubblesAvroMapper.class);
    AvroJob.setReducerClass(conf, FindBubblesAvroReducer.class);

    //delete the output directory if it exists already
    FileSystem.get(conf).delete(new Path(outputPath), true);

    long starttime = System.currentTimeMillis();
    RunningJob result = JobClient.runJob(conf);
    long endtime = System.currentTimeMillis();

    float diff = (float) ((endtime - starttime) / 1000.0);

    long numToPop = result.getCounters().findCounter(
        NUM_BUBBLES.group, NUM_BUBBLES.tag).getValue();

    long numPalindromes = result.getCounters().findCounter(
        NUM_PALINDROMES.group, NUM_PALINDROMES.tag).getValue();

    sLogger.info("Number of nodes to pop:" + numToPop);
    sLogger.info("Number of palindromes:" + numPalindromes);
    sLogger.info("Runtime: " + diff + " s");
    return result;
  }

  // Main
  ///////////////////////////////////////////////////////////////////////////

  public static void main(String[] args) throws Exception   {
    int res = ToolRunner.run(new Configuration(), new FindBubblesAvro(), args);
    System.exit(res);
  }
}
