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
    // The unnamed scope must NOT appear as "agent-it-scope".
    // Its name must follow the SimpleClassName#methodName format produced by ScopeNameDeriver.
    List<ScopeRecord> unnamed =
        model.scopes().stream()
            .filter(s -> !AgentTestSubject.NAMED_SCOPE.equals(s.name()))
            .toList();

    assertThat(unnamed).isNotEmpty();
    assertThat(unnamed)
        .allSatisfy(s -> assertThat(s.name()).contains("#"))
        .allSatisfy(s -> assertThat(s.name()).doesNotContain("lambda$"));
  }

  @Test
  void unnamedScopeHasOneTask() {
    List<ScopeRecord> unnamed =
        model.scopes().stream()
            .filter(s -> !AgentTestSubject.NAMED_SCOPE.equals(s.name()))
            .toList();

    assertThat(unnamed).hasSize(1);
    assertThat(unnamed.get(0).tasks()).hasSize(1);
    assertThat(unnamed.get(0).tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
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
