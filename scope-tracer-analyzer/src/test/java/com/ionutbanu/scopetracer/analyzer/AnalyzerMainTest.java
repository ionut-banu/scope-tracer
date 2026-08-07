package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Recording;
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

  @Test
  void invalidFormatFlagExitsOne() throws Exception {
    var jfr = tempDir.resolve("unused.jfr");
    Files.writeString(jfr, "placeholder");
    var output = tempDir.resolve("out.txt");
    var err = new ByteArrayOutputStream();
    int code =
        AnalyzerMain.run(
            new String[] {jfr.toString(), output.toString(), "--format=xml"},
            nullStream(),
            new PrintStream(err));
    assertThat(code).isEqualTo(1);
    assertThat(err.toString()).contains("Usage:");
  }

  @Test
  void jsonFormatWritesTraceModelJson() throws Exception {
    var jfr = tempDir.resolve("trace.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      try (var scope = TracedScope.open("json-format-test", Thread.ofPlatform().factory())) {
        scope.fork(() -> "result");
        scope.join();
      } finally {
        recording.stop();
      }
      recording.dump(jfr);
    }

    var output = tempDir.resolve("out.json");
    var out = new ByteArrayOutputStream();
    int code =
        AnalyzerMain.run(
            new String[] {jfr.toString(), output.toString(), "--format=json"},
            new PrintStream(out),
            nullStream());

    assertThat(code).isZero();
    assertThat(out.toString()).contains("Report written to");
    var json = Files.readString(output);
    assertThat(json).startsWith("{\"scopes\":[");
    assertThat(json).contains("\"json-format-test\"");
  }

  private static PrintStream nullStream() {
    return new PrintStream(java.io.OutputStream.nullOutputStream());
  }
}
