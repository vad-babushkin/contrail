/*
 * Licensed under the Apache License, Version 2.0 (the "License");
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
// Author: Jeremy Lewi(jeremy@lewi.us)
package contrail.stages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import contrail.graph.EdgeDirection;
import contrail.graph.EdgeTerminal;
import contrail.graph.GraphNode;
import contrail.graph.GraphNodeData;
import contrail.graph.GraphPath;
import contrail.sequences.DNAStrand;

/**
 * Use threads to resolve multiple paths through a node.
 *
 * The input to each mapper is a list of nodes. The mapper resolves
 * threads for all nodes whose edges are in the set of the nodes
 * within this list.
 *
 * The reducer collects all nodes with edges across the boundary of the
 * subgraph considered by each mapper.
 */
public class ResolveThreads extends MRStage {
  private static final Logger sLogger =
      Logger.getLogger(ResolveThreads.class);

  // The key used to gather nodes in the reducer that could have
  // threads to resolve. Node ids should be base64 and $ is not a base64
  // character so using it in the key should guarantee zero probability
  // of conflict with a node id.
  private static final String GATHER_KEY = "$GATHER_KEY$";
  /**
   * A comparator for sorting the graph paths based on the ids of the nodes.
   */
  protected static class GraphPathComparator implements Comparator<GraphPath> {
    @Override
    public int compare(GraphPath o1, GraphPath o2) {
      int minLength = Math.min(o1.numTerminals(), o2.numTerminals());
      for (int i = 0; i < minLength; ++i) {
        int value = o1.getTerminal(i).nodeId.compareTo(o2.getTerminal(i).nodeId);
        if (value != 0) {
          return value;
        }
      }
      if (o1.numTerminals() == o2.numTerminals()) {
        return 0;
      } else if (o1.numTerminals() < o2.numTerminals()) {
        return -1;
      } else {
        return 1;
      }
    }
  }
  /**
   * Class is used to return information about the path resolution.
   */
  protected static class ResolveStats {
    public ResolveStats () {
      clones = 0;
      numNodes = 0;
      numIslands = 0;
    }

    // The number of clones for the node.
    public int clones;
    public int numNodes;
    public int numIslands;

    public void add(ResolveStats other) {
      clones += other.clones;
      numNodes += other.numNodes;
      numIslands += other.numIslands;
    }

    public String toString() {
      return String.format(
          "Number of nodes with spanning reads: %d\n" +
          "Number of clones: %d\n" +
          "Number of islands: %d\n" +
          "Final number of nodes: %d\n",
          numNodes, clones, numIslands, numNodes + clones - numIslands);
    }
  }

  /**
   * Structure providing information about the spanning reads.
   */
  public static class SpanningReads {
    public HashMap<String, EdgeTerminal> inReads;
    public HashMap<String, EdgeTerminal> outReads =
        new HashMap<String, EdgeTerminal>();

    /**
     * Ids of reads which span the node.
     */
    Set<String> spanningIds;

    public SpanningReads() {
      // Get a list of reads for incoming edges.
      inReads = new HashMap<String, EdgeTerminal>();
      outReads = new HashMap<String, EdgeTerminal>();
    }
  }

  /**
   * Find the reads that span the node.
   *
   * @param node
   * @return
   */
  public static SpanningReads findSpanningReads(GraphNode node) {
    SpanningReads info = new SpanningReads();
    // Get outgoing edges for the forward strand.
    for (EdgeTerminal terminal : node.getEdgeTerminalsSet(
        DNAStrand.FORWARD, EdgeDirection.OUTGOING)) {
      for (CharSequence read :
           node.getTagsForEdge(DNAStrand.FORWARD, terminal)) {
        info.outReads.put(read.toString(), terminal);
      }
    }

    // To get reads for incoming edges we look at outgoing edges for the
    // reverse strand. getTagsForEdge assumes the edge is an outgoing edge.
    for (EdgeTerminal terminal : node.getEdgeTerminalsSet(
        DNAStrand.REVERSE, EdgeDirection.OUTGOING)) {
      for (CharSequence read :
           node.getTagsForEdge(DNAStrand.REVERSE, terminal)) {
       info.inReads.put(read.toString(), terminal);
      }
    }

    info.spanningIds = info.inReads.keySet();
    info.spanningIds.retainAll(info.outReads.keySet());
    return info;
  }

