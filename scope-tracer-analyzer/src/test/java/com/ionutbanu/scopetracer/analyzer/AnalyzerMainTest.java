package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
      try (var scope =
          TracedScope.open(
              "json-format-test", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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

  // --- #58: arguments ---

  private static final String USAGE_LINE =
      "Usage: analyzer <input.jfr> <output> [--format=json] [--verbose]";

  private static final String URL =
      "https://github.com/ionut-banu/scope-tracer/blob/main/docs/jfr-events.md"
          + "#high-volume-recordings-jfr-buffer-tuning";

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "IN OUT",
        "IN OUT --verbose",
        "--verbose IN OUT",
        "IN --verbose OUT",
        "IN OUT --format=json --verbose",
        "--format=json --verbose IN OUT",
        "--verbose --format=json IN OUT",
        "IN --format=json OUT --verbose",
        "--format=json IN OUT"
      })
  void optionsAreAcceptedInAnyPosition(String line) throws Exception {
    var jfr = TestRecordings.complete(tempDir.resolve("any.jfr"), "cli-any-position", 1);
    var output = tempDir.resolve("any.out");
    var run = run(line.replace("IN", jfr.toString()).replace("OUT", output.toString()).split(" "));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo("Report written to " + output.toAbsolutePath() + "\n");
    assertThat(run.err()).isEmpty();
    String content = Files.readString(output);
    if (line.contains("--format=json")) {
      assertThat(content).startsWith("{\"scopes\":[");
    } else {
      assertThat(content).startsWith("<!DOCTYPE html>");
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "IN OUT --verbose --verbose",
        "--verbose IN --verbose OUT",
        "IN OUT --format=json --format=json",
        "IN OUT --verbos",
        "IN OUT -v",
        "IN OUT --format=html",
        "IN OUT --format=xml",
        "IN OUT THIRD",
        "IN OUT THIRD --format=json",
        "IN",
        "--verbose",
        "--verbose IN",
        "--help IN OUT",
        "IN OUT -h",
        "-h --verbose"
      })
  void wrongCommandLinesPrintTheUsageOnStderrAndExitOne(String line) throws Exception {
    var jfr = TestRecordings.complete(tempDir.resolve("wrong.jfr"), "cli-wrong", 1);
    var output = tempDir.resolve("wrong.out");
    var run =
        run(
            line.replace("IN", jfr.toString())
                .replace("OUT", output.toString())
                .replace("THIRD", tempDir.resolve("third").toString())
                .split(" "));

    assertThat(run.code()).isEqualTo(1);
    assertThat(run.out()).isEmpty();
    assertThat(run.err()).isEqualTo(USAGE_LINE + "\n");
    assertThat(output).doesNotExist();
  }

  @Test
  void noArgumentsPrintsTheUsageOnStderrAndExitsOne() {
    var run = run();
    assertThat(run.code()).isEqualTo(1);
    assertThat(run.out()).isEmpty();
    assertThat(run.err()).isEqualTo(USAGE_LINE + "\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"-h", "--help"})
  void helpAlonePrintsTheUsageOnStdoutAndExitsZero(String flag) {
    var run = run(flag);
    assertThat(run.code()).isZero();
    assertThat(run.out()).isEqualTo(USAGE_LINE + "\n");
    assertThat(run.err()).isEmpty();
  }

  // --- #58: exit codes are what they were ---

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void missingInputExitsTwoWithTheSameLine(boolean verbose) {
    var missing = tempDir.resolve("nope.jfr");
    var run = run(withVerbose(verbose, missing.toString(), tempDir.resolve("o.html").toString()));
    assertThat(run.code()).isEqualTo(2);
    assertThat(run.out()).isEmpty();
    assertThat(run.err()).isEqualTo("analyzer: input.jfr not found: " + missing + "\n");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void outputInAMissingDirectoryExitsTwoWithTheSameLine(boolean verbose) throws Exception {
    var jfr = TestRecordings.complete(tempDir.resolve("in.jfr"), "cli-missing-dir", 1);
    var output = tempDir.resolve("no-such-dir").resolve("o.html");
    var run = run(withVerbose(verbose, jfr.toString(), output.toString()));
    assertThat(run.code()).isEqualTo(2);
    assertThat(run.out()).isEmpty();
    assertThat(run.err()).isEqualTo("analyzer: file not found: " + output + "\n");
  }

  @Test
  void notAJfrFileExitsThreeWithOneLine() throws Exception {
    var jfr = tempDir.resolve("bad.jfr");
    Files.writeString(jfr, "this is not a JFR file");
    var run = run(jfr.toString(), tempDir.resolve("o.html").toString());
    assertThat(run.code()).isEqualTo(3);
    assertThat(run.out()).isEmpty();
    assertThat(run.err()).isEqualTo("analyzer: I/O error: Not a complete Chunk header\n");
  }

  @Test
  void notAJfrFileWithVerboseAddsTheStackTraceAfterTheLine() throws Exception {
    var jfr = tempDir.resolve("bad.jfr");
    Files.writeString(jfr, "this is not a JFR file");
    var run = run(jfr.toString(), tempDir.resolve("o.html").toString(), "--verbose");
    assertThat(run.code()).isEqualTo(3);
    assertThat(run.out()).isEmpty();
    var lines = run.err().lines().toList();
    assertThat(lines.get(0)).isEqualTo("analyzer: I/O error: Not a complete Chunk header");
    assertThat(lines.get(1)).startsWith("java.io.IOException: Not a complete Chunk header");
    assertThat(lines.subList(2, lines.size())).isNotEmpty().allMatch(l -> l.startsWith("\tat "));
  }

  /** A scope-tracer event type without {@code scopeId}: the parser throws, which exits 4. */
  @Name("com.ionutbanu.scopetracer.CliTestWithoutScopeId")
  static final class WithoutScopeId extends Event {}

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void parserFailureExitsFourAndOnlyVerboseAddsTheStackTrace(boolean verbose) throws Exception {
    var jfr =
        TestRecordings.record(tempDir.resolve("four.jfr"), (r, f) -> new WithoutScopeId().commit());
    var run = run(withVerbose(verbose, jfr.toString(), tempDir.resolve("o.html").toString()));
    assertThat(run.code()).isEqualTo(4);
    assertThat(run.out()).isEmpty();
    var lines = run.err().lines().toList();
    assertThat(lines.get(0)).startsWith("analyzer: failed to parse JFR recording " + jfr + ": ");
    if (verbose) {
      assertThat(lines.get(1)).startsWith("java.lang.IllegalArgumentException");
      assertThat(lines.subList(2, lines.size())).isNotEmpty().anyMatch(l -> l.startsWith("\tat "));
    } else {
      assertThat(lines).hasSize(1);
    }
  }

  // --- #58: warnings a user can act on ---

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeNestedRecordingPrintsOneLineAndNoWarning(boolean json) throws Exception {
    var jfr = TestRecordings.nested(tempDir.resolve("nested.jfr"));
    var output = tempDir.resolve(json ? "nested.json" : "nested.html");
    var run = run(withJson(json, jfr.toString(), output.toString()));
    assertThat(run.code()).isZero();
    assertThat(run.out()).isEqualTo("Report written to " + output.toAbsolutePath() + "\n");
    assertThat(run.err()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "recordings/v0.1.0-nested-failed-cancelled.jfr",
        "recordings/v0.2.0-nested-and-failed.jfr"
      })
  void releasedRecordingsWithFailedAndCancelledTasksPrintNoWarning(String name) throws Exception {
    var jfr = TestRecordings.resource(name);
    for (boolean json : List.of(false, true)) {
      var output = tempDir.resolve(json ? "old.json" : "old.html");
      var run = run(withJson(json, jfr.toString(), output.toString()));
      assertThat(run.code()).isZero();
      assertThat(run.out()).isEqualTo("Report written to " + output.toAbsolutePath() + "\n");
      assertThat(run.err()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oneScopeLeftOpenWarnsInTheSingular(boolean json) throws Exception {
    var jfr = TestRecordings.leftOpen(tempDir.resolve("open.jfr"), "cli-open", 1, 1);
    var output = tempDir.resolve(json ? "open.json" : "open.html");
    var run = run(withJson(json, jfr.toString(), output.toString()));

    assertThat(run.code()).isZero();
    assertThat(run.out()).isEqualTo("Report written to " + output.toAbsolutePath() + "\n");
    assertThat(run.err())
        .isEqualTo(
            "analyzer: warning: "
                + jfr
                + " has 1 scope without a close event and 1 task without a completion event;"
                + " the report draws them as incomplete. Either they were still running when the"
                + " recording was written, or JFR dropped their events (see "
                + URL
                + ")\n");
    assertThat(run.err()).doesNotContain("cut short").doesNotContain("truncated");
  }

  @Test
  void scopesSharingANameCountByScopeIdInThePlural() throws Exception {
    var jfr = TestRecordings.leftOpen(tempDir.resolve("twins.jfr"), "cli-same-name", 2, 1);
    var run = run(jfr.toString(), tempDir.resolve("twins.html").toString());
    assertThat(run.code()).isZero();
    assertThat(run.err())
        .startsWith(
            "analyzer: warning: "
                + jfr
                + " has 2 scopes without a close event and 2 tasks without a completion event;")
        .hasLineCount(1);
  }

  /**
   * Three scopes, each opened by the task of the one before, all left open. The model cannot link
   * them as parent and child (nesting is matched on a task's completion, and none completed), so
   * they are three scopes of the flat list, each counted.
   */
  @Test
  void scopesNestedTwoLevelsDeepAreCounted() throws Exception {
    var jfr = TestRecordings.leftOpen(tempDir.resolve("deep.jfr"), "cli-deep", 1, 3);
    assertThat(JfrParser.parse(jfr).scopes())
        .filteredOn(s -> s.name().equals("cli-deep"))
        .hasSize(3);

    var run = run(jfr.toString(), tempDir.resolve("deep.html").toString());
    assertThat(run.err())
        .startsWith(
            "analyzer: warning: "
                + jfr
                + " has 3 scopes without a close event and 3 tasks without a completion event;")
        .hasLineCount(1);
  }

  @Test
  void onlyScopesIncompleteNamesOnlyScopes() throws Exception {
    var jfr = TestRecordings.scopeWithoutTasksLeftOpen(tempDir.resolve("scope-only.jfr"));
    var run = run(jfr.toString(), tempDir.resolve("scope-only.html").toString());
    assertThat(run.code()).isZero();
    assertThat(run.err())
        .startsWith(
            "analyzer: warning: "
                + jfr
                + " has 1 scope without a close event; the report draws them as incomplete.")
        .doesNotContain("task without")
        .hasLineCount(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void onlyTasksIncompleteNamesOnlyTasks(int tasks) throws Exception {
    var jfr = TestRecordings.tasksWithoutCompletion(tempDir.resolve("tasks-only.jfr"), tasks);
    var run = run(jfr.toString(), tempDir.resolve("tasks-only.html").toString());
    assertThat(run.code()).isZero();
    assertThat(run.err())
        .startsWith(
            "analyzer: warning: "
                + jfr
                + " has "
                + (tasks == 1 ? "1 task" : tasks + " tasks")
                + " without a completion event; the report draws them as incomplete.")
        .doesNotContain("scope without")
        .hasLineCount(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void recordingWithoutScopeTracerEventsWarnsAndStillWritesTheReport(boolean json)
      throws Exception {
    var jfr = TestRecordings.empty(tempDir.resolve("empty.jfr"));
    var output = tempDir.resolve(json ? "empty.json" : "empty.html");
    var run = run(withJson(json, jfr.toString(), output.toString()));

    assertThat(run.code()).isZero();
    assertThat(output).exists();
    assertThat(run.out()).isEqualTo("Report written to " + output.toAbsolutePath() + "\n");
    assertThat(run.err())
        .isEqualTo(
            "analyzer: warning: "
                + jfr
                + " contains no scope-tracer events: the program was not run with the"
                + " scope-tracer agent or TracedScope, or the recording did not enable"
                + " com.ionutbanu.scopetracer.* events\n");
  }

  private record Run(int code, String out, String err) {}

  private static Run run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        AnalyzerMain.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(
        code,
        out.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n"),
        err.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n"));
  }

  private static String[] withVerbose(boolean verbose, String... args) {
    var list = new ArrayList<>(List.of(args));
    if (verbose) list.add("--verbose");
    return list.toArray(String[]::new);
  }

  private static String[] withJson(boolean json, String... args) {
    var list = new ArrayList<>(List.of(args));
    if (json) list.add("--format=json");
    return list.toArray(String[]::new);
  }

  private static PrintStream nullStream() {
    return new PrintStream(java.io.OutputStream.nullOutputStream());
  }
}
