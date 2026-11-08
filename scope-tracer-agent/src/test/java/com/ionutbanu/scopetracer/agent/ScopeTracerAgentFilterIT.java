package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the agent's capture-filter and sampling features ({@link CaptureFilter}).
 * Each test forks a JVM with {@code -javaagent:...=<filter-args>} and runs {@link AgentTestSubject}
 * (or, for sampling over many scopes, {@link AgentSamplingSubject}), then asserts that the
 * recording contains exactly the scopes the filter should have admitted.
 *
 * <p>Requires the agent fat-jar to be built ({@code mvn package}). When the {@code agentJar} system
 * property is absent (e.g. during plain {@code mvn test}), all tests in this class are skipped. Run
 * {@code mvn verify} to execute them.
 */
class ScopeTracerAgentFilterIT {

  private static String agentJar;
  private static String testClassesDir;

  @BeforeAll
  static void resolveAgentJar() {
    agentJar = System.getProperty("agentJar", "");
    testClassesDir = System.getProperty("testClassesDir", "");
  }

  @Test
  void excludeNameSkipsAllEventsForMatchingScope() throws Exception {
    var model = launchAndParse("exclude.name=" + AgentTestSubject.NAMED_SCOPE);
    // The excluded scope must be entirely absent from the model — no open, no fork, no close.
    assertThat(scopeNamed(model, AgentTestSubject.NAMED_SCOPE)).isEmpty();
    // Sibling scopes still captured.
    assertThat(scopeNamed(model, AgentTestSubject.FAIL_SCOPE)).isPresent();
    assertThat(scopeNamed(model, AgentTestSubject.OUTER_SCOPE)).isPresent();
  }

  @Test
  void excludeNameGlobDropsAllNamedScopes() throws Exception {
    // Every named scope in AgentTestSubject starts with "agent-"; the unnamed scope is derived
    // as "AgentTestSubject#main" and must survive.
    var model = launchAndParse("exclude.name=agent-*");
    assertThat(model.scopes()).allSatisfy(s -> assertThat(s.name()).contains("#"));
    assertThat(model.scopes()).isNotEmpty();
  }

  @Test
  void includeNameKeepsOnlyMatchingScopes() throws Exception {
    var model = launchAndParse("include.name=" + AgentTestSubject.NAMED_SCOPE);
    assertThat(model.scopes()).hasSize(1);
    assertThat(model.scopes().get(0).name()).isEqualTo(AgentTestSubject.NAMED_SCOPE);
    // All fork events for the kept scope must still be recorded (3 tasks).
    assertThat(model.scopes().get(0).tasks()).hasSize(3);
  }

  @Test
  void sampleRateZeroProducesEmptyModel() throws Exception {
    var model = launchAndParse("sample.rate=0.0");
    assertThat(model.scopes()).isEmpty();
  }

  @Test
  void sampleRateOneCapturesEverything() throws Exception {
    // sample.rate=1.0 is the default; passing it explicitly must change nothing.
    var model = launchAndParse("sample.rate=1.0");
    // Same count as ScopeTracerAgentIT#modelContainsExactlyExpectedNumberOfScopes.
    assertThat(model.scopes()).hasSize(AgentTestSubject.EXPECTED_SCOPE_COUNT);
  }

  @Test
  void includePackageKeepsScopesFromMatchingCallSite() throws Exception {
    // AgentTestSubject lives in com.ionutbanu.scopetracer.agent, so include.package matching
    // that package must keep every scope (including those opened from worker threads in the
    // same class).
    var model = launchAndParse("include.package=com.ionutbanu.scopetracer.agent");
    assertThat(model.scopes()).hasSize(AgentTestSubject.EXPECTED_SCOPE_COUNT);
  }

  @Test
  void excludePackageDropsScopesFromMatchingCallSite() throws Exception {
    var model = launchAndParse("exclude.package=com.ionutbanu.scopetracer.agent");
    assertThat(model.scopes()).isEmpty();
  }

  @Test
  void includePackageWithUnmatchedGlobDropsAllScopes() throws Exception {
    // No subject scope is opened from this package — all must be filtered out.
    var model = launchAndParse("include.package=com.example.nonexistent");
    assertThat(model.scopes()).isEmpty();
  }

  // --- one filter decision per scope, whichever open overload opened it (issue #51) ---