  /**
   * Find the paths consistent with the reads spanning the node.
   *
   * This function modifies the graph so the graph must be in memory.
   *
   * There are two phases:
   * 1. Copy/Clone
   * 2. Cleanup.
   *
   * During the copy/clone we consider each unique pair of an incoming and outgoing edge (E_i, E_o)
   * We create a copy of the node as well as these edges.
   *
   * If all of the edges to the node are covered by 1 or more reads, then all of the edges are moved to
   * a clone so the original node is a clone and its removed.
   *
   * @return: null if no paths resolved otherwise ResolveStats
   */
  public static ResolveStats resolveSpanningReadPaths(
      HashMap<String, GraphNode> graph, String nodeId) {
    GraphNode node = graph.get(nodeId);
    SpanningReads spanningReads = findSpanningReads(node);

    if (spanningReads.spanningIds.size() == 0) {
      return null;
    }

    ResolveStats stats = new ResolveStats();
    stats.numNodes = 1;

    // Multiple reads could correspond to the same set of terminals
    // so we need to find a unique list of terminal pairs.
    HashMap<GraphPath, ArrayList<String>> pairs =
        new HashMap<GraphPath, ArrayList<String>> ();

    for (String read : spanningReads.spanningIds) {
      EdgeTerminal inTerminal = spanningReads.inReads.get(read).flip();
      EdgeTerminal outTerminal = spanningReads.outReads.get(read);

      GraphPath path = new GraphPath();

      path.add(inTerminal, graph.get(inTerminal.nodeId));
      path.add(outTerminal, graph.get(outTerminal.nodeId));
      if (!pairs.containsKey(path)) {
        pairs.put(path, new ArrayList<String>());
      }
      pairs.get(path).add(read);
    }

    // Sort the paths. This is a convenience so that
    // which cloned node corresponds to which path is stable
    // across invocations.
    ArrayList<GraphPath> paths = new ArrayList<GraphPath>();
    paths.addAll(pairs.keySet());
    Collections.sort(paths, new GraphPathComparator());
    int cloneNum = -1;
    for (GraphPath path : paths) {
      ++cloneNum;
      // We need to flip the terminal because when we got the tags we only
      // considered outgoing edges.
      EdgeTerminal inTerminal = path.first();
      EdgeTerminal outTerminal = path.last();

      ArrayList<String> tags = pairs.get(path);

      // Create a copy of the node.
      GraphNode newNode = new GraphNode();
      newNode.setNodeId(String.format("%s.%02d", node.getNodeId(), cloneNum));
      newNode.setCoverage(tags.size());
      newNode.setSequence(node.getSequence());
      graph.put(newNode.getNodeId(), newNode);

      newNode.addIncomingEdgeWithTags(
          DNAStrand.FORWARD, inTerminal, tags, tags.size() + 1);
      newNode.addOutgoingEdgeWithTags(
          DNAStrand.FORWARD, outTerminal, tags, tags.size() + 1);

      // Add edges to the incoming node
      GraphNode inNode = graph.get(inTerminal.nodeId);
      EdgeTerminal nodeTerminal = new EdgeTerminal(
          newNode.getNodeId(), DNAStrand.FORWARD);
      // We only include the tags which span the node for this edge.
      inNode.addOutgoingEdgeWithTags(
          inTerminal.strand, nodeTerminal, tags, tags.size() + 1);

      GraphNode outNode = graph.get(outTerminal.nodeId);

      outNode.addIncomingEdgeWithTags(
          outTerminal.strand,
          new EdgeTerminal(newNode.getNodeId(), DNAStrand.FORWARD),
          tags, tags.size() + 1);
    }

    // Cleanup.
    // Remove any edges which have been moved to a clone.
    for (GraphPath path : pairs.keySet()) {
      graph.get(path.first().nodeId).removeNeighbor(node.getNodeId());
      graph.get(path.last().nodeId).removeNeighbor(node.getNodeId());
      node.removeNeighbor(path.first().nodeId);
      node.removeNeighbor(path.last().nodeId);
    }

    // If the node is now an island remove it.
    if (node.getNeighborIds().size() == 0) {
      graph.remove(node.getNodeId());
    }

    stats.clones = cloneNum + 1;

    // If all the paths of the node have been resolved then drop the node.
    if (node.getNeighborIds().size() == 0) {
      graph.remove(nodeId);
    }

    return stats;
  }

