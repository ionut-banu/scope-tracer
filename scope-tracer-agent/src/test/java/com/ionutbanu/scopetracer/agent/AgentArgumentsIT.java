package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Warnings for agent arguments that look fine and do something else (issue #114). Each test runs
 * {@link ExitSubject}'s {@code mixed} scope in a child JVM with the agent fat-jar and reads what it
 * left on stderr and on disk.
 */
class AgentArgumentsIT {

  private static final String WARN = ChildJvm.AGENT_PREFIX + " WARN ";
  private static final String UNKNOWN = WARN + "ignoring unknown agent argument '";

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

  private static List<String> names(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  /** The subject's {@code mixed} scope: one open, two forks, two completions, one close. */
  private static void assertMixedScopeRecorded(Path jfr) throws Exception {
    var events = ScopeEvents.read(jfr);
    long id = events.opened(ExitSubject.MIXED_SCOPE).get(0).scopeId();
    assertThat(events.opened(ExitSubject.MIXED_SCOPE)).hasSize(1);
    assertThat(events.events().stream().filter(e -> e.scopeId() == id).map(e -> e.type()))
        .containsExactlyInAnyOrder(
            "ScopeOpened",
            "TaskForked",
            "TaskForked",
            "TaskSucceeded",
            "TaskFailed",
            "ScopeClosed");
  }

  // --- criteria 1, 2 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"recrod", "RECORD", "Record"})
  void anUnknownKeyWarnsOnceAndRecordsNothing(String key, @TempDir Path dir) throws Exception {
    var without = ChildJvm.run(dir, List.of(), "mixed");
    var run = ChildJvm.run(dir, List.of(javaagent(key)), "mixed");

    run.assertSameProgramAs(without);
    assertThat(run.agentLines()).hasSize(1);
    assertThat(run.agentLines().get(0))
        .startsWith(UNKNOWN + key + "'")
        .endsWith(
            "record, html, verbose, output.dir, output.suffix, min.scopes, include.name,"
                + " exclude.name, include.package, exclude.package, sample.rate");
    assertThat(names(dir)).isEmpty();
  }

  // --- criterion 3 ---

  @Test
  void anUnknownKeyDoesNotStopTheOthers(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("recrod,record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).hasSize(1);
    assertThat(run.warnings().get(0)).startsWith(UNKNOWN + "recrod'");
    assertMixedScopeRecorded(jfr);
    assertThat(dir.resolve("t.html")).exists();
  }

  @Test
  void twoWarningsAndTheRecordingWhenAnUnknownKeyAndABadHtmlValueMeet(@TempDir Path dir)
      throws Exception {
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(dir, List.of(javaagent("record=" + jfr + ",foo=bar,html=flase")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).hasSize(2);
    assertThat(run.warnings().get(0)).startsWith(UNKNOWN + "foo'");
    assertThat(run.warnings().get(1))
        .isEqualTo(WARN + "ignoring malformed html value: 'flase' (expected true or false)");
    assertMixedScopeRecorded(jfr);
    assertThat(dir.resolve("t.html")).exists();
  }

  // --- criterion 4 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"=x", "record,=x"})
  void aValueWithNoKeyWarnsOnce(String args, @TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent(args)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).containsExactly(WARN + "ignoring agent argument with no key: '=x'");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"record,,", "record,", ",", "="})
  void emptyTokensGiveNoWarning(String args, @TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent(args)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
  }

  // --- criterion 5 ---

  @Test
  void aTokenWithControlCharactersIsOneEscapedLine(@TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent("=a\tb\rc")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stderr().lines().filter(l -> l.contains("no key")).toList())
        .containsExactly(WARN + "ignoring agent argument with no key: '=a\\tb\\rc'");
    assertThat(run.stderr()).doesNotContain("\r");
  }

  // --- criterion 6 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"record=true", "record=TRUE"})
  void recordTrueIsBareRecord(String args, @TempDir Path dir) throws Exception {
    long pid;
    ChildJvm.Result run;
    try (var child = ChildJvm.start(dir, List.of(javaagent(args)), "mixed")) {
      pid = child.pid();
      run = child.finish();
    }

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
    List<Path> jfrs = AgentReportAtExitIT.filesEndingWith(dir, ".jfr");
    assertThat(jfrs).hasSize(1);
    String name = jfrs.get(0).getFileName().toString();
    assertThat(name)
        .matches("scope-tracer-" + pid + "-\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}\\.jfr");
    assertThat(dir.resolve(name.replace(".jfr", ".html"))).exists();
    assertThat(names(dir)).doesNotContain("true", "true.html");
    assertMixedScopeRecorded(dir.resolve(name));
  }

  // --- criterion 7 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"record=false", "record=False"})
  void recordFalseRecordsNothingOfItsOwnAndInstrumentationStillWorks(String args, @TempDir Path dir)
      throws Exception {
    Path u = dir.resolve("u.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(args), "-XX:StartFlightRecording=filename=" + u + ",dumponexit=true"),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
    assertMixedScopeRecorded(u);
    assertThat(dir.resolve("u.html")).exists();
    assertThat(names(dir)).containsExactly("u.html", "u.jfr");
  }

  @Test
  void aFileReallyNamedFalseIsReachableWithADotSlash(@TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent("record=./false")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
    assertMixedScopeRecorded(dir.resolve("false"));
  }

  // --- criterion 8 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"flase", "no", "0"})
  void aBadHtmlValueWarnsAndKeepsTheReport(String bad, @TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr + ",html=" + bad)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings())
        .containsExactly(
            WARN + "ignoring malformed html value: '" + bad + "' (expected true or false)");
    assertThat(dir.resolve("t.html")).exists();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"html=false", "html=FALSE"})
  void htmlFalseGivesNoWarningAndNoReport(String args, @TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr + "," + args)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
    assertThat(jfr).exists();
    assertThat(dir.resolve("t.html")).doesNotExist();
  }

  // --- criterion 9 ---

  private static final String BYTE_BUDDY = "[Byte Buddy]";

  @Test
  void verboseFalseTurnsVerboseOff(@TempDir Path dir) throws Exception {
    var off = ChildJvm.run(dir, List.of(javaagent("verbose=false")), "mixed");
    var bare = ChildJvm.run(dir, List.of(javaagent("verbose")), "mixed");
    var on = ChildJvm.run(dir, List.of(javaagent("verbose=true")), "mixed");

    assertThat(off.stderr()).doesNotContain(BYTE_BUDDY);
    assertThat(off.warnings()).isEmpty();
    assertThat(bare.stderr()).contains(BYTE_BUDDY);
    assertThat(on.stderr()).contains(BYTE_BUDDY);
  }

  @Test
  void aBadVerboseValueWarnsAndLeavesVerboseOff(@TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent("verbose=maybe")), "mixed");

    assertThat(run.warnings())
        .containsExactly(
            WARN + "ignoring malformed verbose value: 'maybe' (expected true or false)");
    assertThat(run.stderr()).doesNotContain(BYTE_BUDDY);
  }

  @Test
  void anExplicitVerboseFalseBeatsTheSystemProperty(@TempDir Path dir) throws Exception {
    var run =
        ChildJvm.run(
            dir, List.of("-Dscopetracer.agent.verbose=true", javaagent("verbose=false")), "mixed");

    assertThat(run.stderr()).doesNotContain(BYTE_BUDDY);
  }

  // --- criteria 10, 11 ---

  @Test
  void outOfRangeNumbersWarnOnceEach(@TempDir Path dir) throws Exception {
    assertThat(ChildJvm.run(dir, List.of(javaagent("min.scopes=0")), "mixed").warnings())
        .containsExactly(WARN + "min.scopes must be at least 1, using 1");
    assertThat(ChildJvm.run(dir, List.of(javaagent("min.scopes=-3")), "mixed").warnings())
        .containsExactly(WARN + "min.scopes must be at least 1, using 1");
    assertThat(ChildJvm.run(dir, List.of(javaagent("sample.rate=5")), "mixed").warnings())
        .containsExactly(WARN + "sample.rate must be between 0.0 and 1.0, using 1.0");
    assertThat(ChildJvm.run(dir, List.of(javaagent("sample.rate=-0.5")), "mixed").warnings())
        .containsExactly(WARN + "sample.rate must be between 0.0 and 1.0, using 0.0");
  }

  @Test
  void sampleRateNaNKeepsEveryScope(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent("record=" + jfr + ",sample.rate=NaN")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).containsExactly(WARN + "ignoring malformed sample.rate value: NaN");
    assertMixedScopeRecorded(jfr);
  }

  @Test
  void aKeyWithNoValueWarnsAndRecordWithNoneDoesNot(@TempDir Path dir) throws Exception {
    assertThat(ChildJvm.run(dir, List.of(javaagent("output.dir=")), "mixed").warnings())
        .containsExactly(WARN + "ignoring output.dir with no value");
    assertThat(ChildJvm.run(dir, List.of(javaagent("min.scopes")), "mixed").warnings())
        .containsExactly(WARN + "ignoring min.scopes with no value");
    assertThat(ChildJvm.run(dir, List.of(javaagent("include.name=")), "mixed").warnings())
        .containsExactly(WARN + "ignoring include.name with no value");
    Path jfr = dir.resolve("t.jfr");
    assertThat(
            ChildJvm.run(dir, List.of(javaagent("record=" + jfr + ",html=false")), "mixed")
                .warnings())
        .isEmpty();
  }

  // --- criterion 14 ---

  @Test
  void aKeyGivenTwiceKeepsTheLastValue(@TempDir Path dir) throws Exception {
    Path a = dir.resolve("a.jfr");
    Path b = dir.resolve("b.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent("record=" + a + ",record=" + b + ",html=false")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
    assertThat(names(dir)).containsExactly("b.jfr");
  }

  // --- criterion 15 ---

  static Stream<String> everyArgumentOfCriteria1To11() {
    return Stream.of(
        "recrod",
        "RECORD",
        "Record",
        "recrod,record=%1$s/t.jfr",
        "record=%1$s/t.jfr,foo=bar,html=flase",
        "=x",
        "record,=x",
        "record,,",
        ",",
        "=",
        "=a\tb\rc",
        "record=true",
        "record=TRUE",
        "record=false,html=false",
        "record=False",
        "record=%1$s/false.jfr,html=flase",
        "html=no",
        "html=0",
        "verbose=false",
        "verbose=maybe",
        "min.scopes=0",
        "min.scopes=-3",
        "sample.rate=5",
        "sample.rate=-0.5",
        "sample.rate=NaN",
        "output.dir=",
        "output.suffix=",
        "min.scopes=",
        "sample.rate=",
        "include.name=",
        "exclude.name=",
        "include.package=",
        "exclude.package=",
        "output.dir",
        "min.scopes",
        "include.name",
        "record=",
        "record");
  }

  @ParameterizedTest(name = "[{index}]")
  @MethodSource("everyArgumentOfCriteria1To11")
  void everyArgumentStartsTheJvmAndLeavesTheProgramUnchanged(String template, @TempDir Path dir)
      throws Exception {
    Path work = Files.createDirectory(dir.resolve("work"));
    Path out = Files.createDirectory(dir.resolve("out"));
    var without = ChildJvm.run(work, List.of(), "mixed");
    var run = ChildJvm.run(work, List.of(javaagent(template.formatted(out))), "mixed");

    run.assertSameProgramAs(without);
    assertThat(run.warnings()).allMatch(w -> w.lines().count() == 1);
  }

  // --- criterion 15, renamed jar ---

  @Test
  void everyWarningIsPrintedOnceUnderARenamedJar(@TempDir Path dir) throws Exception {
    Path renamed = dir.resolve("renamed").resolve("agent.jar");
    Files.createDirectories(renamed.getParent());
    Files.copy(Path.of(agent), renamed, StandardCopyOption.REPLACE_EXISTING);
    Path work = Files.createDirectory(dir.resolve("work"));
    var run =
        ChildJvm.run(
            work, List.of("-javaagent:" + renamed + "=recrod,html=flase,min.scopes=0,=x"), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).hasSize(4).doesNotHaveDuplicates();
    assertThat(run.warnings().stream().filter(w -> w.contains("'recrod'"))).hasSize(1);
  }

  /**
   * The published example arguments (README, {@code ScopeTracerAgent}), the plugin's among them.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "html=false",
        "record,html=false",
        "output.dir=%1$s/reports,output.suffix=.report.html,min.scopes=2",
        "include.name=checkout-*,exclude.name=*-debug",
        "include.package=com.acme.**,exclude.package=com.noisy.**,sample.rate=0.25",
        "sample.rate=1.0",
        "sample.rate=0.0",
      })
  void validArgumentsGiveNoWarning(String template, @TempDir Path dir) throws Exception {
    var run = ChildJvm.run(dir, List.of(javaagent(template.formatted(dir))), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings()).isEmpty();
  }
}
