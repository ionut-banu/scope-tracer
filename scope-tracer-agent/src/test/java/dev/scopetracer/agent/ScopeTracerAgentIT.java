package dev.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.scopetracer.analyzer.JfrParser;
import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
      launchSubject(agentJar, testClassesDir, jfrOut);
      model = JfrParser.parse(jfrOut);
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
    List<ScopeRecord> unnamed =
        model.scopes().stream().filter(s -> s.name().contains("#")).toList();

    assertThat(unnamed).hasSize(1);
    assertThat(unnamed.get(0).tasks()).hasSize(1);
    assertThat(unnamed.get(0).tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
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
    assertThat(failed.exceptionMessage()).isEqualTo("intentional failure");
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

  // --- scope lifecycle ---

  @Test
  void allScopesHaveOpenAndCloseTimestamps() {
    assertThat(model.scopes()).isNotEmpty();
    assertThat(model.scopes())
        .allSatisfy(s -> assertThat(s.openTime()).isNotNull())
        .allSatisfy(s -> assertThat(s.closeTime()).isNotNull());
  }

  // --- helpers ---

  private static java.util.Optional<ScopeRecord> scopeNamed(String name) {
    return model.scopes().stream().filter(s -> name.equals(s.name())).findFirst();
  }

  private static void launchSubject(String agentJar, String testClassesDir, Path jfrOut)
      throws IOException, InterruptedException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    var proc =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-javaagent:" + agentJar,
                "-cp",
                testClassesDir,
                "dev.scopetracer.agent.AgentTestSubject",
                jfrOut.toString())
            .redirectErrorStream(true)
            .start();

    String output = new String(proc.getInputStream().readAllBytes());
    int exitCode = proc.waitFor();

    if (exitCode != 0) {
      fail("AgentTestSubject process exited with code " + exitCode + ":\n" + output);
    }
  }
}