  /**
   * Get the parameters used by this stage.
   */
  protected Map<String, ParameterDefinition> createParameterDefinitions() {
      HashMap<String, ParameterDefinition> defs =
        new HashMap<String, ParameterDefinition>();

    defs.putAll(super.createParameterDefinitions());

    for (ParameterDefinition def:
      ContrailParameters.getInputOutputPathOptions()) {
      defs.put(def.getName(), def);
    }
    return Collections.unmodifiableMap(defs);
  }


  /**
   * The mapper identifies potential bubbles.
   */
  public static class Mapper extends
      AvroMapper<List<GraphNodeData>, Pair<CharSequence,GraphNodeData>> {
    HashMap<String, GraphNode> nodes;

    private Pair<CharSequence, GraphNodeData> outPair;

    public void configure(JobConf job)    {
      nodes = new HashMap<String, GraphNode>();
      outPair = new Pair<CharSequence, GraphNodeData>("", new GraphNodeData());
    }

    /**
     * Return true if the node has edges spanning the border.
     * @param node
     * @return
     */
    private boolean onBorder(HashMap<String, GraphNode> nodes, GraphNode node) {
      for (String neighborId : node.getNeighborIds()) {
        if (!nodes.containsKey(neighborId)) {
          return true;
        }
      }
      return false;
    }

    public void map(List<GraphNodeData> nodesData,
        AvroCollector<Pair<CharSequence, GraphNodeData>> output,
        Reporter reporter) throws IOException   {
      nodes.clear();

      for (GraphNodeData data: nodesData) {
        nodes.put(data.getNodeId().toString(), new GraphNode(data));
      }

      // Find nodes contained entirely in the graph and those on the edge.
      HashSet<String> borderIds = new HashSet<String>();
      ArrayList<String> innerIds = new ArrayList<String>();

      for (String nodeId : nodes.keySet()) {
        GraphNode node = nodes.get(nodeId);
        if (onBorder(nodes, node)) {
          borderIds.add(nodeId);
        } else {
          innerIds.add(nodeId);
        }
      }

      ResolveStats totalStats = new ResolveStats();

      // Process all the interior nodes.
      for (String nodeId : innerIds) {
        GraphNode node = nodes.get(nodeId);
        // If the indegree and outdegree are both <=1 then there are no paths
        // that can be resolved for this node.
        if (node.degree(DNAStrand.FORWARD, EdgeDirection.INCOMING) <= 1 &&
            node.degree(DNAStrand.FORWARD, EdgeDirection.OUTGOING) <= 1) {
          continue;
        }
        ResolveStats stats = resolveSpanningReadPaths(nodes, nodeId);

        if (stats != null) {
          totalStats.add(stats);
        }
      }

      // Output any nodes which are on the border. If the border node
      // could have threads to resolve then we need to include all its
      // neighbors.
      HashSet<String> nodesToGather = new HashSet<String>();
      for (String id : borderIds) {
        nodesToGather.add(id);
        GraphNode node = nodes.get(id);
        if (node.degree(DNAStrand.FORWARD, EdgeDirection.INCOMING) >= 1 &&
            node.degree(DNAStrand.FORWARD, EdgeDirection.OUTGOING) >= 1) {
          // Since this node could have threads we need to include all
          // its neighbors.
          nodesToGather.addAll(node.getNeighborIds());
        }
      }
      // Output the data
      for (String id : nodes.keySet()) {
       if (nodesToGather.contains(id)) {
         outPair.key(GATHER_KEY);
       } else {
         outPair.key(id);
       }

        outPair.value(nodes.get(id).getData());
        output.collect(outPair);
      }
      reporter.incrCounter("Contrail", "clones", totalStats.clones);
      reporter.incrCounter("Contrail", "islands", totalStats.numIslands);
      reporter.incrCounter(
          "Contrail", "nodes-with-threads", totalStats.numNodes);
    }
  }

