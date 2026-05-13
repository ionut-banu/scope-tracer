package com.ionutbanu.scopetracer.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Forks a child JVM with the scope-tracer agent attached and {@link AgentParallelStressSubject}
 * driving a heavy concurrent load. Verifies the agent's bytecode instrumentation does not lose
 * scope or task events under pressure.
 *
 * <p>Tuned by system properties:
 *
 * <ul>
 *   <li>{@code stress.scopes} — number of inner scopes the subject opens (default 100)
 *   <li>{@code stress.tasksPerScope} — tasks per inner scope (default 20)
 * </ul>
 */
class AgentParallelStressIT {

  // Conservative defaults so JFR's per-thread buffers don't overflow under the agent's
  // bytecode-emitted event burst. Override via -Dstress.scopes -Dstress.tasksPerScope for
  // heavier loads (with the understanding that event loss above ~5k events/sec is a JFR limit,
  // not a scope-tracer bug).
  private static final int SCOPES = Integer.getInteger("stress.scopes", 50);
  private static final int TASKS_PER_SCOPE = Integer.getInteger("stress.tasksPerScope", 10);

  @Test
  @Timeout(180)
  void agentEmitsAllEventsUnderConcurrentLoad() throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping agent stress IT: build the agent fat-jar first (mvn -DskipTests package).");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(!testClassesDir.isBlank(), "testClassesDir system property not set.");

    Path jfrOut = Files.createTempFile("stress-agent-", ".jfr");
    try {
      runSubject(agentJar, testClassesDir, jfrOut);

      TraceModel model = JfrParser.parse(jfrOut);
      int expectedScopes = SCOPES + 1; // outer + per-iteration inner
      assertThat(model.scopes()).as("parsed scope records").hasSize(expectedScopes);

      long taskCount = model.scopes().stream().mapToLong(s -> s.tasks().size()).sum();
      long expectedTasks = SCOPES + (long) SCOPES * TASKS_PER_SCOPE;
      assertThat(taskCount).as("total tasks across all scopes").isEqualTo(expectedTasks);

      assertThat(model.scopes())
          .as("every task succeeded")
          .allSatisfy(
              s ->
                  assertThat(s.tasks())
                      .allSatisfy(
                          t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class)));
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }

  private static void runSubject(String agentJar, String testClassesDir, Path jfrOut)
      throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var proc =
        new ProcessBuilder(
                java,
                "--enable-preview",
                "-XX:FlightRecorderOptions=stackdepth=64,threadbuffersize=1M,memorysize=64M,maxchunksize=128M",
                "-javaagent:" + agentJar,
                "-cp",
                testClassesDir,
                AgentParallelStressSubject.class.getName(),
                jfrOut.toString(),
                Integer.toString(SCOPES),
                Integer.toString(TASKS_PER_SCOPE))
            .redirectErrorStream(true)
            .start();
    String output = new String(proc.getInputStream().readAllBytes());
    int exit = proc.waitFor();
    if (exit != 0) {
      fail("AgentParallelStressSubject exited with code " + exit + ":\n" + output);
    }
  }
}
