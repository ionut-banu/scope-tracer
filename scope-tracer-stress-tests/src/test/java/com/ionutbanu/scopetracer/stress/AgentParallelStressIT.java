package com.ionutbanu.scopetracer.stress;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Forks a child JVM with the scope-tracer agent attached and {@link AgentParallelStressSubject}
 * driving a heavy concurrent load. Verifies the agent's bytecode instrumentation does not lose
 * scope or task events under pressure, and that the analyzer turns everything the recording holds
 * into the model.
 *
 * <p>Every repetition is a fresh child JVM and counts twice: the events in the recording ({@code
 * ScopeOpened}, {@code ScopeClosed}, {@code TaskForked}, {@code TaskSucceeded}) and, after {@link
 * JfrParser}, the scopes, the scopes with a close time, the tasks and the tasks that succeeded. The
 * two counts are never allowed any tolerance, because they fail for different reasons: a recording
 * that is short lost events (JFR or the agent), a complete recording whose model is short is the
 * analyzer's fault. The failure message says which, shows both counts and keeps the recording.
 *
 * <p>Tuned by system properties:
 *
 * <ul>
 *   <li>{@code stress.scopes} — number of inner scopes the subject opens (default 50)
 *   <li>{@code stress.tasksPerScope} — tasks per inner scope (default 10)
 *   <li>{@code stress.repetitions} — number of child JVMs, each checked on its own (default 1)
 * </ul>
 *
 * <p>The reliability check, 300 clean repetitions in a row (about 6 minutes), run from the
 * repository root:
 *
 * <pre>{@code
 * mvn -q -DskipTests install && mvn -q -Pstress verify -Dstress.repetitions=300
 * }</pre>
 */
class AgentParallelStressIT {

  // Conservative defaults so JFR's per-thread buffers don't overflow under the agent's
  // bytecode-emitted event burst. Override via -Dstress.scopes -Dstress.tasksPerScope for
  // heavier loads (with the understanding that event loss above ~5k events/sec is a JFR limit,
  // not a scope-tracer bug).
  private static final int SCOPES = Integer.getInteger("stress.scopes", 50);
  private static final int TASKS_PER_SCOPE = Integer.getInteger("stress.tasksPerScope", 10);
  private static final int REPETITIONS = Integer.getInteger("stress.repetitions", 1);

  private static final String OUTER = "agent-stress-outer";

  /** One inner scope per outer task plus the outer scope itself. */
  private static final long EXPECTED_SCOPES = SCOPES + 1L;

  /** One task per inner scope in the outer scope, plus the inner scopes' own tasks. */
  private static final long EXPECTED_TASKS = SCOPES + (long) SCOPES * TASKS_PER_SCOPE;

  // A repetition takes about a second, so 300 of them take about 6 minutes; the test's own timeout
  // (90 minutes) only guards the whole run, and each child JVM has this timeout of its own so that
  // a hung child fails the run quickly.
  private static final long CHILD_TIMEOUT_SECONDS = 120;

  @Test
  @Timeout(value = 90, unit = TimeUnit.MINUTES)
  void agentEmitsAllEventsUnderConcurrentLoad() throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping agent stress IT: build the agent fat-jar first (mvn -DskipTests package).");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(!testClassesDir.isBlank(), "testClassesDir system property not set.");

