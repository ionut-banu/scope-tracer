package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code -executable} jar in a child JVM, stdout and stderr captured separately (#58). A child
 * JVM is needed because a logging backend is configured once per JVM: an in-process test cannot see
 * both the default and the {@code --verbose} configuration, nor the jar's own {@code logback.xml}.
 */
class AnalyzerCliIT {

  private static final Pattern TIMESTAMP = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");
  private static final Pattern THREAD = Pattern.compile("\\[[\\w-]+]");
  private static final String PARSER_LOGGER = JfrParser.class.getName();

  @TempDir static Path recordings;

  private static Path nested;
  private static Path manyRoots;
  private static Path open;
  private static Path empty;

  @TempDir Path dir;

  @BeforeAll
  static void makeRecordings() throws Exception {
    assertThat(executableJar()).as("the -executable jar; run with mvn verify").exists();
    nested = TestRecordings.nested(recordings.resolve("nested.jfr"));
    manyRoots = TestRecordings.complete(recordings.resolve("many.jfr"), "cli-many", 5_000);
    open = TestRecordings.leftOpen(recordings.resolve("open.jfr"), "cli-open", 1, 1);
    empty = recordings.resolve("empty.jfr");
    // the recording a first-time user makes: -XX:StartFlightRecording without the agent
    var made =
        new ProcessBuilder(
                java(),
                "-Xlog:jfr+startup=off",
                "-XX:StartFlightRecording=filename=" + empty,
                "-version")
            .redirectErrorStream(true)
            .redirectOutput(recordings.resolve("empty.log").toFile())
            .start();
    assertThat(made.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(empty).exists();
  }

  // --- default output ---

  @ParameterizedTest(name = "json={0}")
  @ValueSource(booleans = {false, true})
  void completeNestedRecordingPrintsOneLineAndNothingOnStderr(boolean json) throws Exception {
    var output = dir.resolve(json ? "nested.json" : "nested.html");
    var run = analyze(nested, output, json ? List.of("--format=json") : List.of());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err()).isEmpty();
  }

  @ParameterizedTest(name = "json={0}")
  @ValueSource(booleans = {false, true})
  void fiveThousandRootScopesPrintOneLineAndNothingOnStderr(boolean json) throws Exception {
    var output = dir.resolve(json ? "many.json" : "many.html");
    var run = analyze(manyRoots, output, json ? List.of("--format=json") : List.of());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err()).isEmpty();
    assertThat(JfrParser.parse(manyRoots).scopes()).hasSizeGreaterThanOrEqualTo(5_000);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "recordings/v0.1.0-nested-failed-cancelled.jfr",
        "recordings/v0.2.0-nested-and-failed.jfr"
      })
  void releasedRecordingsPrintOneLineAndNothingOnStderr(String name) throws Exception {
    var jfr = TestRecordings.resource(name);
    for (var options : List.of(List.<String>of(), List.of("--format=json"))) {
      var output = dir.resolve(options.isEmpty() ? "old.html" : "old.json");
      var run = analyze(jfr, output, options);
      assertThat(run.code()).as(run.err()).isZero();
      assertThat(run.out()).isEqualTo(reportLine(output));
      assertThat(run.err()).isEmpty();
    }
  }

  // --- warnings ---

  @ParameterizedTest(name = "json={0}")
  @ValueSource(booleans = {false, true})
  void scopeLeftOpenPrintsOneWarningLineAndNoLogLine(boolean json) throws Exception {
    var output = dir.resolve(json ? "open.json" : "open.html");
    var run = analyze(open, output, json ? List.of("--format=json") : List.of());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err().lines().toList())
        .containsExactly(AnalyzerMain.warnings(open, JfrParser.parse(open), -1).getFirst());
    assertThat(run.err())
        .startsWith("analyzer: warning: " + open + " has 1 scope without a close event and 1 task")
        .doesNotContain(PARSER_LOGGER)
        .doesNotContain("WARN");
    assertNoLogFormat(run);
  }

  @ParameterizedTest(name = "json={0}")
  @ValueSource(booleans = {false, true})
  void recordingWithoutScopeTracerEventsPrintsOneWarningLine(boolean json) throws Exception {
    var output = dir.resolve(json ? "empty.json" : "empty.html");
    var run = analyze(empty, output, json ? List.of("--format=json") : List.of());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(output).exists();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err().lines().toList())
        .singleElement()
        .asString()
        .startsWith("analyzer: warning: " + empty + " contains no scope-tracer events");
    assertNoLogFormat(run);
  }

  // --- --verbose ---

  @Test
  void verboseShowsTheParsersDebugSummaryOnStderrOnly() throws Exception {
    var output = dir.resolve("verbose.html");
    var run = analyze(nested, output, List.of("--verbose"));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    var debug =
        run.err().lines().filter(l -> l.startsWith("DEBUG " + PARSER_LOGGER + " - ")).toList();
    assertThat(debug).hasSize(1);
    assertThat(debug.getFirst())
        .contains("parsed " + nested + " in ")
        .containsPattern(" \\d+ ms")
        .contains(
            "ScopeOpened=3",
            "TaskForked=4",
            "TaskSucceeded=4",
            "TaskFailed=0",
            "TaskCancelled=0",
            "ScopeClosed=3")
        .contains("3 scope(s), 4 task(s)");
  }

  @Test
  void verboseOnAScopeLeftOpenShowsTheParsersWarnAndTheWarningLine() throws Exception {
    var output = dir.resolve("open-verbose.json");
    var run = analyze(open, output, List.of("--format=json", "--verbose"));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    var lines = run.err().lines().toList();
    assertThat(lines)
        .anySatisfy(
            l ->
                assertThat(l)
                    .startsWith("WARN " + PARSER_LOGGER + " - recording " + open)
                    .contains("still running when the recording was written")
                    .contains("JFR dropped their events")
                    .doesNotContain("cut short"))
        .anySatisfy(l -> assertThat(l).startsWith("DEBUG " + PARSER_LOGGER))
        .last()
        .asString()
        .startsWith("analyzer: warning: " + open);
  }

  @ParameterizedTest(name = "verbose={0}")
  @ValueSource(booleans = {false, true})
  void notAJfrRecordingPrintsTheStackTraceOnlyWithVerbose(boolean verbose) throws Exception {
    var bad = dir.resolve("bad.jfr");
    Files.writeString(bad, "this is not a JFR file");
    var run = analyze(bad, dir.resolve("bad.html"), verbose ? List.of("--verbose") : List.of());

    assertThat(run.code()).isEqualTo(3);
    assertThat(run.out()).isEmpty();
    var lines = run.err().lines().toList();
    assertThat(lines.getFirst()).isEqualTo("analyzer: I/O error: Not a complete Chunk header");
    if (verbose) {
      assertThat(lines).hasSizeGreaterThan(2).anyMatch(l -> l.startsWith("\tat "));
    } else {
      assertThat(lines).hasSize(1);
    }
  }

  /**
   * Neither the CLI's own level property nor Logback's ({@code logback.configurationFile} pointing
   * at a DEBUG configuration on stdout, {@code logback.debug}, {@code
   * logback.statusListenerClass}), each given as {@code -D} or through {@code JAVA_TOOL_OPTIONS},
   * turns diagnostics on or puts anything on stdout. The JVM's own {@code Picked up
   * JAVA_TOOL_OPTIONS} line is the only extra.
   */
  @ParameterizedTest(name = "{0} via {1}")
  @CsvSource({
    "level, -D",
    "level, JAVA_TOOL_OPTIONS",
    "configurationFile, -D",
    "configurationFile, JAVA_TOOL_OPTIONS",
    "debug, -D",
    "debug, JAVA_TOOL_OPTIONS",
    "statusListenerClass, -D",
    "statusListenerClass, JAVA_TOOL_OPTIONS"
  })
  void noSystemPropertyTurnsDiagnosticsOn(String property, String route) throws Exception {
    var output = dir.resolve("property.html");
    String definition = diagnosticProperty(property);
    var run =
        route.equals("-D")
            ? run(List.of(definition), Map.of(), args(output))
            : run(List.of(), Map.of("JAVA_TOOL_OPTIONS", definition), args(output));
    String err = withoutToolOptionsNotice(run.err());

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(err.lines().toList())
        .singleElement()
        .asString()
        .startsWith("analyzer: warning: " + open + " has 1 scope");
    assertNoLogFormat(new Result(run.code(), run.out(), err));
    assertThat(run.out() + err).doesNotContain("EVIL").doesNotContain("|-INFO");
    if (route.equals("JAVA_TOOL_OPTIONS")) {
      assertThat(run.err()).startsWith("Picked up JAVA_TOOL_OPTIONS: " + definition);
    }
  }

  /** With {@code --verbose} and the same properties, the jar's own configuration still applies. */
  @Test
  void verboseKeepsItsOwnConfigurationDespiteLogbackProperties() throws Exception {
    var output = dir.resolve("property-verbose.html");
    var options = new ArrayList<>(args(output));
    options.add("--verbose");
    var properties = new ArrayList<String>();
    for (var property : List.of("level", "configurationFile", "debug", "statusListenerClass")) {
      properties.add(diagnosticProperty(property));
    }
    var run = run(properties, Map.of(), options);

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err()).doesNotContain("EVIL").doesNotContainPattern(TIMESTAMP);
    assertThat(run.err().lines())
        .anyMatch(l -> l.startsWith("WARN " + PARSER_LOGGER + " - "))
        .anyMatch(l -> l.startsWith("DEBUG " + PARSER_LOGGER + " - "))
        .last()
        .asString()
        .startsWith("analyzer: warning:");
  }

  @Test
  void recordingWithOnlyOrphanEventsSaysWhatItHolds() throws Exception {
    var orphans = TestRecordings.orphans(dir.resolve("orphans.jfr"), 0, true, 2);
    var output = dir.resolve("orphans.json");
    var run = analyze(orphans, output, List.of("--format=json"));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.out()).isEqualTo(reportLine(output));
    assertThat(run.err().lines().toList())
        .singleElement()
        .asString()
        .startsWith(
            "analyzer: warning: "
                + orphans
                + " holds 3 scope-tracer events but no ScopeOpened event, so the report shows no"
                + " scopes.");
  }

  /**
   * One property a user could try, as {@code -Dname=value}: the CLI's own level, or one of
   * Logback's, with a configuration that logs everything to stdout with a timestamp and {@code
   * EVIL}.
   */
  private String diagnosticProperty(String which) throws Exception {
    return switch (which) {
      case "level" -> "-D" + AnalyzerMain.LOG_LEVEL_PROPERTY + "=DEBUG";
      case "configurationFile" -> {
        var evil = dir.resolve("evil.xml");
        Files.writeString(
            evil,
            """
            <configuration>
              <appender name="OUT" class="ch.qos.logback.core.ConsoleAppender">
                <target>System.out</target>
                <encoder><pattern>EVIL %d [%thread] %level %logger - %msg%n</pattern></encoder>
              </appender>
              <root level="DEBUG"><appender-ref ref="OUT"/></root>
            </configuration>
            """);
        yield "-Dlogback.configurationFile=" + evil;
      }
      case "debug" -> "-Dlogback.debug=true";
      case "statusListenerClass" ->
          "-Dlogback.statusListenerClass=ch.qos.logback.core.status.OnConsoleStatusListener";
      default -> throw new IllegalArgumentException(which);
    };
  }

  /** The JVM's own {@code Picked up JAVA_TOOL_OPTIONS: ...} line, which no program can prevent. */
  private static String withoutToolOptionsNotice(String err) {
    return err.lines()
        .filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
        .map(l -> l + "\n")
        .reduce("", String::concat);
  }

  private static List<String> args(Path output) {
    return List.of(open.toString(), output.toString());
  }

  @Test
  void usageNamesVerbose() throws Exception {
    var run = run(List.of(), List.of("--help"));
    assertThat(run.code()).isZero();
    assertThat(run.out())
        .isEqualTo("Usage: analyzer <input.jfr> <output> [--format=json] [--verbose]\n");
    assertThat(run.err()).isEmpty();

    var wrong = run(List.of(), List.of("--verbose", "--verbose", open.toString(), "x.html"));
    assertThat(wrong.code()).isEqualTo(1);
    assertThat(wrong.out()).isEmpty();
    assertThat(wrong.err())
        .isEqualTo("Usage: analyzer <input.jfr> <output> [--format=json] [--verbose]\n");
  }

  // --- helpers ---

  /** Without {@code --verbose} nothing on either stream looks like a log line. */
  private static void assertNoLogFormat(Result run) {
    for (var text : List.of(run.out(), run.err())) {
      assertThat(text).doesNotContainPattern(TIMESTAMP).doesNotContainPattern(THREAD);
      assertThat(text).doesNotContain(PARSER_LOGGER).doesNotContain("SLF4J");
    }
  }

  private static String reportLine(Path output) {
    return "Report written to " + output.toAbsolutePath() + "\n";
  }

  private Result analyze(Path jfr, Path output, List<String> options) throws Exception {
    return analyze(List.of(), jfr, output, options);
  }

  private Result analyze(List<String> jvmArgs, Path jfr, Path output, List<String> options)
      throws Exception {
    var args = new ArrayList<String>();
    args.add(jfr.toString());
    args.add(output.toString());
    args.addAll(options);
    return run(jvmArgs, args);
  }

  private Result run(List<String> jvmArgs, List<String> args) throws Exception {
    return run(jvmArgs, Map.of(), args);
  }

  private Result run(List<String> jvmArgs, Map<String, String> env, List<String> args)
      throws Exception {
    var command = new ArrayList<String>();
    command.add(java());
    command.add("--enable-preview");
    command.addAll(jvmArgs);
    command.add("-jar");
    command.add(executableJar().toString());
    command.addAll(args);
    var out = dir.resolve("stdout-" + System.nanoTime() + ".txt");
    var err = dir.resolve("stderr-" + System.nanoTime() + ".txt");
    var builder = new ProcessBuilder(command);
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().putAll(env);
    var process =
        builder
            .directory(dir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("analyzer did not exit: " + command);
    }
    return new Result(
        process.exitValue(),
        Files.readString(out, StandardCharsets.UTF_8),
        Files.readString(err, StandardCharsets.UTF_8));
  }

  static Path executableJar() {
    return Path.of(System.getProperty("analyzerExecutableJar", "missing-executable.jar"));
  }

  static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  static String classPath(Path... entries) {
    var parts = new ArrayList<String>();
    for (var entry : entries) parts.add(entry.toString());
    return String.join(File.pathSeparator, parts);
  }

  record Result(int code, String out, String err) {}
}
