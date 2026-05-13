package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerMainTest {

  @TempDir Path tempDir;

  @Test
  void wrongArgCountExitsOne() {
    var err = new ByteArrayOutputStream();
    int code = AnalyzerMain.run(new String[] {}, nullStream(), new PrintStream(err));
    assertThat(code).isEqualTo(1);
    assertThat(err.toString()).contains("Usage:");
  }

  @Test
  void missingInputFileExitsTwo() {
    var err = new ByteArrayOutputStream();
    var missing = tempDir.resolve("nope.jfr");
    var output = tempDir.resolve("out.html");
    int code =
        AnalyzerMain.run(
            new String[] {missing.toString(), output.toString()},
            nullStream(),
            new PrintStream(err));
    assertThat(code).isEqualTo(2);
    assertThat(err.toString()).contains("not found");
  }

  @Test
  void corruptJfrFileExitsFour() throws Exception {
    var jfr = tempDir.resolve("bad.jfr");
    Files.writeString(jfr, "this is not a JFR file");
    var html = tempDir.resolve("out.html");
    var err = new ByteArrayOutputStream();
    int code =
        AnalyzerMain.run(
            new String[] {jfr.toString(), html.toString()}, nullStream(), new PrintStream(err));
    // Corrupt JFR triggers either an IOException (exit 3) or a RuntimeException (exit 4)
    // depending on the JDK's RecordingFile internals — accept either.
    assertThat(code).isIn(3, 4);
    assertThat(err.toString()).startsWith("analyzer:");
  }

  @Test
  void helpFlagExitsZero() {
    var out = new ByteArrayOutputStream();
    int code = AnalyzerMain.run(new String[] {"--help"}, new PrintStream(out), nullStream());
    assertThat(code).isZero();
    assertThat(out.toString()).contains("Usage:");
  }

  private static PrintStream nullStream() {
    return new PrintStream(java.io.OutputStream.nullOutputStream());
  }
}
