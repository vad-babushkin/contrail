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
package contrail.scaffolding;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import contrail.sequences.FastQFileReader;
import contrail.sequences.FastQRecord;
import contrail.sequences.FastUtil;

/**
 * This class is used to run the bowtie short read aligner.
 */
public class BowtieRunner {
  private static final Logger sLogger = Logger.getLogger(BowtieRunner.class);
  private final String bowtiePath;
  private final String bowtieBuildPath;

  // The initial capacity for the array to store the alignments to each
  // reference contig.
  private static final int NUM_READS_PER_CTG = 200;

  /**
   * Construct the runner.
   * @param bowTiePath: Path to bowtie.
   * @param bowtieBuildPath: Path to bowtie-build the binary for building
   *   bowtie indexes.
   */
  public BowtieRunner(String bowtiePath, String bowtieBuildPath) {
    this.bowtiePath = bowtiePath;
    this.bowtieBuildPath = bowtieBuildPath;
  }

  /**
   * Build the bowtie index by invoking bambus.
   *
   * @param contigFiles
   * @param outDir: The directory where the index should be written.
   * @param outBase: The base name for the index files.
   * @return: True if the build was successful and false otherwise.
   */
  public boolean bowtieBuildIndex(
      Collection<String> contigFiles, String outDir, String outBase) {
    String fileList = StringUtils.join(contigFiles, ",");
    String command = String.format(
        "%s %s %s", bowtieBuildPath, fileList,
        new File(outDir, outBase).getAbsolutePath());
    boolean success = false;

    File outDirFile = new File(outDir);
    // TODO(jeremy@lewi.us): What should we do if the index directory
    // already exists?
    if (!outDirFile.exists()) {
      try {
        FileUtils.forceMkdir(outDirFile);
      } catch (IOException e) {
        sLogger.fatal(
            "Could not create the output directory for the bowtie index:" +
            outDirFile.getPath(), e);
      }
    } else {
      sLogger.warn(
          "Directory to store the bowtie index already exists: " + outDir);
    }

    try {
      sLogger.info("Running bowtie to build index:" + command);
      Process p = Runtime.getRuntime().exec(command);

      BufferedReader stdInput = new BufferedReader(
          new InputStreamReader(p.getInputStream()));

      BufferedReader stdError = new BufferedReader(
          new InputStreamReader(p.getErrorStream()));

      p.waitFor();
      String line;
      while ((line = stdInput.readLine()) != null) {
        sLogger.info("bowtie-build: " + line);
      }
      while ((line = stdError.readLine()) != null) {
        sLogger.error("bowtie-build: " + line);
      }

      sLogger.info("bowtie-build: Exit Value: " + p.exitValue());
      if (p.exitValue() == 0) {
        success = true;
      } else {
        sLogger.error("bowtie-build failed command was: " + command);
        success = false;
      }

    } catch (IOException e) {
      throw new RuntimeException(
          "There was a problem executing bowtie. The command was:\n" +
              command + "\n. The Exception was:\n" + e.getMessage());
    } catch (InterruptedException e) {
      throw new RuntimeException(
          "Bowtie execution was interupted. The command was:\n" +
              command + "\n. The Exception was:\n" + e.getMessage());
    }
    return success;
  }

  /**
   * Class to contain the result of running bowtie to align the reads.
   */
  public static class AlignResult {
    public AlignResult() {
      success = false;
      outputs = new HashMap<String, String>();
    }
    boolean success;

    // This is a map from the names of the input fastq files to the name
    // of the output file containing the alignments.
    HashMap<String, String> outputs;
  }

  /**
   * Shorten the fastq entries in the file to the specified length output them.
   * @param inFile
   * @param outFile
   * @param readLength
   */
  private void shortenFastQFile(String inFile, File outFile, int readLength) {
    try {
      PrintStream out = new PrintStream(outFile);
      FastQFileReader reader = new FastQFileReader(inFile);

      while (reader.hasNext()) {
        FastQRecord record = reader.next();

        // We need to make the read ids safe.
        record.setId(Utils.safeReadId(record.getId().toString()));
        if (readLength < record.getRead().length()) {
          record.setRead(
              record.getRead().toString().subSequence(0, readLength));
          record.setQvalue(
              record.getQvalue().toString().subSequence(0, readLength));
        }
        FastUtil.writeFastQRecord(out, record);
      }
      if (out.checkError()) {
        sLogger.fatal(
            "There was a problem writing the shortened reads.",
            new RuntimeException("Could not write shortened reads."));
        System.exit(-1);
      }
    } catch (Exception e) {
      sLogger.fatal("Could not write shortened reads." , e);
      System.exit(-1);
    }
  }

