/**
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
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

package contrail.stages;

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import contrail.util.FileHelper;

/**
 * This stage iteratively compresses chains and does error correction.
 *
 * Each round of compression can expose new topological errors that can be
 * corrected. Similarly, correcting errors can expose new chains which can
 * be compressed. Consequently, we repeatedly perform compression followed
 * by error correction until we have a round where the graph doesn't change.
 */
public class CompressAndCorrect extends Stage {
  private static final Logger sLogger = Logger.getLogger(CompressChains.class);
  /**
   * Get the parameters used by this stage.
   */
  protected Map<String, ParameterDefinition> createParameterDefinitions() {
    HashMap<String, ParameterDefinition> definitions =
        new HashMap<String, ParameterDefinition>();

    // We add all the options for the stages we depend on.
    Stage[] substages =
      {new CompressChains(), new RemoveTipsAvro(), new FindBubblesAvro(),
       new PopBubblesAvro(), new RemoveLowCoverageAvro()};

    for (Stage stage: substages) {
      definitions.putAll(stage.getParameterDefinitions());
    }

    return Collections.unmodifiableMap(definitions);
  }

  /**
   * This class is used to return information about the sub jobs that are run.
   */
  protected static class JobInfo {
    // True if the graph changed.
    public boolean graphChanged;
    // The path to the graph.
    public String graphPath;
    // A message summarizing what happened.
    public String logMessage;
  }

  private JobInfo compressGraph(String inputPath, String outputPath)
      throws Exception {
    CompressChains compressStage = new CompressChains();
    compressStage.setConf(getConf());
    // Make a shallow copy of the stage options required by the compress
    // stage.
    Map<String, Object> stageOptions =
        ContrailParameters.extractParameters(
            this.stage_options,
            compressStage.getParameterDefinitions().values());

    stageOptions.put("inputpath", inputPath);
    stageOptions.put("outputpath", outputPath);
    compressStage.setParameters(stageOptions);
    RunningJob compressJob = compressStage.runJob();

    // TODO(jlewi): We should compute how much the graph changed and output
    // this as part of the log message.
    JobInfo result = new JobInfo();
    result.logMessage = "CompressChains ran.";
    result.graphPath = compressStage.getFinalGraphPath();
    return result;
  }

  /**
   * Remove the tips in the graph.
   * @param inputPath
   * @param outputPath
   * @return True if any tips were removed.
   */
  private JobInfo removeTips(String inputPath, String outputPath)
      throws Exception {
    RemoveTipsAvro stage = new RemoveTipsAvro();
    stage.setConf(getConf());
    // Make a shallow copy of the stage options required by the compress
    // stage.
    Map<String, Object> stageOptions =
        ContrailParameters.extractParameters(
            this.stage_options,
            stage.getParameterDefinitions().values());

    stageOptions.put("inputpath", inputPath);
    stageOptions.put("outputpath", outputPath);
    stage.setParameters(stageOptions);
    RunningJob job = stage.runJob();

    JobInfo result = new JobInfo();
    if (job == null) {
      result.logMessage =
          "RemoveTips stage was skipped because graph would not change.";
      sLogger.info(result.logMessage);
      result.graphPath = inputPath;
      result.graphChanged = false;
      return result;
    }

    // Check if any tips were found.
    long tipsRemoved = job.getCounters().findCounter(
        RemoveTipsAvro.NUM_REMOVED.group,
        RemoveTipsAvro.NUM_REMOVED.tag).getValue();

    if (tipsRemoved > 0) {
      result.graphChanged = true;
    }
    result.graphPath = outputPath;

    Formatter formatter = new Formatter(new StringBuilder());
    result.logMessage = formatter.format(
        "RemoveTips: number of nodes removed %d", tipsRemoved).toString();
    return result;
  }

