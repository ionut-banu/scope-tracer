package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests for {@link ScopeTracerAgent}. Forks a JVM with the agent fat-jar attached via
 * {@code -javaagent}, runs {@link AgentTestSubject}, and asserts that the expected JFR events were
 * emitted.
 *
 * <p>Requires the agent fat-jar to be built ({@code mvn package}). When the {@code agentJar} system
 * property is absent (e.g. during plain {@code mvn test}), all tests in this class are skipped. Run
 * {@code mvn verify} to execute them.
 */
class ScopeTracerAgentIT {

  /** Parsed recording from one invocation of {@link AgentTestSubject}; shared across all tests. */
  private static TraceModel model;

  /** The same recording's raw events, read with {@code RecordingFile}. */
  private static ScopeEvents events;

  @BeforeAll
  static void forkSubjectAndParseJfr() throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");

    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(
        !testClassesDir.isBlank(), "Skipping agent IT: testClassesDir system property not set.");

    Path jfrOut = Files.createTempFile("scope-tracer-agent-it-", ".jfr");
    try {
      launchSubject(agentJar, testClassesDir, AgentTestSubject.class, jfrOut);
      model = JfrParser.parse(jfrOut);
      events = ScopeEvents.read(jfrOut);
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }

  // --- named scope ---

