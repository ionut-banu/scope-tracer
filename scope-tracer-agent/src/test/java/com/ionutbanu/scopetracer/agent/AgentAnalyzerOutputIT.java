package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's auto-HTML and the analyzer's CLI on a recording with a scope still open (#58). The
 * agent calls {@code JfrParser} and {@code HtmlRenderer}, not {@code AnalyzerMain}, so it prints
 * neither the CLI's {@code analyzer: warning:} line nor the parser's WARN. The CLI has one code
 * path for both producers: the agent's recording and a {@code TracedScope} one of the same shape
 * get the same warning line.
 */
class AgentAnalyzerOutputIT {

  private static String agent;
  private static Path analyzer;

  @BeforeAll
  static void requireJars() {
    agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
    analyzer = Path.of(System.getProperty("analyzerExecutableJar", ""));
    assertThat(analyzer).as("the analyzer's -executable jar").exists();
  }

  @Test
  void recordWithAScopeOpenAtExitPrintsOnlyAgentLines(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("agent-open.jfr");
    var with = ChildJvm.run(dir, List.of("-javaagent:" + agent + "=record=" + jfr), "exit");
    var without = ChildJvm.run(dir, List.of(), "exit");

    with.assertSameProgramAs(without);
    assertThat(with.stdout()).isEqualTo(ExitSubject.SCOPE_RUNNING + "\n");
    assertThat(with.stderr().lines()).allMatch(l -> l.startsWith(ChildJvm.AGENT_PREFIX));
    assertThat(with.stderr())
        .doesNotContain("analyzer:")
        .doesNotContain("SLF4J")
        .doesNotContain("JfrParser")
        .doesNotContain(" WARN ");
    assertThat(with.writtenReports()).hasSize(1);
  }

  @Test
  void agentAndTracedScopeRecordingsOfAnOpenScopeGetTheSameCliWarning(@TempDir Path dir)
      throws Exception {
    Path fromAgent = dir.resolve("agent.jfr");
    var run =
        ChildJvm.run(
            dir, List.of("-javaagent:" + agent + "=record=" + fromAgent + ",html=false"), "exit");
    assertThat(run.exitCode()).as(run.stderr()).isEqualTo(3);
    Path fromTracedScope = tracedScopeLeftOpen(dir.resolve("traced.jfr"));

    String agentLine = cliWarning(dir, fromAgent);
    String tracedLine = cliWarning(dir, fromTracedScope);

    assertThat(agentLine)
        .startsWith(
            "analyzer: warning: "
                + fromAgent
                + " has 1 scope without a close event and 1 task without a completion event;");
    assertThat(agentLine.replace(fromAgent.toString(), "<file>"))
        .isEqualTo(tracedLine.replace(fromTracedScope.toString(), "<file>"));
  }

  /**
   * The shape of {@link ExitSubject}'s {@code exit} mode, made with {@code TracedScope} in this JVM
   * (which has no agent): one closed scope with one task, then one scope whose one task is blocked
   * when the recording is dumped.
   */
  private static Path tracedScopeLeftOpen(Path jfr) throws Exception {
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      try (var scope = TracedScope.open("traced-closed")) {
        scope.fork(() -> "ok");
        scope.join();
      }
      var running = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var owner =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (var scope = TracedScope.open("traced-open")) {
                      scope.fork(
                          () -> {
                            running.countDown();
                            release.await();
                            return null;
                          });
                      scope.join();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  });
      running.await();
      recording.dump(jfr);
      recording.stop();
      release.countDown();
      owner.join();
    }
    return jfr;
  }

  /** Runs the analyzer CLI on {@code jfr} and returns its one stderr line; stdout is one line. */
  private static String cliWarning(Path dir, Path jfr) throws Exception {
    Path out = dir.resolve(jfr.getFileName() + ".out");
    Path err = dir.resolve(jfr.getFileName() + ".err");
    var process =
        new ProcessBuilder(
                ChildJvm.javaCommand(
                    ChildJvm.javaHome().resolve("bin").resolve("java").toString(),
                    "-jar",
                    analyzer.toString(),
                    jfr.toString(),
                    dir.resolve(jfr.getFileName() + ".html").toString()))
            .directory(dir.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    assertThat(process.waitFor(ChildJvm.TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    String stderr = Files.readString(err, StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(stderr).isZero();
    assertThat(Files.readString(out, StandardCharsets.UTF_8).lines()).hasSize(1);
    var lines = stderr.lines().toList();
    assertThat(lines).hasSize(1);
    return lines.getFirst();
  }
}