  /**
   * PopBubbles in the graph.
   * @param inputPath
   * @param outputPath
   * @return JobInfo
   */
  private JobInfo popBubbles(String inputPath, String outputPath)
      throws Exception {
    FindBubblesAvro findStage = new FindBubblesAvro();
    findStage.setConf(getConf());
    String findOutputPath = new Path(outputPath, "FindBubbles").toString();
    {
      // Make a shallow copy of the stage options required.
      Map<String, Object> stageOptions =
          ContrailParameters.extractParameters(
              this.stage_options,
              findStage.getParameterDefinitions().values());

      stageOptions.put("inputpath", inputPath);
      stageOptions.put("outputpath", findOutputPath);
      findStage.setParameters(stageOptions);
    }

    RunningJob findJob = findStage.runJob();

    if (findJob == null) {
      JobInfo result = new JobInfo();
      result.logMessage =
          "FindBubbles stage was skipped because graph would not change.";
      sLogger.info(result.logMessage);
      result.graphPath = inputPath;
      result.graphChanged = false;
      return result;
    }

    // Check if any bubbles or palindromes were found.
    long bubblesFound = findJob.getCounters().findCounter(
        FindBubblesAvro.NUM_BUBBLES.group,
        FindBubblesAvro.NUM_BUBBLES.tag).getValue();

    long palindromesFound = findJob.getCounters().findCounter(
        FindBubblesAvro.NUM_PALINDROMES.group,
        FindBubblesAvro.NUM_PALINDROMES.tag).getValue();

    if (bubblesFound == 0 && palindromesFound == 0) {
      // Since no bubbles and no palindromes were found,
      // we don't need to run the second phase of pop bubbles.
      JobInfo result = new JobInfo();
      result.graphChanged = false;
      // Since the graph didn't change return the input path as the path to
      // the graph.
      result.graphPath = inputPath;
      result.logMessage =
          "FindBubbles found 0 bubbles and 0 palindromes.";
      return result;
    }

    PopBubblesAvro popStage = new PopBubblesAvro();
    popStage.setConf(getConf());
    String popOutputPath = new Path(outputPath, "PopBubbles").toString();
    {
      // Make a shallow copy of the stage options required.
      Map<String, Object> stageOptions =
          ContrailParameters.extractParameters(
              this.stage_options,
              popStage.getParameterDefinitions().values());

      stageOptions.put("inputpath", findOutputPath);
      stageOptions.put("outputpath", popOutputPath);
      popStage.setParameters(stageOptions);
    }
    RunningJob popJob = popStage.runJob();

    JobInfo result = new JobInfo();
    result.graphChanged = true;
    result.graphPath = popOutputPath;

    Formatter formatter = new Formatter(new StringBuilder());
    result.logMessage = formatter.format(
        "FindBubbles: number of nodes removed %d", bubblesFound).toString();
    return result;
  }

  /**
   * Remove low coverage nodes.
   * @param inputPath
   * @param outputPath
   * @return True if any tips were removed.
   */
  private JobInfo removeLowCoverageNodes(String inputPath, String outputPath)
      throws Exception {
    RemoveLowCoverageAvro stage = new RemoveLowCoverageAvro();
    stage.setConf(getConf());
    // Make a shallow copy of the stage options required by the compress
    // stage.
    Map<String, Object> stageOptions =
        ContrailParameters.extractParameters(
            this.stage_options,
            stage.getParameterDefinitions().values());

    stageOptions.put("inputpath", inputPath);
    stageOptions.put("outputpath", outputPath);
    stage.setParameters(stageOptions);
    RunningJob job = stage.runJob();

    JobInfo result = new JobInfo();
    if (job == null) {
      result.logMessage =
          "RemoveLowCoverage stage was skipped because graph would not " +
           "change.";
      sLogger.info(result.logMessage);
      result.graphPath = inputPath;
      result.graphChanged = false;
      return result;
    }
    // Check if any tips were found.
    long nodesRemoved = job.getCounters().findCounter(
        RemoveLowCoverageAvro.NUM_REMOVED.group,
        RemoveLowCoverageAvro.NUM_REMOVED.tag).getValue();

    result.graphPath = outputPath;
    if (nodesRemoved > 0) {
      result.graphChanged = true;
    }

    Formatter formatter = new Formatter(new StringBuilder());
    result.logMessage = formatter.format(
        "RemoveLowCoverage removed %d nodes.", nodesRemoved).toString();
    return result;
  }

  /**
   * Class contains the result of compressing the graph as much as possible.
   */
  private class CompressionResult {
    // The current step.
    int step;

    // The latest path.
    String latestPath;
  }

  private String tempPath() {
    String outputPath = (String) stage_options.get("outputpath");
    // A subdirectory of the output path to contain the temporary
    // output from each substage.
    String tempPath = new Path(outputPath, "temp").toString();
    return tempPath;
  }

