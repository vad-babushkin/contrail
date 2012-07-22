package contrail.gephi;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import contrail.avro.ContrailParameters;
import contrail.avro.ParameterDefinition;
import contrail.avro.Stage;
import contrail.graph.EdgeDirection;
import contrail.graph.EdgeTerminal;
import contrail.graph.GraphNode;
import contrail.graph.GraphNodeData;
import contrail.sequences.DNAStrand;
import contrail.sequences.StrandsUtil;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Write a gephi XML file to represent a graph.
 *
 * Doc about gexf format:
 * http://gexf.net/1.2draft/gexf-12draft-primer.pdf
 *
 * WARNING: Gephi appears to have problems reading files in "/tmp" so
 * write the file somewhere else.
 *
 * TODO(jlewi): It would be good if we could label each node so that
 * we could see
 */
public class WriteGephiFile extends Stage {
  private static final Logger sLogger =
      Logger.getLogger(WriteGephiFile.class);

  protected Map<String, ParameterDefinition>
  createParameterDefinitions() {
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
   * Create an XML element to represent the edge.
   * @param edge_id
   * @param node_id_map
   * @param node
   * @param terminal
   * @return
   */
  private static Element createElementForEdge(
      Document doc, int edge_id, HashMap<String, Integer> node_id_map,
      GraphNode node, DNAStrand strand, EdgeTerminal terminal) {
    Element xml_edge = doc.createElement("edge");
    Integer this_node_id = node_id_map.get(node.getNodeId());

    xml_edge.setAttribute("id", Integer.toString(edge_id));
    edge_id++;

    xml_edge.setAttribute("source", this_node_id.toString());
    Integer target_id = node_id_map.get(terminal.nodeId);
    xml_edge.setAttribute("target", target_id.toString());

    xml_edge.setAttribute("type", "directed");
    xml_edge.setAttribute(
        "label",
        StrandsUtil.form(strand,  terminal.strand).toString());
    return xml_edge;
  }

  public static void writeGraph(List<GraphNode> nodes, String xml_file) {
    if (xml_file.startsWith("/tmp")) {
      throw new RuntimeException(
          "Don't write the file to '/tmp' gephi has problems with that.");
    }
    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = null;
    try {
       dBuilder = dbFactory.newDocumentBuilder();
    } catch (Exception exception) {
        sLogger.error("Exception:" + exception.toString());
    }
    Document doc = dBuilder.newDocument();

    Element gexf_root = doc.createElement("gexf");
    doc.appendChild(gexf_root);
    Element root = doc.createElement("graph");
    gexf_root.appendChild(root);
    root.setAttribute("mode", "static");
    root.setAttribute("defaultedgetype", "directed");

    Element xml_nodes = doc.createElement("nodes");
    Element xml_edges = doc.createElement("edges");

    root.appendChild(xml_nodes);
    root.appendChild(xml_edges);

    // We assign each edge a unique id.
    int edge_id = 0;

    // I think the id's in the gephi xml file need to be string representations
    // of integers so we assign each node an integer.
    HashMap<String, Integer> node_id_map = new HashMap<String, Integer>();

    // Create a lookup table so we can map nodeId's to their integers.
    for (int index = 0; index < nodes.size(); ++index) {
      node_id_map.put(nodes.get(index).getNodeId(), index);
    }

    for (GraphNode node: nodes) {
      Element xml_node = doc.createElement("node");
      xml_nodes.appendChild(xml_node);

      Integer this_node_id = node_id_map.get(node.getNodeId());

      xml_node.setAttribute("id", this_node_id.toString());
      xml_node.setAttribute("label", node.getNodeId());
      xml_nodes.appendChild(xml_node);

      for (DNAStrand strand: DNAStrand.values()) {
        List<EdgeTerminal> edges =
            node.getEdgeTerminals(strand, EdgeDirection.OUTGOING);
        for (EdgeTerminal terminal: edges){
          // If the node for the edge isn't provided skip it.
          // TODO(jlewi): It would be nice to visually indicate that the
          // node for the edge is missing.
          if (!node_id_map.containsKey(terminal.nodeId)) {
            continue;
          }
          Element xml_edge = createElementForEdge(
              doc, ++edge_id, node_id_map, node, strand, terminal);
          xml_edges.appendChild(xml_edge);
        }
      }

      // write the content into xml file
      try {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(xml_file);
        transformer.transform(source, result);
      } catch (Exception exception) {
        sLogger.error("Exception:" + exception.toString());
      }
    }
  }

  private List<GraphNode> readNodes() {
    List<GraphNode> nodes = new ArrayList<GraphNode>();
    String input_path = (String) stage_options.get("inputpath");
    sLogger.info(" - input: "  + input_path);
    Schema schema = (new GraphNodeData()).getSchema();

    try {
      FileInputStream in_stream = new FileInputStream(input_path);
      SpecificDatumReader<GraphNodeData> reader =
          new SpecificDatumReader<GraphNodeData>(GraphNodeData.class);

      DataFileStream<GraphNodeData> avro_stream =
          new DataFileStream<GraphNodeData>(in_stream, reader);

      while(avro_stream.hasNext()) {
        GraphNodeData data = avro_stream.next();
        GraphNode node = new GraphNode(data);

        nodes.add(node);
      }
    } catch (IOException exception) {
      throw new RuntimeException(
          "There was a problem reading the nodes the graph to an avro file." +
          " Exception:" + exception.getMessage());
    }
    return nodes;
  }

  @Override
  public RunningJob runJob() throws Exception {
    // Check for missing arguments.
    String[] required_args = {"inputpath", "outputpath"};
    checkHasParametersOrDie(required_args);

    String outputPath = (String) stage_options.get("outputpath");
    sLogger.info(" - output: " + outputPath);

    List<GraphNode> nodes = readNodes();

    writeGraph(nodes, outputPath);
    sLogger.info("Wrote: " + outputPath);


    return null;
  }
  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new WriteGephiFile(), args);
    System.exit(res);
  }
}