  /**
   * The reducer.
   */
  public static class Reducer
      extends AvroReducer<CharSequence, GraphNodeData, GraphNodeData> {
    private HashMap<String, GraphNode> nodes;
    private GraphNode node;

    public void configure(JobConf job)    {
      nodes = new HashMap<String, GraphNode>();
      node = new GraphNode();
    }

    public void reduce(CharSequence key, Iterable<GraphNodeData> iterable,
        AvroCollector<GraphNodeData> collector, Reporter reporter)
            throws IOException {
      if (!key.toString().equals(GATHER_KEY)) {
        // Just output the data
        for (GraphNodeData data : iterable) {
          collector.collect(data);
        }
        return;
      }
      nodes.clear();

      for (GraphNodeData data : iterable) {
        node.setData(data);
        nodes.put(node.getNodeId(), node.clone());
      }

      ResolveStats totalStats = new ResolveStats();

      // Process all the interior nodes.
      // We need to make a copy of the keys because the hasmap will be changing.
      ArrayList<String> nodeIds = new ArrayList<String>();
      nodeIds.addAll(nodes.keySet());
      for (String nodeId : nodeIds) {
        node = nodes.get(nodeId);
        // If the indegree and outdegree are both <=1 then there are no paths
        // that can be resolved for this node.
        if (node.degree(DNAStrand.FORWARD, EdgeDirection.INCOMING) <= 1 &&
            node.degree(DNAStrand.FORWARD, EdgeDirection.OUTGOING) <= 1) {
          continue;
        }
        ResolveStats stats = resolveSpanningReadPaths(nodes, nodeId);

        if (stats != null) {
          totalStats.add(stats);
        }
      }

      // Output all the data.
      for (GraphNode node : nodes.values()) {
        collector.collect(node.getData());
      }
      reporter.incrCounter("Contrail", "clones", totalStats.clones);
      reporter.incrCounter("Contrail", "islands", totalStats.numIslands);
      reporter.incrCounter(
          "Contrail", "nodes-with-threads", totalStats.numNodes);
    }
  }

  @Override
  protected void setupConfHook() {
    JobConf conf = (JobConf) getConf();
    String inputPath = (String) stage_options.get("inputpath");
    String outputPath = (String) stage_options.get("outputpath");

    FileInputFormat.addInputPath(conf, new Path(inputPath));
    FileOutputFormat.setOutputPath(conf, new Path(outputPath));

    AvroJob.setInputSchema(
        conf, Schema.createArray(new GraphNodeData().getSchema()));
    AvroJob.setMapOutputSchema(
        conf,  Pair.getPairSchema(Schema.create(Schema.Type.STRING),
            (new GraphNodeData()).getSchema()));
    AvroJob.setOutputSchema(conf, new GraphNodeData().getSchema());

    AvroJob.setMapperClass(conf, Mapper.class);
    AvroJob.setReducerClass(conf, Reducer.class);
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new ResolveThreads(), args);
    System.exit(res);
  }
}
