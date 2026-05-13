package com.ionutbanu.scopetracer.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.core.TracedScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opens a single long-lived {@link TracedScope} and forks many staggered subtasks. Verifies that
 * the JFR recording captures every task as well as the (single) scope's open/close pair, and that
 * the parsed scope duration is plausible for the chosen workload.
 *
 * <p>Tuned by system properties:
 *
 * <ul>
 *   <li>{@code stress.longTaskCount} — number of tasks to fork (default 5000)
 *   <li>{@code stress.longTaskMicros} — micro-seconds of busy-wait per task (default 50)
 * </ul>
 */
class LongRunningScopeIT {

  // Scale chosen to keep emission rate well below JFR's burst-write capacity. JFR drops events
  // when per-thread buffers can't flush to disk in time; the regression-detection value of this
  // test depends on staying below that ceiling on the default CI runner. Override via
  // -Dstress.longTaskCount=N -Dstress.longTaskMicros=M for heavier loads.
  private static final int TASK_COUNT = Integer.getInteger("stress.longTaskCount", 1000);
  private static final int TASK_MICROS = Integer.getInteger("stress.longTaskMicros", 1000);
  private static final String SCOPE = "stress-long-running";

  @Test
  @Timeout(120)
  void manyStaggeredTasksAllRecorded() throws Exception {
    Path jfrOut = Files.createTempFile("stress-long-running-", ".jfr");
    long startNs = System.nanoTime();
    try {
      try (var recording = new jdk.jfr.Recording()) {
        recording.enable("com.ionutbanu.scopetracer.*");
        recording.setToDisk(true);
        recording.setMaxSize(64L * 1024 * 1024);
        recording.start();

        try (var scope = TracedScope.open(SCOPE)) {
          for (int i = 0; i < TASK_COUNT; i++) {
            scope.fork(
                () -> {
                  LockSupport.parkNanos(TASK_MICROS * 1_000L);
                  return null;
                });
          }
          scope.join();
        }

        recording.stop();
        recording.dump(jfrOut);
      }
      long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

      TraceModel model = JfrParser.parse(jfrOut);
      assertThat(model.scopes()).as("exactly one scope recorded").hasSize(1);
      ScopeRecord scope = model.scopes().get(0);
      assertThat(scope.name()).isEqualTo(SCOPE);
      assertThat(scope.tasks()).as("all tasks recorded").hasSize(TASK_COUNT);
      assertThat(scope.tasks())
          .as("every task succeeded")
          .allSatisfy(t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class));

      long scopeDurationMs =
          java.time.Duration.between(scope.openTime(), scope.closeTime()).toMillis();
      assertThat(scopeDurationMs)
          .as("scope duration <= test wallclock")
          .isLessThanOrEqualTo(elapsedMs);
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }
}