  /**
   * An include rule on the derived name of a scope opened with {@code open()} keeps that one scope
   * with its two tasks. Include and exclude are deterministic, so before #51 the rule kept both
   * {@code ScopeOpened} of the call and the model had a second, empty scope.
   */
  @Test
  void includeNameOnAnOpenNoArgsScopeKeepsExactlyThatOneScope() throws Exception {
    var model = launchAndParse("include.name=" + AgentTestSubject.NO_ARG_SCOPE);
    assertThat(model.scopes()).hasSize(1);
    assertThat(model.scopes().get(0).name()).isEqualTo(AgentTestSubject.NO_ARG_SCOPE);
    assertThat(model.scopes().get(0).tasks()).hasSize(2);
  }

  @Test
  void excludeNameOnAnOpenWithJoinerScopeDropsExactlyThatOneScope() throws Exception {
    var model = launchAndParse("exclude.name=" + AgentTestSubject.JOINER_SCOPE);
    assertThat(scopeNamed(model, AgentTestSubject.JOINER_SCOPE)).isEmpty();
    assertThat(model.scopes()).hasSize(AgentTestSubject.EXPECTED_SCOPE_COUNT - 1);
  }

  /**
   * {@code sample.rate=0.5} over {@value AgentSamplingSubject#SCOPE_COUNT} scopes opened with
   * {@code open()} keeps about half, and every kept scope has its close and its fork. Fixed, the
   * {@code ScopeOpened} count is binomial(1000, 0.5): 500 ± 16. Before #51 the filter ran in each
   * of the two advised {@code open} frames and each passing draw emitted, so the count was about
   * 1,000 (974 measured) and about 750 scopes had at least one. The bounds are more than 6 standard
   * deviations from 500 and further still from both of those.
   */
  @Test
  void sampleRateHalfKeepsAboutHalfOfTheScopesOpenedWithOpenNoArgs() throws Exception {
    var events = launchAndRead(AgentSamplingSubject.class, "sample.rate=0.5");

    var opened = events.ofType(ScopeEvents.SCOPE_OPENED);
    assertThat(opened.size()).isBetween(400, 600);
    var openedIds = opened.stream().map(ScopeEvents.Event::scopeId).toList();
    assertThat(openedIds).doesNotHaveDuplicates();
    assertThat(events.ofType(ScopeEvents.SCOPE_CLOSED))
        .extracting(ScopeEvents.Event::scopeId)
        .containsExactlyInAnyOrderElementsOf(openedIds);
    assertThat(events.ofType(ScopeEvents.TASK_FORKED))
        .extracting(ScopeEvents.Event::scopeId)
        .containsExactlyInAnyOrderElementsOf(openedIds);
  }

  // --- helpers ---

  private static java.util.Optional<com.ionutbanu.scopetracer.analyzer.model.ScopeRecord>
      scopeNamed(TraceModel model, String name) {
    return model.scopes().stream().filter(s -> name.equals(s.name())).findFirst();
  }

  private static TraceModel launchAndParse(String agentArgs) throws Exception {
    Path jfrOut = launch(AgentTestSubject.class, agentArgs);
    try {
      return JfrParser.parse(jfrOut);
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }

  private static ScopeEvents launchAndRead(Class<?> subject, String agentArgs) throws Exception {
    Path jfrOut = launch(subject, agentArgs);
    try {
      return ScopeEvents.read(jfrOut);
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }

  /** Runs {@code subject} under the agent with {@code agentArgs}; returns its recording. */
  private static Path launch(Class<?> subject, String agentArgs) throws Exception {
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping agent IT: run 'mvn verify' to build the fat-jar.");
    assumeTrue(
        !testClassesDir.isBlank(), "Skipping agent IT: testClassesDir system property not set.");

    Path jfrOut = Files.createTempFile("scope-tracer-filter-it-", ".jfr");
    try {
      launchSubject(agentJar, testClassesDir, subject, agentArgs, jfrOut);
      return jfrOut;
    } catch (Exception | AssertionError e) {
      Files.deleteIfExists(jfrOut);
      throw e;
    }
  }

  private static void launchSubject(
      String agentJar, String testClassesDir, Class<?> subject, String agentArgs, Path jfrOut)
      throws IOException, InterruptedException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String agentSpec = "-javaagent:" + agentJar + "=" + agentArgs;

    var proc =
        new ProcessBuilder(
                java,
                "--enable-preview",
                agentSpec,
                "-cp",
                testClassesDir,
                subject.getName(),
                jfrOut.toString())
            .redirectErrorStream(true)
            .start();

    String output = new String(proc.getInputStream().readAllBytes());
    int exitCode = proc.waitFor();

    if (exitCode != 0) {
      fail(
          subject.getSimpleName()
              + " process exited with code "
              + exitCode
              + " (args="
              + agentArgs
              + "):\n"
              + output);
    }
  }
}
