package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The agent's own recording: {@code -javaagent:...=record} alone leaves a {@code .jfr} and its HTML
 * report when the JVM exits (issue #56, criteria 6-21 for this route). Each test runs {@link
 * ExitSubject} in a child JVM with the agent fat-jar and reads what it left on disk.
 */
class AgentRecordIT {

  private static final String RECORDING_TO = ChildJvm.AGENT_PREFIX + " recording to ";

  private static String agent;

  @BeforeAll
  static void requireFatJar() {
    agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
  }

  private static String javaagent(String args) {
    return "-javaagent:" + agent + "=" + args;
  }

  // --- criterion 6 ---

  @Test
  void recordAloneWritesJfrAndReportInTheWorkingDirectoryNamedForThePid(@TempDir Path dir)
      throws Exception {
    long pid;
    ChildJvm.Result run;
    try (var child = ChildJvm.start(dir, List.of(javaagent("record")), "scopes", "record-scope")) {
      pid = child.pid();
      run = child.finish();
    }

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    List<Path> jfrs = AgentReportAtExitIT.filesEndingWith(dir, ".jfr");
    assertThat(jfrs).hasSize(1);
    String name = jfrs.get(0).getFileName().toString();
    assertThat(name)
        .matches("scope-tracer-" + pid + "-\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}\\.jfr");
    // the child's user.dir is its working directory as getcwd() reports it, symbolic links resolved
    Path jfr = dir.toRealPath().resolve(name);
    Path html = dir.toRealPath().resolve(name.replace(".jfr", ".html"));
    assertThat(Files.readString(html)).contains("record-scope");
    assertThat(run.agentLines()).containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + html);
  }

  // --- criterion 7 ---

  @Test
  void recordToAFileInMissingDirectoriesCreatesThem(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("sub").resolve("deeper").resolve("trace.jfr");
    var run =
        ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "scopes", "explicit-path-scope");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(JfrParser.parse(jfr).scopes())
        .extracting(ScopeRecord::name)
        .contains("explicit-path-scope");
    assertThat(jfr.resolveSibling("trace.html")).exists();
  }

  @Test
  void recordToAnExistingFileReplacesIt(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("trace.jfr");
    Files.writeString(jfr, "not a recording");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "scopes", "replaced");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(JfrParser.parse(jfr).scopes())
        .extracting(ScopeRecord::name)
        .containsExactly("replaced");
  }

  // --- criterion 8 ---

  @Test
  void agentRecordingHoldsOnlyScopeTracerEvents(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("only.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "tree");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    var types = new ArrayList<String>();
    try (var file = new RecordingFile(jfr)) {
      while (file.hasMoreEvents()) types.add(file.readEvent().getEventType().getName());
    }
    assertThat(types).isNotEmpty().allMatch(t -> t.startsWith("com.ionutbanu.scopetracer."));
  }

  // --- criterion 9 ---

  /**
   * One program, recorded once by {@code record} and once by {@code -XX:StartFlightRecording}: the
   * analyzer's model of the two has the same scopes, parent links, task names and outcomes. Which
   * sibling ends {@code Cancelled} is whatever the {@code -XX} recording shows.
   */
  @Test
  void recordGivesTheSameTraceAsStartFlightRecording(@TempDir Path dir) throws Exception {
    Path own = dir.resolve("own.jfr");
    Path xx = dir.resolve("xx.jfr");
    var ownRun = ChildJvm.run(dir, List.of(javaagent("record=" + own + ",html=false")), "tree");
    var xxRun =
        ChildJvm.run(
            dir,
            List.of(
                "-javaagent:" + agent + "=html=false",
                "-XX:StartFlightRecording=filename=" + xx + ",dumponexit=true"),
            "tree");
    assertThat(ownRun.exitCode()).as(ownRun.stderr()).isZero();
    assertThat(xxRun.exitCode()).as(xxRun.stderr()).isZero();

    List<String> fromXx = shape(JfrParser.parse(xx));
    assertThat(shape(JfrParser.parse(own))).isEqualTo(fromXx);
    // and the shape is the one the subject was written to produce
    assertThat(fromXx)
        .anyMatch(
            s ->
                s.startsWith(ExitSubject.TREE_FAIL + " <- root")
                    && s.contains("Failed(java.lang.IllegalStateException)")
                    && s.contains("Cancelled"))
        .anyMatch(
            s -> s.startsWith(ExitSubject.TREE_LEVEL_1 + " <- " + ExitSubject.TREE_NEST + "/"))
        .anyMatch(
            s -> s.startsWith(ExitSubject.TREE_LEVEL_2 + " <- " + ExitSubject.TREE_LEVEL_1 + "/"));
    assertThat(fromXx.stream().filter(s -> s.startsWith(ExitSubject.TREE_TWIN + " <- root")))
        .hasSize(2);
  }

  // --- criterion 10 ---

  @Test
  void scopeOpenedByTheFirstStatementOfMainIsRecorded(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("first.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "first");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(ScopeEvents.read(jfr).opened(ExitSubject.FIRST_SCOPE)).hasSize(1);
  }

  // --- criterion 11 ---

  @Test
  void includeNameFilterAppliesToTheAgentRecording(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("filtered.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent("record=" + jfr + ",include.name=keep-*")),
            "scopes",
            "keep-1,keep-2,drop-1");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    var events = ScopeEvents.read(jfr);
    assertThat(events.opened("keep-1")).hasSize(1);
    assertThat(events.opened("keep-2")).hasSize(1);
    for (String keep : List.of("keep-1", "keep-2")) {
      long id = events.opened(keep).get(0).scopeId();
      assertThat(events.ofScope(ScopeEvents.TASK_FORKED, id)).hasSize(1);
      assertThat(events.ofScope(ScopeEvents.SCOPE_CLOSED, id)).hasSize(1);
      assertThat(
              events.events().stream()
                  .filter(e -> e.scopeId() == id && e.type().equals("TaskSucceeded")))
          .hasSize(1);
    }
    assertThat(events.events()).noneMatch(e -> "drop-1".equals(e.scopeName()));
  }

  @Test
  void sampleRateZeroRecordsNoScopeAndWritesNoReport(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("sampled.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent("record=" + jfr + ",sample.rate=0.0")), "scopes", "a,b");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(ScopeEvents.read(jfr).events()).isEmpty();
    assertThat(dir.resolve("sampled.html")).doesNotExist();
    assertThat(run.writtenReports()).isEmpty();
  }

  // --- criterion 12 ---

  @Test
  void recordWithHtmlFalseWritesOnlyTheJfr(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("quiet.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr + ",html=false")), "scopes", "q");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(ScopeEvents.read(jfr).opened("q")).hasSize(1);
    assertThat(dir.resolve("quiet.html")).doesNotExist();
    assertThat(run.writtenReports()).isEmpty();
  }

  @Test
  void recordWithOutputDirPutsOnlyTheReportThere(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("rec").resolve("r.jfr");
    Path reports = dir.resolve("reports");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent("record=" + jfr + ",output.dir=" + reports)), "scopes", "o");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(jfr).exists();
    assertThat(reports.resolve("r.html")).exists();
    assertThat(jfr.resolveSibling("r.html")).doesNotExist();
  }

  // --- criterion 13 ---

  @Test
  void recordTogetherWithStartFlightRecordingGivesTwoRecordingsAndTwoReports(@TempDir Path dir)
      throws Exception {
    Path own = dir.resolve("own.jfr");
    Path user = dir.resolve("u.jfr");
    var with =
        ChildJvm.run(
            dir,
            List.of(
                javaagent("record=" + own),
                "-XX:StartFlightRecording=filename=" + user + ",dumponexit=true"),
            "scopes",
            "both-scope");
    var without =
        ChildJvm.run(
            dir,
            List.of(
                "-XX:StartFlightRecording=filename="
                    + dir.resolve("plain.jfr")
                    + ",dumponexit=true"),
            "scopes",
            "both-scope");

    with.assertSameProgramAs(without);
    Path ownHtml = dir.resolve("own.html").toAbsolutePath();
    Path userHtml = dir.resolve("u.html").toAbsolutePath();
    assertThat(with.writtenReports())
        .containsExactlyInAnyOrder(ownHtml.toString(), userHtml.toString());
    assertThat(Files.readString(ownHtml)).contains("both-scope");
    assertThat(Files.readString(userHtml)).contains("both-scope");
    assertThat(ScopeEvents.read(own).opened("both-scope")).hasSize(1);
    assertThat(ScopeEvents.read(user).opened("both-scope")).hasSize(1);
  }

  // --- criterion 14 ---

  @Test
  void recordingIsNamedScopeTracerAndJcmdStopWritesItThenAndNothingAtExit(@TempDir Path dir)
      throws Exception {
    Path jfr = dir.resolve("jcmd.jfr");
    try (var child = ChildJvm.start(dir, List.of(javaagent("record=" + jfr)), "stdin")) {
      child.awaitStdout(ExitSubject.READY);
      assertThat(Jcmd.run(child.pid(), "JFR.check")).contains("name=" + AgentRecording.NAME);
      Jcmd.run(child.pid(), "JFR.stop", "name=" + AgentRecording.NAME);
      child.awaitStderrStartingWith(ChildJvm.WRITTEN);
      assertThat(ScopeEvents.read(jfr).opened(ExitSubject.EXIT_SCOPE)).hasSize(1);
      child.writeStdin("go");
      var run = child.finish();

      assertThat(run.exitCode()).as(run.stderr()).isZero();
      assertThat(run.writtenReports())
          .containsExactly(dir.resolve("jcmd.html").toAbsolutePath().toString());
    }
  }

  // --- criteria 15, 16 and 17 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"return", "exit", "throw", "hang"})
  void everyOrderlyExitIsUnchangedAndGetsTheReport(String mode, @TempDir Path dir)
      throws Exception {
    Path jfr = dir.resolve("exit.jfr");
    var with = runMode(dir, List.of(javaagent("record=" + jfr)), mode);
    var without = runMode(dir, List.of(), mode);

    with.assertSameProgramAs(without);
    Path html = dir.resolve("exit.html").toAbsolutePath();
    assertThat(Files.readString(html)).contains(ExitSubject.EXIT_SCOPE);
    assertThat(with.writtenReports()).containsExactly(html.toString());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"exit", "hang"})
  void scopeStillOpenAtExitIsReportedWithoutItsClose(String mode, @TempDir Path dir)
      throws Exception {
    Path jfr = dir.resolve("open.jfr");
    var with = runMode(dir, List.of(javaagent("record=" + jfr)), mode);

    assertThat(with.warnings()).isEmpty();
    assertThat(Files.readString(dir.resolve("open.html"))).contains(ExitSubject.OPEN_AT_EXIT);
    var open =
        JfrParser.parse(jfr).scopes().stream()
            .filter(s -> s.name().equals(ExitSubject.OPEN_AT_EXIT))
            .toList();
    assertThat(open).hasSize(1);
    assertThat(open.get(0).closeTime()).isNull();
  }

  @Test
  void applicationShutdownHookStillRuns(@TempDir Path dir) throws Exception {
    Path markerWith = dir.resolve("marker-with");
    Path markerWithout = dir.resolve("marker-without");
    var with =
        ChildJvm.run(
            dir,
            List.of(
                javaagent("record=" + dir.resolve("hook.jfr")),
                "-D" + ExitSubject.MARKER_PROPERTY + "=" + markerWith),
            "return");
    var without =
        ChildJvm.run(
            dir, List.of("-D" + ExitSubject.MARKER_PROPERTY + "=" + markerWithout), "return");

    with.assertSameProgramAs(without);
    assertThat(markerWith).hasContent("hook ran");
    assertThat(markerWithout).hasContent("hook ran");
    assertThat(dir.resolve("hook.html")).exists();
  }

  // --- criterion 18 ---

  @Test
  void sigkillLeavesOnlyJfrsEmptyFileAndTheNextRunReplacesIt(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("killed.jfr");
    List<String> jvmArgs = List.of(javaagent("record=" + jfr));
    try (var child = ChildJvm.start(dir, jvmArgs, "hang")) {
      child.awaitStdout(ExitSubject.SCOPE_RUNNING);
      child.kill();
      child.finish();
    }
    AgentReportAtExitIT.assertNothingButJfrsEmptyFile(dir, jfr);

    var next = ChildJvm.run(dir, jvmArgs, "return");
    assertThat(next.exitCode()).as(next.stderr()).isZero();
    assertThat(ScopeEvents.read(jfr).opened(ExitSubject.EXIT_SCOPE)).hasSize(1);
    assertThat(dir.resolve("killed.html")).exists();
  }

  @Test
  void haltLeavesOnlyJfrsEmptyFile(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("halted.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "halt");

    assertThat(run.exitCode()).isZero();
    assertThat(run.writtenReports()).isEmpty();
    AgentReportAtExitIT.assertNothingButJfrsEmptyFile(dir, jfr);
  }

  // --- criterion 19 ---

  /**
   * A {@code record} location that cannot be written: one warning naming it, no recording of the
   * agent's own, and the instrumentation still in place, which a {@code -XX:StartFlightRecording}
   * recording in the same JVM shows.
   */
  @Test
  void unwritableRecordLocationWarnsOnceAndStillInstruments(@TempDir Path dir) throws Exception {
    Path readOnly = Files.createDirectory(dir.resolve("read-only"));
    Path user = dir.resolve("u.jfr");
    makeReadOnly(readOnly);
    try {
      var with =
          ChildJvm.run(
              dir,
              List.of(
                  javaagent("record=" + readOnly + ",html=false"),
                  "-XX:StartFlightRecording=filename=" + user + ",dumponexit=true"),
              "scopes",
              "still-traced");
      var without =
          ChildJvm.run(
              dir,
              List.of(
                  "-XX:StartFlightRecording=filename="
                      + dir.resolve("plain.jfr")
                      + ",dumponexit=true"),
              "scopes",
              "still-traced");

      with.assertSameProgramAs(without);
      assertThat(with.warnings()).hasSize(1);
      assertThat(with.warnings().get(0)).contains(readOnly.toAbsolutePath().toString());
      assertThat(with.agentLines()).noneMatch(l -> l.startsWith(RECORDING_TO));
      try (var files = Files.list(readOnly)) {
        assertThat(files).isEmpty();
      }
      assertThat(ScopeEvents.read(user).opened("still-traced")).hasSize(1);
    } finally {
      makeWritable(readOnly);
    }
  }

  // --- criterion 20 ---

  @Test
  void unwritableOutputDirWarnsOnceAndKeepsTheJfr(@TempDir Path dir) throws Exception {
    Path readOnly = Files.createDirectory(dir.resolve("read-only"));
    Path jfr = dir.resolve("r.jfr");
    makeReadOnly(readOnly);
    try {
      var with =
          ChildJvm.run(
              dir, List.of(javaagent("record=" + jfr + ",output.dir=" + readOnly)), "scopes", "w");
      var without = ChildJvm.run(dir, List.of(), "scopes", "w");

      with.assertSameProgramAs(without);
      assertThat(with.warnings()).hasSize(1);
      assertThat(with.warnings().get(0))
          .contains(readOnly.resolve("r.html").toAbsolutePath().toString());
      assertThat(with.writtenReports()).isEmpty();
      assertThat(ScopeEvents.read(jfr).opened("w")).hasSize(1);
    } finally {
      makeWritable(readOnly);
    }
  }

  @Test
  void reportPathTakenByADirectoryWarnsOnceAndKeepsTheJfr(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("d.jfr");
    Path inTheWay = Files.createDirectory(dir.resolve("d.html"));
    var with = ChildJvm.run(dir, List.of(javaagent("record=" + jfr)), "scopes", "d");
    var without = ChildJvm.run(dir, List.of(), "scopes", "d");

    with.assertSameProgramAs(without);
    assertThat(with.warnings()).hasSize(1);
    assertThat(with.warnings().get(0)).contains(inTheWay.toAbsolutePath().toString());
    assertThat(inTheWay).isEmptyDirectory();
    assertThat(ScopeEvents.read(jfr).opened("d")).hasSize(1);
  }

  // --- criterion 21 (startup cases as an IT) ---

  @Test
  void emptyRecordValueRecordsUnderTheDefaultNameInTheWorkingDirectory(@TempDir Path dir)
      throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent("record=")), "scopes", "empty-value");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    List<Path> jfrs = AgentReportAtExitIT.filesEndingWith(dir, ".jfr");
    assertThat(jfrs).hasSize(1);
    assertThat(jfrs.get(0).getFileName().toString()).startsWith("scope-tracer-");
    assertThat(ScopeEvents.read(jfrs.get(0)).opened("empty-value")).hasSize(1);
  }

  @Test
  void recordToAnExistingDirectoryUsesTheDefaultNameInIt(@TempDir Path dir) throws Exception {
    Path target = Files.createDirectory(dir.resolve("traces"));
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + target)), "scopes", "in-dir");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    List<Path> jfrs = AgentReportAtExitIT.filesEndingWith(target, ".jfr");
    assertThat(jfrs).hasSize(1);
    assertThat(jfrs.get(0).getFileName().toString()).startsWith("scope-tracer-");
    assertThat(AgentReportAtExitIT.filesEndingWith(dir, ".jfr")).isEmpty();
    assertThat(AgentReportAtExitIT.filesEndingWith(target, ".html")).hasSize(1);
  }

  // --- helpers ---

  private static ChildJvm.Result runMode(Path dir, List<String> jvmArgs, String mode)
      throws Exception {
    try (var child = ChildJvm.start(dir, jvmArgs, mode)) {
      if (mode.equals("hang")) {
        child.awaitStdout(ExitSubject.SCOPE_RUNNING);
        child.terminate();
      }
      return child.finish();
    }
  }

  private static void makeReadOnly(Path dir) throws Exception {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
  }

  private static void makeWritable(Path dir) throws Exception {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  /**
   * The trace without times and ids: per scope its name, its parent as {@code scope/task} (or
   * {@code root}), and its tasks' names and outcomes, sorted so the order of the file does not
   * matter.
   */
  static List<String> shape(TraceModel model) {
    Map<Long, ScopeRecord> byId =
        model.scopes().stream()
            .collect(Collectors.toMap(ScopeRecord::scopeId, Function.identity()));
    var shapes = new ArrayList<String>();
    for (ScopeRecord s : model.scopes()) {
      String parent = "root";
      if (s.parent() != null) {
        ScopeRecord p = byId.get(s.parent().parentScopeId());
        String task =
            p == null
                ? "?"
                : p.tasks().stream()
                    .filter(t -> t.taskId() == s.parent().taskId())
                    .map(TaskRecord::taskName)
                    .findFirst()
                    .orElse("?");
        parent = s.parent().scopeName() + "/" + task;
      }
      String tasks =
          s.tasks().stream()
              .map(t -> t.taskName() + "=" + outcome(t.outcome()))
              .sorted()
              .collect(Collectors.joining(", "));
      shapes.add(s.name() + " <- " + parent + " [" + tasks + "]");
    }
    shapes.sort(Comparator.naturalOrder());
    return shapes;
  }

  private static String outcome(TaskOutcome outcome) {
    return switch (outcome) {
      case null -> "incomplete";
      case TaskOutcome.Success s -> "Success";
      case TaskOutcome.Failed f -> "Failed(" + f.exceptionType() + ")";
      case TaskOutcome.Cancelled c -> "Cancelled";
    };
  }
}
