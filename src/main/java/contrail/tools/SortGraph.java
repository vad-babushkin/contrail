package contrail.tools;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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

import contrail.stages.ContrailParameters;
import contrail.stages.ParameterDefinition;
import contrail.stages.Stage;
import contrail.graph.GraphNodeData;

/**
 * A simple mapreduce job which sorts the graph by the node ids.
 */
public class SortGraph extends Stage {
  private static final Logger sLogger = Logger.getLogger(SortGraph.class);

  /**
   * Get the parameters used by this stage.
   */
  @Override
  protected Map<String, ParameterDefinition> createParameterDefinitions() {
    HashMap<String, ParameterDefinition> defs =
      new HashMap<String, ParameterDefinition>();

    defs.putAll(super.createParameterDefinitions());
    for (ParameterDefinition parameter :
         ContrailParameters.getInputOutputPathOptions()) {
      defs.put(parameter.getName(), parameter);
    }

    return Collections.unmodifiableMap(defs);
  }

  public static class SortGraphMapper extends
    AvroMapper<GraphNodeData, Pair<CharSequence, GraphNodeData>> {
    private Pair<CharSequence, GraphNodeData> pair;
    public void configure(JobConf job) {
      pair = new Pair<CharSequence, GraphNodeData>("", new GraphNodeData());
    }

    @Override
    public void map(GraphNodeData input,
        AvroCollector<Pair<CharSequence, GraphNodeData>> collector,
        Reporter reporter)
            throws IOException {
      pair.set(input.getNodeId(), input);
      collector.collect(pair);
    }
  }

  public static class SortGraphReducer extends
      AvroReducer<CharSequence, GraphNodeData, GraphNodeData> {
    @Override
    public void reduce(CharSequence nodeId, Iterable<GraphNodeData> iterable,
        AvroCollector<GraphNodeData> collector, Reporter reporter)
            throws IOException {
      Iterator<GraphNodeData> iterator = iterable.iterator();
      if (!iterator.hasNext()) {
        sLogger.fatal(
            "No node for nodeid:" + nodeId, new RuntimeException("No node."));
        throw new RuntimeException("No node.");
      }
      collector.collect(iterator.next());
      if (iterator.hasNext()) {
        sLogger.fatal(
            "Multiple nodes for nodeid:" + nodeId,
            new RuntimeException("multiple nodes."));
        throw new RuntimeException("Multiple node.");
      }
    }
  }

  @Override
  public RunningJob runJob() throws Exception {
    // Check for missing arguments.
    String[] required_args = {"inputpath", "outputpath"};
    checkHasParametersOrDie(required_args);

    String inputPath = (String) stage_options.get("inputpath");
    String outputPath = (String) stage_options.get("outputpath");

    sLogger.info(" - input: "  + inputPath);
    sLogger.info(" - output: " + outputPath);

    Configuration base_conf = getConf();
    JobConf conf = null;
    if (base_conf != null) {
      conf = new JobConf(getConf(), this.getClass());
    } else {
      conf = new JobConf(this.getClass());
    }
    conf.setJobName("SortGraph");

    initializeJobConfiguration(conf);

    FileInputFormat.addInputPath(conf, new Path(inputPath));
    FileOutputFormat.setOutputPath(conf, new Path(outputPath));

    GraphNodeData nodeData = new GraphNodeData();
    AvroJob.setInputSchema(conf, nodeData.getSchema());

    Pair<CharSequence, GraphNodeData> pair =
        new Pair<CharSequence, GraphNodeData>("", nodeData);
    AvroJob.setMapOutputSchema(conf, pair.getSchema());
    AvroJob.setOutputSchema(conf, nodeData.getSchema());

    AvroJob.setMapperClass(conf, SortGraphMapper.class);
    AvroJob.setReducerClass(conf, SortGraphReducer.class);

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
      RunningJob result = JobClient.runJob(conf);
      long endtime = System.currentTimeMillis();

      float diff = (float) ((endtime - starttime) / 1000.0);

      sLogger.info("Runtime: " + diff + " s");
      return result;
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new SortGraph(), args);
    System.exit(res);
  }
}