  /**
   * Run the bowtie aligner.
   * @param indexDir: The base directory for the bowtie index.
   * @param indexBase: The prefix for the bowtie index files.
   * @param fastqFiles: Collection of the fastq files to align.
   *   The reads should already have been truncated to a suitable length.
   * @param readLength: Reads are shortened to this length before aligning
   *   them. This is neccessary because bowtie can only handle short reads.
   * @param outDir: The directory where the bowtie output should be written.
   * @return: An instance of AlignResult containing the results.
   */
  public AlignResult alignReads(
      String indexDir, String indexBase, Collection<String> fastqFiles,
      String outDir, int readLength) {
    AlignResult result = new AlignResult();
    result.success = false;

    File outDirFile = new File(outDir);
    // TODO(jeremy@lewi.us): What should we do if the index directory
    // already exists?
    if (!outDirFile.exists()) {
      outDirFile.mkdir();
    } else {
      sLogger.warn(
          "Directory to store the bowtie alignments already exists: " + outDir);
    }

    String bowtieIndex = new File(indexDir, indexBase).getAbsolutePath();

    // We keep track of all outputs written so far to make sure we don't
    // overwrite an existing output.
    HashSet<String> outputs = new HashSet<String>();

    for (String fastqFile : fastqFiles) {
      sLogger.info("Aligning the reads in file: " + fastqFile);
      String baseName = FilenameUtils.getBaseName(fastqFile);
      String outFile = FilenameUtils.concat(outDir, baseName + ".bout");

      File shortFastq = null;

      try {
        shortFastq = File.createTempFile("temp", baseName + ".fastq");
      } catch (IOException e) {
        sLogger.fatal(
            "Couldn't create temporary file for shortened reads.", e);
      }
      shortenFastQFile(fastqFile, shortFastq, readLength);

      if (outputs.contains(outFile)) {
        // TODO(jeremy@lewi.us). We could handle this just by appending
        // a suffix to make the filename unique.
        String message =
            "Error output: " + outFile + " would overwrite the output for a " +
                "previous input. This can happen if two input files have the " +
                "same base name.";
        sLogger.fatal(message, new RuntimeException(message));
        System.exit(-1);
      }

      outputs.add(outFile);
      result.outputs.put(fastqFile, outFile);
      String command = String.format(
          "%s -v 1 -M 2  %s %s %s", bowtiePath, bowtieIndex,
          shortFastq.getPath(), outFile);

      // TODO(jeremy@lewi.us) use one of the functions in ShellUtil.
      try {
        sLogger.info("Running bowtie to align the reads:" + command);
        Process p = Runtime.getRuntime().exec(command);

        BufferedReader stdInput = new BufferedReader(
            new InputStreamReader(p.getInputStream()));

        BufferedReader stdError = new BufferedReader(
            new InputStreamReader(p.getErrorStream()));

        p.waitFor();
        String line;
        while ((line = stdInput.readLine()) != null) {
          sLogger.info("bowtie: " + line);
        }
        while ((line = stdError.readLine()) != null) {
          // bowtie appears to write non error messages to the error stream so we use info
          // to log the contents of the error stream.
          sLogger.info("bowtie: " + line);
        }

        sLogger.info("bowtie: Exit Value: " + p.exitValue());
        if (p.exitValue() == 0) {
          result.success = true;
        } else {
          sLogger.error("bowtie-build failed command was: " + command);
        }

      } catch (IOException e) {
        throw new RuntimeException(
            "There was a problem executing bowtie. The command was:\n" +
                command + "\n. The Exception was:\n" + e.getMessage());
      } catch (InterruptedException e) {
        throw new RuntimeException(
            "Bowtie execution was interupted. The command was:\n" +
                command + "\n. The Exception was:\n" + e.getMessage());
      }

      shortFastq.delete();
    }

    return result;
  }

  public static class MappingInfo {
    public String readID = null;

    // position on the read
    int start = 0;
    int end = 0;

    // position on the contig
    int contigStart = 0;
    int contigEnd = 0;
  }