  /**
   * Compress the graph as much as possible, removing tips and popping bubbles.
   *
   * @param step: Integer identifying the step number.
   */
  private CompressionResult compressAsMuchAsPossible(
      int step, String stepInputPath) throws Exception {
    // TODO(jlewi): Does this function really need to throw an exception?
    String outputPath = (String) stage_options.get("outputpath");

    // When formatting the step as a string we want to zero pad it
    DecimalFormat sf = new DecimalFormat("00");

    // Keep track of the latest input for the step.
    boolean  done = false;

    while (!done) {
      ++step;
      sLogger.info("Step " + sf.format(step).toString());

      // Create a subdirectory of the temp directory to contain the output
      // from this round.
      String stepPath = new Path(
          tempPath(), "step_" +sf.format(step)).toString();

      // Paths to use for this round. The paths for the compressed graph
      // and the error corrected graph.
      String compressedPath = new Path(
          stepPath, "CompressChains").toString();
      String removeTipsPath = new Path(stepPath, "RemoveTips").toString();

      JobInfo compressResult = compressGraph(stepInputPath, compressedPath);
      sLogger.info(compressResult.logMessage);

      JobInfo tipsResult = removeTips(compressResult.graphPath, removeTipsPath);
      sLogger.info(tipsResult.logMessage);

      if (tipsResult.graphChanged) {
        stepInputPath = tipsResult.graphPath;
        // We need to recompress the graph before continuing.
        continue;
      }

      // There were no tips, so the graph is maximally compressed. Try
      // finding and removing bubbles.
      String popBubblesPath = new Path(stepPath, "PoppedBubbles").toString();
      JobInfo popResult = popBubbles(tipsResult.graphPath, popBubblesPath);
      sLogger.info(popResult.logMessage);
      stepInputPath = popResult.graphPath;
      if (!popResult.graphChanged) {
        done = true;
      }
    }

    CompressionResult result = new CompressionResult();
    result.step = step;
    result.latestPath = stepInputPath;
    return result;
  }

  private void processGraph() throws Exception {
    // TODO(jlewi): Does this function really need to throw an exception?
    String outputPath = (String) stage_options.get("outputpath");

    int step = 0;

    // When formatting the step as a string we want to zero pad it
    DecimalFormat sf = new DecimalFormat("00");

    String inputPath = (String) stage_options.get("inputpath");

    // Compress the graph as much as possible, removing tips and popping
    // bubbles.
    CompressionResult initialCompression = compressAsMuchAsPossible(
        0, inputPath);

    // Having compressed the graph and popping bubbles, operations which could
    // increase the average coverage of some nodes, we remove low coverage
    // nodes.
    JobInfo lowCoverageResult;
    {
      // Create a subdirectory of the temp directory to contain the output
      // from this round.
      String stepPath = new Path(
          tempPath(), "step_" +sf.format(step)).toString();
      step = initialCompression.step + 1;
      String lowCoveragePath =
          new Path(stepPath, "LowCoveragePath").toString();
      lowCoverageResult = removeLowCoverageNodes(
              initialCompression.latestPath, lowCoveragePath);
      sLogger.info(lowCoverageResult.logMessage);;
    }

    String finalGraphPath;

    // If remove low coverage changed the graph then run another round of
    // compression.
    if (lowCoverageResult.graphChanged) {
      ++step;
      CompressionResult finalCompression = compressAsMuchAsPossible(
          0, inputPath);
      finalGraphPath = finalCompression.latestPath;
    } else {
      finalGraphPath = lowCoverageResult.graphPath;
    }

    sLogger.info("Save result to: " + outputPath + "\n\n");
    FileHelper.moveDirectoryContents(getConf(), finalGraphPath, outputPath);
    sLogger.info("Final graph saved to:" + outputPath);

    // Clean up the intermediary directories.
    // TODO(jlewi): We might want to add an option to keep the intermediate
    // directories.
    sLogger.info("Delete temporary directory: " + tempPath() + "\n\n");
    FileSystem.get(getConf()).delete(new Path(tempPath()), true);
  }

  @Override
  public RunningJob runJob() throws Exception {
    String[] required_args = {"inputpath", "outputpath"};
    checkHasParametersOrDie(required_args);

    if (stage_options.containsKey("writeconfig")) {
      // TODO(jlewi): Can we write the configuration for this stage like
      // other stages or do we need to do something special?
      throw new NotImplementedException(
          "Support for writeconfig isn't implemented yet for " +
          "CompressAndCorrect");
    } else {
      long starttime = System.currentTimeMillis();
      processGraph();
      long endtime = System.currentTimeMillis();

      float diff = (float) ((endtime - starttime) / 1000.0);
      sLogger.info("Runtime: " + diff + " s");
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    int res = ToolRunner.run(
        new Configuration(), new CompressAndCorrect(), args);
    System.exit(res);
  }
}