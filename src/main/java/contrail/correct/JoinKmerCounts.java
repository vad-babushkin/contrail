/**
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
// Author: Jeremy Lewi (jeremy@lewi.us)
package contrail.correct;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.avro.mapred.AvroCollector;
import org.apache.avro.mapred.AvroJob;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapred.AvroMapper;
import org.apache.avro.mapred.AvroValue;
import org.apache.avro.mapred.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.codehaus.jackson.map.ObjectMapper;

import contrail.stages.ContrailParameters;
import contrail.stages.MRStage;
import contrail.stages.ParameterDefinition;
import contrail.util.ContrailLogger;

/**
 * Join the KMer counts from before and after error correction.
 *
 * This stage is primarily useful for evaluating Quake.
 * The output is a json record suitable for import into Big Query.
 */
public class JoinKmerCounts extends MRStage {
  private static final ContrailLogger sLogger = ContrailLogger.getLogger(
      JoinKmerCounts.class);

  @Override
  protected Map<String, ParameterDefinition> createParameterDefinitions() {
    HashMap<String, ParameterDefinition> defs =
        new HashMap<String, ParameterDefinition>();
    defs.putAll(super.createParameterDefinitions());
    for (ParameterDefinition def:
      ContrailParameters.getInputOutputPathOptions()) {
      defs.put(def.getName(), def);
    }

    defs.remove("inputpath");

    ParameterDefinition before = new ParameterDefinition(
        "before", "The directory containing the KMer counts before correction.",
        String.class,
        null);

    ParameterDefinition after = new ParameterDefinition(
        "after", "The directory containing the KMer counts after correction.",
        String.class,
        null);


    defs.put(before.getName(), before);
    defs.put(after.getName(), after);
    return Collections.unmodifiableMap(defs);
  }

  /**
   * Phase describes whether the count corresponds to before or after error
   * correction.
   */
  protected enum Phase {
    After,
    Before,
  }
  public static class Mapper extends AvroMapper<
      Pair<CharSequence, Long>, Pair<CharSequence, Pair<Phase, Long>>> {

    private Phase phase;
    private Pair<CharSequence, Pair<Phase, Long>> outPair;

    @Override
    public void configure(JobConf job) {
      JoinKmerCounts stage = new JoinKmerCounts();
      Map<String, ParameterDefinition> definitions =
          stage.getParameterDefinitions();
      String  after = (String)(definitions.get("after").parseJobConf(job));
      String  before = (String)(definitions.get("before").parseJobConf(job));

      // Determine which phase it came from by comparing the filepath.
      String filename = job.get("map.input.file");
      if (filename.startsWith(after)) {
        phase = Phase.After;
      } else if (filename.startsWith(before)) {
        phase = Phase.Before;
      } else {
        sLogger.fatal(
            "Could not figure out which phase the input belongs to. File: " +
            filename, new RuntimeException("Invalid input."));
      }
      Pair<Phase, Long> phasePair = new Pair<Phase, Long>(phase, 0L);
      outPair = new Pair<CharSequence, Pair<Phase, Long>>("", phasePair);
    }

    /**
     * Mapper emits out <Kmer, 1> pairs
     */
    @Override
    public void map(
        Pair<CharSequence, Long> input,
        AvroCollector<Pair<CharSequence, Pair<Phase, Long>>> collector,
        Reporter reporter) throws IOException {
      outPair.key(input.key());
      outPair.value().key(phase);
      outPair.value().value(input.value());
      collector.collect(outPair);
    }
  }

  public static class JoinReducer extends MapReduceBase
      implements Reducer<
          AvroKey<CharSequence>, AvroValue<Pair<Phase, Long>>,
          Text, NullWritable> {

    private static class CountsRecord {
      public String Kmer;
      public long before;
      public long after;

      public CountsRecord() {
        clear();
      }

      public void clear() {
        Kmer = "";
        before = 0;
        after = 0;
      }
    }

    private CountsRecord counts;
    private Text outKey;
    private ObjectMapper jsonMapper;

    @Override
    public void configure(JobConf job) {
      counts = new CountsRecord();
      outKey = new Text();
      jsonMapper = new ObjectMapper();
    }

    @Override
    public void reduce(
        AvroKey<CharSequence> key,
        Iterator<AvroValue<Pair<Phase, Long>>> values,
        OutputCollector<Text, NullWritable> collector, Reporter reporter)
        throws IOException {
      counts.clear();

      outKey.set(jsonMapper.writeValueAsString(counts));
      collector.collect(outKey, NullWritable.get());
    }
  }

  @Override
  protected void setupConfHook() {
    JobConf conf = (JobConf) getConf();
    String inputPath = (String) stage_options.get("inputpath");
    String outputPath = (String) stage_options.get("outputpath");

    FileInputFormat.addInputPath(conf, new Path(inputPath));
    FileOutputFormat.setOutputPath(conf, new Path(outputPath));

    Pair<CharSequence, Long> inputPair = new Pair<CharSequence, Long>("", 0L);
    AvroJob.setInputSchema(conf, inputPair.getSchema());

    Pair<CharSequence, Pair<Phase, Long>> mapOutputPair =
        new Pair<CharSequence, Pair<Phase, Long>>(
            "", new Pair<Phase, Long>(Phase.After, 0L));
    AvroJob.setMapOutputSchema(conf, mapOutputPair.getSchema());
    AvroJob.setMapperClass(conf, Mapper.class);

    conf.setReducerClass(JoinReducer.class);
    conf.setOutputFormat(TextOutputFormat.class);
    conf.setOutputKeyClass(Text.class);
    conf.setOutputValueClass(NullWritable.class);
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(new Configuration(), new JoinKmerCounts(), args);
    System.exit(res);
  }
}