  /**
   * Read the bowtie output.
   *
   * @param bowtieFiles: A list of the files containing the bowtie output.
   * @param subLen: TODO(jeremy@lewi.us) figure out what this parameters is.
   * @param return: This is a hashmap keyed by the id of each contig. The value
   *   is an array of MappingInfo. Each MappingInfo stores information about
   *   a read aligned to contig given by the key.
   * @throws Exception
   */
  public HashMap<String, ArrayList<MappingInfo>> readBowtieResults(
      Collection<String> bowtieFiles, int subLen) {
    HashMap<String, ArrayList<MappingInfo>> map =
        new HashMap<String, ArrayList<MappingInfo>>();

    final int PROGRESS_INCREMENT = 1000000;
    sLogger.warn(
        "Setting the end of the contig map to:" + subLen + " this code may " +
        "be incorrect.");
    for (String bowtieFile : bowtieFiles) {
      BufferedReader reader;
      try {
        reader = new BufferedReader(new FileReader(bowtieFile));
      } catch (Exception e) {
        throw new RuntimeException(
            "Could not open file for reading: " + bowtieFile +
            "exception was: " + e.getMessage());
      }

      String baseName = FilenameUtils.getName(bowtieFile);
      String filePrefix = FilenameUtils.getBaseName(bowtieFile);

      sLogger.info("Reading alignments from:" + bowtieFile);

      // Compute the library name. We need to strip the "_#" suffix from
      // the basename.
      String libraryName;
      if (filePrefix.contains("_")) {
        libraryName = filePrefix.split("_", 2)[0];
      } else {
        libraryName = filePrefix;
      }
      sLogger.info("File belongs to library:" + libraryName);
      String line = null;
      int counter = 0;
      try {
        line = reader.readLine();
      } catch (IOException e) {
        throw new RuntimeException(e.getMessage());
      }
      while (line != null) {
        if ((counter % PROGRESS_INCREMENT == 0) && (counter > 0)) {
          sLogger.info(
              "Read " + counter + " mapping records from " + baseName);
        }

        String[] splitLine = line.trim().split("\\s+");
        MappingInfo m = new MappingInfo();

        int position = 1;
        // skip crud
        // The first field in the output is the name of the read that was
        // aligned. The second field is a + or - indicating which strand
        // the read aligned to. We identify the position of the +/- in
        // the split line and use this to determine the mapping of output
        // fields to indexes in splitLine.
        while (!splitLine[position].equalsIgnoreCase("+") &&
            !splitLine[position].equalsIgnoreCase("-")) {
          position++;
        }

        String readID = splitLine[position - 1];
        String strand = splitLine[position];
        String contigID = splitLine[position + 1];

        // 0-based offset into the forward reference strand where leftmost
        // character of the alignment occurs.
        String forwardOffset = splitLine[position + 2];
        String readSequence = splitLine[position + 3];

        // isFWD indicates the read was aligned to the forward strand of
        // the reference genome.
        Boolean isFwd = null;
        if (strand.contains("-")) {
          isFwd = false;
        } else if (strand.contains("+")) {
          isFwd = true;
        } else {
          throw new RuntimeException("Couldn't parse the alignment strand");
        }

        // TODO(jeremy@lewi.us): It looks like the original code prefixed
        // the read id's with the library name.
        // We don't prefix the read with the library
        // name because we think that's more likely to cause problems because
        // the read ids would need to be changed consistently everywhere.
        m.readID = readID;
        m.start = 1;
        // TODO(jeremy@lewi.us): Need to check whether the length should be
        // zero based or 1 based. The original code set this to SUB_LEN
        // which was the length of the truncated reads which were aligned.
        // TODO(jerem@lewi.us): Do we have to pass in SUB_LEN or can we
        // determine it from the output.
        m.end = subLen;


        m.contigStart = Integer.parseInt(forwardOffset);
        if (isFwd) {
          m.contigEnd = m.contigStart + readSequence.length() - 1;
        } else {
          m.contigEnd = m.contigStart;
          m.contigStart = m.contigEnd + readSequence.length() - 1;
        }

        if (map.get(contigID) == null) {
          map.put(contigID, new ArrayList<MappingInfo>(NUM_READS_PER_CTG));
        }
        map.get(contigID).add(m);
        counter++;
        try {
          line = reader.readLine();
        } catch (IOException e) {
          throw new RuntimeException(e.getMessage());
        }
      }
      try {
        reader.close();
      } catch (IOException e) {
        throw new RuntimeException(e.getMessage());
      }
      // Print out the final record count.
      sLogger.info(
          "Total of " + counter + " mapping records were found in " +
              baseName);
    }
    return map;
  }
}