    for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
      runRepetition(agentJar, testClassesDir, repetition);
    }
  }

  private static void runRepetition(String agentJar, String testClassesDir, int repetition)
      throws Exception {
    Path jfrOut = Files.createTempFile("stress-agent-", ".jfr");
    boolean passed = false;
    try {
      runSubject(agentJar, testClassesDir, jfrOut);

      RecordingCounts recorded = countRecording(jfrOut);
      TraceModel model = JfrParser.parse(jfrOut);
      ModelCounts parsed = countModel(model);

      String counts =
          "repetition %d of %d%n  expected  : %s%n  recording : %s%n  model     : %s"
              .formatted(
                  repetition,
                  REPETITIONS,
                  new RecordingCounts(
                      EXPECTED_SCOPES, EXPECTED_SCOPES, EXPECTED_TASKS, EXPECTED_TASKS),
                  recorded,
                  parsed);

      if (!recorded.equals(
          new RecordingCounts(EXPECTED_SCOPES, EXPECTED_SCOPES, EXPECTED_TASKS, EXPECTED_TASKS))) {
        fail(
            "the recording is short: JFR or the agent lost events, the analyzer is not involved%n%s%n"
                + "scopes whose task count differs from the expectation: %s%nrecording kept at %s",
            counts, shortScopes(model), jfrOut);
      }
      if (!parsed.equals(
          new ModelCounts(EXPECTED_SCOPES, EXPECTED_SCOPES, EXPECTED_TASKS, EXPECTED_TASKS))) {
        fail(
            "the recording is complete but the parsed model is short: the analyzer dropped events%n%s%n"
                + "scopes whose task count differs from the expectation: %s%nrecording kept at %s",
            counts, shortScopes(model), jfrOut);
      }
      passed = true;
    } finally {
      if (passed) {
        Files.deleteIfExists(jfrOut);
      } else {
        // Keep the recording so CI's "Upload JFR dumps on failure" step (it globs
        // /tmp/stress-*.jfr) has something to upload for diagnosis.
        System.err.println("Keeping recording for diagnosis: " + jfrOut);
      }
    }
  }

  private record RecordingCounts(long opened, long closed, long forked, long succeeded) {
    @Override
    public String toString() {
      return "ScopeOpened=%d ScopeClosed=%d TaskForked=%d TaskSucceeded=%d"
          .formatted(opened, closed, forked, succeeded);
    }
  }

  private record ModelCounts(long scopes, long closedScopes, long tasks, long succeededTasks) {
    @Override
    public String toString() {
      return "scopes=%d scopesWithCloseTime=%d tasks=%d tasksSucceeded=%d"
          .formatted(scopes, closedScopes, tasks, succeededTasks);
    }
  }

  private static RecordingCounts countRecording(Path jfr) throws IOException {
    long opened = 0;
    long closed = 0;
    long forked = 0;
    long succeeded = 0;
    try (var file = new RecordingFile(jfr)) {
      while (file.hasMoreEvents()) {
        switch (file.readEvent().getEventType().getName()) {
          case "com.ionutbanu.scopetracer.ScopeOpened" -> opened++;
          case "com.ionutbanu.scopetracer.ScopeClosed" -> closed++;
          case "com.ionutbanu.scopetracer.TaskForked" -> forked++;
          case "com.ionutbanu.scopetracer.TaskSucceeded" -> succeeded++;
          default -> {}
        }
      }
    }
    return new RecordingCounts(opened, closed, forked, succeeded);
  }

  private static ModelCounts countModel(TraceModel model) {
    long closedScopes = model.scopes().stream().filter(s -> s.closeTime() != null).count();
    long tasks = model.scopes().stream().mapToLong(s -> s.tasks().size()).sum();
    long succeeded =
        model.scopes().stream()
            .flatMap(s -> s.tasks().stream())
            .filter(t -> t.outcome() instanceof TaskOutcome.Success)
            .count();
    return new ModelCounts(model.scopes().size(), closedScopes, tasks, succeeded);
  }

  /** Names the scopes with an unexpected task count, so a failure shows where tasks are missing. */
  private static List<String> shortScopes(TraceModel model) {
    var out = new ArrayList<String>();
    for (var s : model.scopes()) {
      int expected = OUTER.equals(s.name()) ? SCOPES : TASKS_PER_SCOPE;
      if (s.tasks().size() != expected) out.add(s.name() + "=" + s.tasks().size());
    }
    return out;
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
    // The output is drained on this thread, so the child cannot block on a full pipe; a hung child
    // is killed once the timeout has passed.
    var output = new java.io.ByteArrayOutputStream();
    var drain =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    proc.getInputStream().transferTo(output);
                  } catch (IOException ignored) {
                    // child killed or pipe closed; the exit code below says what happened
                  }
                });
    if (!proc.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      proc.destroyForcibly();
      fail("AgentParallelStressSubject did not finish within " + CHILD_TIMEOUT_SECONDS + " s");
    }
    drain.join();
    int exit = proc.exitValue();
    if (exit != 0) {
      fail("AgentParallelStressSubject exited with code " + exit + ":\n" + output);
    }
  }
}
