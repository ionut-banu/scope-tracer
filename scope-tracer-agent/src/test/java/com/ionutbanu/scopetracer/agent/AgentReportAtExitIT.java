package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A recording the user started with {@code -XX:StartFlightRecording=...,dumponexit=true} gets its
 * HTML report when the JVM exits, as one stopped with {@code jcmd JFR.stop} does (issue #56,
 * criteria 1-5 and 15-18 for this route). Each test runs {@link ExitSubject} in a child JVM with
 * the agent fat-jar and reads what it left on disk.
 */
class AgentReportAtExitIT {

  private static String agent;

  @BeforeAll
  static void requireFatJar() {
    agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
  }

  private static String startFlightRecording(Path jfr) {
    return "-XX:StartFlightRecording=filename=" + jfr + ",dumponexit=true";
  }

  private static String javaagent(String args) {
    return "-javaagent:" + agent + (args.isEmpty() ? "" : "=" + args);
  }

  // --- criterion 1 ---

  @Test
  void dumponexitRecordingGetsItsReportAtExit(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(""), startFlightRecording(jfr)), "scopes", "at-exit-scope");

    Path html = dir.resolve("a.html").toAbsolutePath();
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(html).exists();
    assertThat(Files.readString(html)).contains("at-exit-scope");
    assertThat(run.agentLines()).containsExactly(ChildJvm.WRITTEN + html);
  }

  // --- criterion 2 ---

  @Test
  void recordingWithoutFilenameGetsReportBesideTheFileJfrNamed(@TempDir Path dir) throws Exception {
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(""), "-XX:StartFlightRecording=dumponexit=true"),
            "scopes",
            "unnamed-file-scope");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    List<Path> jfrs = filesEndingWith(dir, ".jfr");
    assertThat(jfrs).hasSize(1);
    String base = jfrs.get(0).getFileName().toString();
    assertThat(base).startsWith("hotspot-pid-");
    Path html = dir.resolve(base.substring(0, base.length() - 4) + ".html");
    assertThat(html).exists();
    assertThat(Files.readString(html)).contains("unnamed-file-scope");
    assertThat(run.writtenReports()).hasSize(1);
    assertThat(Path.of(run.writtenReports().get(0))).isAbsolute();
    assertThat(Files.isSameFile(Path.of(run.writtenReports().get(0)), html)).isTrue();
  }

  // --- criterion 3 ---

  @Test
  void recordingStoppedWithJcmdGetsOneReportThenAndNoneAtExit(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("stopped.jfr");
    try (var child =
        ChildJvm.start(
            dir,
            List.of(
                javaagent(""),
                "-XX:StartFlightRecording=name=user,filename=" + jfr + ",dumponexit=true"),
            "stdin")) {
      child.awaitStdout(ExitSubject.READY);
      Jcmd.run(child.pid(), "JFR.stop", "name=user");
      child.awaitStderrStartingWith(ChildJvm.WRITTEN);
      child.writeStdin("go");
      var run = child.finish();

      assertThat(run.exitCode()).as(run.stderr()).isZero();
      assertThat(run.writtenReports())
          .containsExactly(dir.resolve("stopped.html").toAbsolutePath().toString());
    }
  }

  // --- criterion 4 ---

  @Test
  void htmlFalseGivesNoReportAtExit(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent("html=false"), startFlightRecording(jfr)), "scopes", "s1");

    assertThat(run.exitCode()).isZero();
    assertThat(jfr).exists();
    assertThat(dir.resolve("a.html")).doesNotExist();
    assertThat(run.writtenReports()).isEmpty();
  }

  @Test
  void outputDirPutsTheAtExitReportThereCreatingIt(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    Path reports = dir.resolve("reports").resolve("nested");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent("output.dir=" + reports), startFlightRecording(jfr)),
            "scopes",
            "s1");

    assertThat(run.exitCode()).isZero();
    assertThat(reports.resolve("a.html")).exists();
    assertThat(dir.resolve("a.html")).doesNotExist();
    assertThat(run.writtenReports())
        .containsExactly(reports.resolve("a.html").toAbsolutePath().toString());
  }

  @Test
  void outputSuffixNamesTheAtExitReport(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent("output.suffix=.report.html"), startFlightRecording(jfr)),
            "scopes",
            "s1");

    assertThat(run.exitCode()).isZero();
    assertThat(dir.resolve("a.report.html")).exists();
    assertThat(dir.resolve("a.html")).doesNotExist();
  }

  @Test
  void minScopesAboveTheRecordedCountGivesNoAtExitReport(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent("min.scopes=5"), startFlightRecording(jfr)), "scopes", "s1");

    assertThat(run.exitCode()).isZero();
    assertThat(jfr).exists();
    assertThat(dir.resolve("a.html")).doesNotExist();
    assertThat(run.agentLines()).isEmpty();
  }

  // --- criterion 5 ---

  @Test
  void recordingWithoutScopeTracerEventsGivesNoReport(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("a.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(""), startFlightRecording(jfr)), "none");

    assertThat(run.exitCode()).isZero();
    assertThat(jfr).exists();
    assertThat(dir.resolve("a.html")).doesNotExist();
    assertThat(run.agentLines()).isEmpty();
  }

  // --- criteria 15 and 16 ---

  /**
   * However the JVM ends with its shutdown hooks run, the program is the same as without the agent
   * and the report is written. {@code hang} is ended with {@code Process.destroy()} (SIGTERM).
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"return", "exit", "throw", "hang"})
  void everyOrderlyExitIsUnchangedAndGetsTheReport(String mode, @TempDir Path dir)
      throws Exception {
    var with = runMode(dir, mode, true);
    var without = runMode(dir, mode, false);

    with.assertSameProgramAs(without);
    Path html = dir.resolve("with.html").toAbsolutePath();
    assertThat(html).exists();
    assertThat(Files.readString(html)).contains(ExitSubject.EXIT_SCOPE);
    assertThat(with.agentLines()).containsExactly(ChildJvm.WRITTEN + html);
  }

  /**
   * A scope still open on another thread at {@code System.exit} or SIGTERM is in the report as a
   * scope without its {@code ScopeClosed} (spec §2, "Event loss"), and writing the report logs no
   * warning.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"exit", "hang"})
  void scopeStillOpenAtExitIsReportedWithoutItsClose(String mode, @TempDir Path dir)
      throws Exception {
    var with = runMode(dir, mode, true);

    assertThat(with.warnings()).isEmpty();
    Path html = dir.resolve("with.html");
    assertThat(Files.readString(html)).contains(ExitSubject.OPEN_AT_EXIT);
    List<ScopeRecord> open =
        JfrParser.parse(dir.resolve("with.jfr")).scopes().stream()
            .filter(s -> s.name().equals(ExitSubject.OPEN_AT_EXIT))
            .toList();
    assertThat(open).hasSize(1);
    assertThat(open.get(0).closeTime()).isNull();
  }

  // --- criterion 17 ---

  @Test
  void applicationShutdownHookStillRunsInTheRunThatWritesTheReport(@TempDir Path dir)
      throws Exception {
    Path markerWith = dir.resolve("marker-with");
    Path markerWithout = dir.resolve("marker-without");
    var with =
        ChildJvm.run(
            dir,
            List.of(
                javaagent(""),
                startFlightRecording(dir.resolve("with.jfr")),
                "-D" + ExitSubject.MARKER_PROPERTY + "=" + markerWith),
            "return");
    var without =
        ChildJvm.run(
            dir,
            List.of(
                startFlightRecording(dir.resolve("without.jfr")),
                "-D" + ExitSubject.MARKER_PROPERTY + "=" + markerWithout),
            "return");

    with.assertSameProgramAs(without);
    assertThat(markerWithout).hasContent("hook ran");
    assertThat(markerWith).hasContent("hook ran");
    assertThat(dir.resolve("with.html")).exists();
  }

  // --- criterion 18 ---

  @Test
  void sigkillLeavesNoReportAndTheNextRunReplacesJfrsEmptyFile(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("killed.jfr");
    List<String> jvmArgs = List.of(javaagent(""), startFlightRecording(jfr));
    try (var child = ChildJvm.start(dir, jvmArgs, "hang")) {
      child.awaitStdout(ExitSubject.SCOPE_RUNNING);
      child.kill();
      child.finish();
    }
    assertNothingButJfrsEmptyFile(dir, jfr);

    var next = ChildJvm.run(dir, jvmArgs, "return");
    assertThat(next.exitCode()).isZero();
    assertThat(Files.size(jfr)).isPositive();
    assertThat(dir.resolve("killed.html")).exists();
  }

  @Test
  void haltLeavesNoReport(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("halted.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(""), startFlightRecording(jfr)), "halt");

    assertThat(run.exitCode()).isZero();
    assertThat(run.agentLines()).isEmpty();
    assertNothingButJfrsEmptyFile(dir, jfr);
  }

  // --- helpers ---

  /**
   * Runs {@code mode} with the agent (recording {@code with.jfr}) or without it ({@code
   * without.jfr}); {@code hang} is ended with SIGTERM once its scope is running.
   */
  private static ChildJvm.Result runMode(Path dir, String mode, boolean withAgent)
      throws Exception {
    var jvmArgs = new ArrayList<String>();
    if (withAgent) jvmArgs.add(javaagent(""));
    jvmArgs.add(startFlightRecording(dir.resolve(withAgent ? "with.jfr" : "without.jfr")));
    try (var child = ChildJvm.start(dir, jvmArgs, mode)) {
      if (mode.equals("hang")) {
        child.awaitStdout(ExitSubject.SCOPE_RUNNING);
        child.terminate();
      }
      return child.finish();
    }
  }

  /** Only JFR's own zero-byte file at {@code jfr}: no report, no temporary file of the agent's. */
  static void assertNothingButJfrsEmptyFile(Path dir, Path jfr) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactly(jfr.getFileName().toString());
    }
    assertThat(Files.size(jfr)).isZero();
  }

  static List<Path> filesEndingWith(Path dir, String suffix) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
    }
  }
}