  @Test
  void namedScopeUsesConfiguredName() {
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE)).isPresent();
  }

  @Test
  void namedScopeContainsThreeTasks() {
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE))
        .isPresent()
        .hasValueSatisfying(s -> assertThat(s.tasks()).hasSize(3));
  }

  @Test
  void namedScopeAllTasksSucceeded() {
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE))
        .isPresent()
        .hasValueSatisfying(
            s ->
                assertThat(s.tasks())
                    .allSatisfy(
                        t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class)));
  }

  // --- fork(Runnable) coverage ---

  @Test
  void forkRunnableIsTracedViaCallableDelegation() {
    // The Runnable task (task 3) must appear as a tracked task — the JDK forks it via
    // fork(Callable) internally, so ForkAdvice fires and a TaskSucceeded event is emitted.
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE))
        .isPresent()
        .hasValueSatisfying(
            s -> {
              // Three tasks: two Callable + one Runnable — all must have a completion outcome
              assertThat(s.tasks()).hasSize(3);
              assertThat(s.tasks()).allSatisfy(t -> assertThat(t.outcome()).isNotNull());
            });
  }

  // --- unnamed scope (stack-walker derived name) ---

  @Test
  void unnamedScopeNameDerivedFromCallSite() {
    // Unnamed scopes derive their name from the call-site via ScopeNameDeriver and always
    // follow the SimpleClassName#methodName format. Named scopes use configured names that
    // never contain '#'. Filter by the presence of '#' to isolate unnamed scopes.
    List<ScopeRecord> unnamed =
        model.scopes().stream().filter(s -> s.name().contains("#")).toList();

    assertThat(unnamed).isNotEmpty();
    assertThat(unnamed).allSatisfy(s -> assertThat(s.name()).doesNotContain("lambda$"));
  }

  @Test
  void unnamedScopeHasOneTask() {
    // The one unnamed scope opened with open(Joiner, UnaryOperator) directly in main().
    List<ScopeRecord> unnamed = scopesNamed(AgentTestSubject.UNNAMED_TWO_ARG_SCOPE);

    assertThat(unnamed).hasSize(1);
    assertThat(unnamed.get(0).tasks()).hasSize(1);
    assertThat(unnamed.get(0).tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
  }

  /**
   * Every unnamed scope is named after the user method that called {@code open}, whichever overload
   * it called, and a configured {@code withName} wins over the derived name: the derived names in
   * the recording are exactly the methods that open unnamed scopes, and no {@code ScopeOpened} of a
   * named scope carries a derived name instead.
   */
  @Test
  void unnamedScopesAreNamedAfterTheCallingMethodForEveryOverload() {
    assertThat(
            events.ofType(ScopeEvents.SCOPE_OPENED).stream()
                .map(ScopeEvents.Event::scopeName)
                .filter(n -> n.contains("#"))
                .distinct())
        .containsExactlyInAnyOrderElementsOf(AgentTestSubject.DERIVED_NAMES);
    assertThat(events.opened(AgentTestSubject.NAMED_SCOPE)).hasSize(1);
  }

  // --- one scope per open() call, for every overload (issue #51) ---

  static Stream<Arguments> openOverloads() {
    return Stream.of(
        Arguments.of("open()", AgentTestSubject.NO_ARG_SCOPE, 2),
        Arguments.of("open(Joiner)", AgentTestSubject.JOINER_SCOPE, 2),
        Arguments.of("open(Joiner, UnaryOperator) with withName", AgentTestSubject.NAMED_SCOPE, 3),
        Arguments.of(
            "open(Joiner, UnaryOperator) without withName",
            AgentTestSubject.UNNAMED_TWO_ARG_SCOPE,
            1));
  }

  /**
   * One {@code open} call is one {@code ScopeOpened}: the JDK's {@code open()} and {@code
   * open(Joiner)} call {@code open(Joiner, UnaryOperator)}, and every one of them is instrumented,
   * so the advice runs once per frame on the same scope. Read from the raw recording, not the
   * model: one {@code ScopeOpened}, one {@code ScopeClosed}, a {@code TaskForked} per fork, and no
   * other {@code scopeId} under the scope's name.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("openOverloads")
  void eachOpenOverloadRecordsExactlyOneScope(String overload, String scopeName, int forks) {
    var opened = events.opened(scopeName);
    assertThat(opened).as("ScopeOpened for %s", overload).hasSize(1);
    long scopeId = opened.get(0).scopeId();

    assertThat(events.ofScope(ScopeEvents.SCOPE_CLOSED, scopeId)).hasSize(1);
    assertThat(events.ofScope(ScopeEvents.TASK_FORKED, scopeId)).hasSize(forks);
    assertThat(events.scopeIdsNamed(scopeName)).containsExactly(scopeId);
  }

  /** The same, in the parsed model: one scope per call, with its tasks and a close time. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("openOverloads")
  void eachOpenOverloadIsOneClosedScopeInTheModel(String overload, String scopeName, int forks) {
    var scopes = scopesNamed(scopeName);
    assertThat(scopes).as("scopes for %s", overload).hasSize(1);
    assertThat(scopes.get(0).tasks()).hasSize(forks);
    assertThat(scopes.get(0).closeTime()).isNotNull();
  }

  /**
   * Nothing in the model is what {@code JfrParser} reports as truncated: its "truncated scope(s)"
   * warning counts scopes with no {@code closeTime} and tasks with no completion, so with neither
   * present the warning is not logged. The duplicate of #51 was an empty, never-closed scope.
   */
  @Test
  void modelHasNothingTheAnalyzerWouldReportAsTruncated() {
    assertThat(model.scopes())
        .noneSatisfy(s -> assertThat(s.closeTime()).isNull())
        .noneSatisfy(s -> assertThat(s.tasks()).isEmpty());
    assertThat(model.scopes().stream().flatMap(s -> s.tasks().stream()))
        .allSatisfy(
            t -> {
              assertThat(t.completionTime()).isNotNull();
              assertThat(t.outcome()).isNotNull();
            });
  }

  // --- outcomes, nesting, names and threads under open() and open(Joiner) ---

  @Test
  void failedTaskUnderOpenNoArgsIsRecordedAsFailedOnThatScope() {
    var scopes = scopesNamed(AgentTestSubject.NO_ARG_FAIL_SCOPE);
    assertThat(scopes).hasSize(1);
    var tasks = scopes.get(0).tasks();
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).outcome())
        .isInstanceOfSatisfying(
            TaskOutcome.Failed.class,
            f -> {
              assertThat(f.exceptionType()).isEqualTo(IllegalStateException.class.getName());
              assertThat(f.exceptionMessage()).isEqualTo(AgentTestSubject.NO_ARG_FAILURE_MESSAGE);
            });
  }

  @Test
  void loserUnderAnySuccessfulOrThrowIsRecordedAsCancelledOnThatScope() {
    var scopes = scopesNamed(AgentTestSubject.ANY_SUCCESS_SCOPE);
    assertThat(scopes).hasSize(1);
    var tasks = scopes.get(0).tasks().stream().sorted(Comparator.comparingLong(t -> t.taskId()));
    // Task 1 is the loser (forked first, blocks), task 2 the winner.
    assertThat(tasks.map(t -> t.outcome().getClass().getSimpleName()))
        .containsExactly("Cancelled", "Success");
  }

  @Test
  void openNoArgsInsideTasksHasItsOuterScopeAndTaskAsParentTwoLevelsDeep() {
    var level0 = onlyScopeNamed(AgentTestSubject.NESTED_LEVEL_0);
    var level1 = onlyScopeNamed(AgentTestSubject.NESTED_LEVEL_1);
    var level2 = onlyScopeNamed(AgentTestSubject.NESTED_LEVEL_2);

    assertThat(level0.parent()).isNull();
    assertThat(level1.parent())
        .isEqualTo(
            new ScopeRecord.ParentRef(
                level0.scopeId(), level0.name(), level0.tasks().get(0).taskId()));
    assertThat(level2.parent())
        .isEqualTo(
            new ScopeRecord.ParentRef(
                level1.scopeId(), level1.name(), level1.tasks().get(0).taskId()));
  }

  @Test
  void sameMethodOpeningTwiceGivesTwoScopesWithTheirOwnTasks() {
    var scopes = scopesNamed(AgentTestSubject.REPEATED_SCOPE);
    assertThat(scopes).hasSize(AgentTestSubject.REPEATED_COUNT);
    assertThat(scopes.stream().map(ScopeRecord::scopeId).distinct())
        .hasSize(AgentTestSubject.REPEATED_COUNT);
    assertThat(scopes).allSatisfy(s -> assertThat(s.tasks()).hasSize(1));
    assertThat(events.opened(AgentTestSubject.REPEATED_SCOPE))
        .hasSize(AgentTestSubject.REPEATED_COUNT);
  }

  @Test
  void openNoArgsFromSeveralThreadsAtOnceGivesOneScopeOpenedEachWithDistinctIds() {
    var opened = events.opened(AgentTestSubject.CONCURRENT_OPEN_SCOPE);
    assertThat(opened).hasSize(AgentTestSubject.CONCURRENT_SCOPE_COUNT);
    assertThat(opened.stream().map(ScopeEvents.Event::scopeId).distinct())
        .hasSize(AgentTestSubject.CONCURRENT_SCOPE_COUNT);
    assertThat(opened.stream().map(ScopeEvents.Event::threadName).distinct())
        .hasSize(AgentTestSubject.CONCURRENT_SCOPE_COUNT);
    assertThat(opened)
        .allSatisfy(
            o -> {
              assertThat(events.ofScope(ScopeEvents.SCOPE_CLOSED, o.scopeId())).hasSize(1);
              assertThat(events.ofScope(ScopeEvents.TASK_FORKED, o.scopeId())).hasSize(1);
            });
  }

  // --- task failure ---

  @Test
  void failedTaskIsRecordedByAgent() {
    var failScope = scopeNamed(AgentTestSubject.FAIL_SCOPE);
    assertThat(failScope).isPresent();
    var tasks = failScope.get().tasks();
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).outcome()).isInstanceOf(TaskOutcome.Failed.class);
    var failed = (TaskOutcome.Failed) tasks.get(0).outcome();
    assertThat(failed.exceptionType()).isEqualTo(IllegalStateException.class.getName());
    assertThat(failed.exceptionMessage()).isEqualTo(AgentTestSubject.FAILURE_MESSAGE);
    assertThat(failed.stackTrace()).isNotBlank();
  }

  // --- task cancellation ---

  @Test
  void cancelledTaskIsRecordedByAgent() {
    var cancelScope = scopeNamed(AgentTestSubject.CANCEL_SCOPE);
    assertThat(cancelScope).isPresent();
    var outcomes = cancelScope.get().tasks().stream().map(t -> t.outcome()).toList();
    assertThat(outcomes).anySatisfy(o -> assertThat(o).isInstanceOf(TaskOutcome.Failed.class));
    assertThat(outcomes).anySatisfy(o -> assertThat(o).isInstanceOf(TaskOutcome.Cancelled.class));
  }

  // --- nested scope ---

  @Test
  void nestedScopeParentRefIsDetectedByAgent() {
    var innerScope = scopeNamed(AgentTestSubject.INNER_SCOPE);
    assertThat(innerScope).isPresent();
    assertThat(innerScope.get().parent()).isNotNull();
    assertThat(innerScope.get().parent().scopeName()).isEqualTo(AgentTestSubject.OUTER_SCOPE);
  }

  // --- no duplicate instrumentation ---

  /**
   * Each call to {@code StructuredTaskScope.open()} must produce exactly one scope in the model. If
   * the ByteBuddy advice were wired twice (double-instrumentation regression), the model would
   * contain more scopes than expected.
   */
  @Test
  void modelContainsExactlyExpectedNumberOfScopes() {
    assertThat(model.scopes()).hasSize(AgentTestSubject.EXPECTED_SCOPE_COUNT);
    assertThat(events.ofType(ScopeEvents.SCOPE_OPENED))
        .hasSize(AgentTestSubject.EXPECTED_SCOPE_COUNT);
  }

  // --- concurrent scope scopeId distinctness ---

  /**
   * {@code N} threads each open a {@code StructuredTaskScope} simultaneously. The agent's {@code
   * SCOPE_ID_COUNTER.incrementAndGet()} must be race-free — all {@code N} scopes must receive
   * distinct {@code scopeId} values in the parsed model.
   */
  @Test
  void concurrentScopesGetDistinctScopeIds() {
    List<ScopeRecord> concurrent =
        model.scopes().stream()
            .filter(s -> s.name().startsWith(AgentTestSubject.CONCURRENT_SCOPE_BASE))
            .toList();

    assertThat(concurrent).hasSize(AgentTestSubject.CONCURRENT_SCOPE_COUNT);
    var scopeIds = concurrent.stream().map(ScopeRecord::scopeId).collect(Collectors.toSet());
    assertThat(scopeIds).hasSize(AgentTestSubject.CONCURRENT_SCOPE_COUNT);
  }

  // --- task names ---

  /**
   * Every task forked through the instrumented {@code StructuredTaskScope} carries a non-null
   * {@code taskName} stamped by the agent's {@code TaskNameDeriver}. Lambdas fall back to the
   * caller call-site format {@code SimpleClassName#methodName:line}.
   */
  @Test
  void agentStampsNonNullTaskNameOnForkedEvents() {
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE))
        .isPresent()
        .hasValueSatisfying(
            s -> {
              assertThat(s.tasks()).isNotEmpty();
              assertThat(s.tasks())
                  .allSatisfy(
                      t -> {
                        assertThat(t.taskName()).isNotNull();
                        assertThat(t.taskName()).isNotBlank();
                      });
            });
  }

  /**
   * For lambda-forked tasks, the agent's caller-frame derivation produces {@code
   * SimpleClassName#methodName:line}. The named scope's tasks are all forked from {@code
   * AgentTestSubject#main}, so their {@code taskName} must start with {@code AgentTestSubject#}.
   */
  @Test
  void agentDerivesTaskNameFromCallerFrameForLambdas() {
    assertThat(scopeNamed(AgentTestSubject.NAMED_SCOPE))
        .isPresent()
        .hasValueSatisfying(
            s ->
                assertThat(s.tasks())
                    .allSatisfy(t -> assertThat(t.taskName()).startsWith("AgentTestSubject#")));
  }

  // --- scope lifecycle ---

  @Test
  void allScopesHaveOpenAndCloseTimestamps() {
    assertThat(model.scopes()).isNotEmpty();
    assertThat(model.scopes())
        .allSatisfy(s -> assertThat(s.openTime()).isNotNull())
        .allSatisfy(s -> assertThat(s.closeTime()).isNotNull());
  }

  // --- a failing advice loses events, never behaviour (spec §1) ---

  /**
   * {@link AgentAdviceFailureSubject} makes {@code ScopeOpenAdvice} throw in every {@code open}
   * overload and checks, in the traced JVM, that each still returns a working scope and that {@code
   * open} throws what its configuration function threw; it exits non-zero otherwise. The scopes
   * opened while the advice fails lose their events; the one opened afterwards is traced.
   */
  @Test
  void failingOpenAdviceLosesEventsButNeverChangesWhatOpenReturnsOrThrows(@TempDir Path tempDir)
      throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    String testClassesDir = System.getProperty("testClassesDir", "");
    Path jfr = tempDir.resolve("advice-failure.jfr");

    launchSubject(agentJar, testClassesDir, AgentAdviceFailureSubject.class, jfr);
    var failureEvents = ScopeEvents.read(jfr);

    var opened = failureEvents.ofType(ScopeEvents.SCOPE_OPENED);
    assertThat(opened)
        .extracting(ScopeEvents.Event::scopeName)
        .containsExactly(AgentAdviceFailureSubject.AFTER_RESTORE_SCOPE);
    long tracedId = opened.get(0).scopeId();
    assertThat(failureEvents.events()).allSatisfy(e -> assertThat(e.scopeId()).isEqualTo(tracedId));
    assertThat(failureEvents.ofScope(ScopeEvents.TASK_FORKED, tracedId)).hasSize(1);
    assertThat(failureEvents.ofScope(ScopeEvents.SCOPE_CLOSED, tracedId)).hasSize(1);
  }

  // --- auto-HTML generation ---

  /**
   * When a JFR recording is stopped with a destination file set (mirroring {@code jcmd JFR.start
   * filename=...}), the agent's {@link jdk.jfr.FlightRecorderListener} must automatically write an
   * HTML report alongside the {@code .jfr} file.
   */
  @Test
  void autoHtmlIsWrittenAlongsideJfrOnRecordingStop(@TempDir Path tempDir) throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping: run 'mvn verify' to build the fat-jar.");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(!testClassesDir.isBlank(), "Skipping: testClassesDir system property not set.");

    Path jfr = tempDir.resolve("auto.jfr");
    Path html = tempDir.resolve("auto.html");

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    int exit =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-javaagent:" + agentJar,
                "-cp",
                testClassesDir,
                "com.ionutbanu.scopetracer.agent.AutoHtmlTestSubject",
                jfr.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor();

    assertThat(exit).isZero();
    assertThat(jfr).exists();
    assertThat(html).exists();
    assertThat(Files.readString(html)).contains("checkout-flow");
  }

  // --- agent argument: output.dir ---

  /**
   * {@code output.dir=<path>} routes the generated HTML to that directory instead of placing it
   * alongside the {@code .jfr} file. The directory is created on demand.
   */
  @Test
  void outputDirAgentArgRoutesHtmlToCustomDirectory(@TempDir Path tempDir) throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping: run 'mvn verify' to build the fat-jar.");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(!testClassesDir.isBlank(), "Skipping: testClassesDir system property not set.");

    Path jfr = tempDir.resolve("auto.jfr");
    Path htmlDir = tempDir.resolve("reports");
    Path htmlExpected = htmlDir.resolve("auto.html");

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    int exit =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-javaagent:" + agentJar + "=output.dir=" + htmlDir,
                "-cp",
                testClassesDir,
                "com.ionutbanu.scopetracer.agent.AutoHtmlTestSubject",
                jfr.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor();

    assertThat(exit).isZero();
    assertThat(jfr).exists();
    assertThat(htmlExpected).exists();
    // No HTML alongside the .jfr file when output.dir is used.
    assertThat(jfr.resolveSibling("auto.html")).doesNotExist();
  }

  // --- agent argument: min.scopes ---

  /**
   * {@code min.scopes=N} skips HTML generation when the recording contains fewer than N scopes. The
   * test subject opens one scope; {@code min.scopes=999} therefore suppresses the HTML.
   */
  @Test
  void minScopesAgentArgSkipsHtmlWhenBelowThreshold(@TempDir Path tempDir) throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping: run 'mvn verify' to build the fat-jar.");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(!testClassesDir.isBlank(), "Skipping: testClassesDir system property not set.");

    Path jfr = tempDir.resolve("auto.jfr");
    Path html = tempDir.resolve("auto.html");

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    int exit =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-javaagent:" + agentJar + "=min.scopes=999",
                "-cp",
                testClassesDir,
                "com.ionutbanu.scopetracer.agent.AutoHtmlTestSubject",
                jfr.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor();

    assertThat(exit).isZero();
    assertThat(jfr).exists();
    assertThat(html).doesNotExist();
  }

  // --- helpers ---

  private static java.util.Optional<ScopeRecord> scopeNamed(String name) {
    return model.scopes().stream().filter(s -> name.equals(s.name())).findFirst();
  }

  private static List<ScopeRecord> scopesNamed(String name) {
    return model.scopes().stream().filter(s -> name.equals(s.name())).toList();
  }

  private static ScopeRecord onlyScopeNamed(String name) {
    var scopes = scopesNamed(name);
    assertThat(scopes).as("scopes named %s", name).hasSize(1);
    return scopes.get(0);
  }

  private static void launchSubject(
      String agentJar, String testClassesDir, Class<?> subject, Path jfrOut)
      throws IOException, InterruptedException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    var proc =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-javaagent:" + agentJar,
                "-cp",
                testClassesDir,
                subject.getName(),
                jfrOut.toString())
            .redirectErrorStream(true)
            .start();

    String output = new String(proc.getInputStream().readAllBytes());
    int exitCode = proc.waitFor();

    if (exitCode != 0) {
      fail(subject.getSimpleName() + " process exited with code " + exitCode + ":\n" + output);
    }
  }
}
