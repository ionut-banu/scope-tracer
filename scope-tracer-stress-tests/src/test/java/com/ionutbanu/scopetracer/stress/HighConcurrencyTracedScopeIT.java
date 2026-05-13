package com.ionutbanu.scopetracer.stress;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.core.TracedScope;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives many concurrent {@link TracedScope}s, each forking many subtasks, and asserts that the
 * recorded JFR stream contains exactly one open/close per scope and one terminal event per task —
 * i.e. no event loss under load.
 *
 * <p>Tuned by system properties:
 *
 * <ul>
 *   <li>{@code stress.scopes} — number of inner scopes (default 100)
 *   <li>{@code stress.tasksPerScope} — tasks forked per inner scope (default 20)
 * </ul>
 */
class HighConcurrencyTracedScopeIT {

  private static final int SCOPES = Integer.getInteger("stress.scopes", 100);
  private static final int TASKS_PER_SCOPE = Integer.getInteger("stress.tasksPerScope", 20);
  private static final String INNER_PREFIX = "stress-inner-";
  private static final String OUTER = "stress-outer";

  @Test
  @Timeout(120)
  void noEventLossUnderConcurrentLoad() throws Exception {
    Path jfrOut = Files.createTempFile("stress-traced-scope-", ".jfr");
    try {
      try (var recording = new Recording()) {
        recording.enable("com.ionutbanu.scopetracer.ScopeOpened");
        recording.enable("com.ionutbanu.scopetracer.ScopeClosed");
        recording.enable("com.ionutbanu.scopetracer.TaskForked");
        recording.enable("com.ionutbanu.scopetracer.TaskSucceeded");
        recording.enable("com.ionutbanu.scopetracer.TaskFailed");
        recording.enable("com.ionutbanu.scopetracer.TaskCancelled");
        recording.setToDisk(true);
        recording.setMaxSize(128L * 1024 * 1024);
        recording.start();

        runLoad();

        recording.stop();
        recording.dump(jfrOut);
      }

      long opened = countEvents(jfrOut, "ScopeOpened");
      long closed = countEvents(jfrOut, "ScopeClosed");
      long forked = countEvents(jfrOut, "TaskForked");
      long succeeded = countEvents(jfrOut, "TaskSucceeded");

      int expectedScopes = SCOPES + 1;
      int expectedTasks = SCOPES + (SCOPES * TASKS_PER_SCOPE);

      assertThat(opened).as("ScopeOpened events").isEqualTo(expectedScopes);
      assertThat(closed).as("ScopeClosed events").isEqualTo(expectedScopes);
      assertThat(forked).as("TaskForked events").isEqualTo(expectedTasks);
      assertThat(succeeded).as("TaskSucceeded events").isEqualTo(expectedTasks);

      TraceModel model = JfrParser.parse(jfrOut);
      assertThat(model.scopes()).as("parsed scope records").hasSize(expectedScopes);
      assertThat(model.scopes())
          .as("every task in every scope succeeded")
          .allSatisfy(
              (ScopeRecord s) ->
                  assertThat(s.tasks())
                      .allSatisfy(
                          t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class)));
    } finally {
      Files.deleteIfExists(jfrOut);
    }
  }

  private static void runLoad() throws Exception {
    try (var outer = TracedScope.open(OUTER)) {
      for (int i = 0; i < SCOPES; i++) {
        final int scopeIndex = i;
        outer.fork(
            () -> {
              try (var inner = TracedScope.open(INNER_PREFIX + scopeIndex)) {
                for (int t = 0; t < TASKS_PER_SCOPE; t++) {
                  inner.fork(() -> 1);
                }
                inner.join();
              }
              return null;
            });
      }
      outer.join();
    }
  }

  private static long countEvents(Path jfrFile, String simpleEventName) throws Exception {
    long count = 0;
    try (var file = new RecordingFile(jfrFile)) {
      while (file.hasMoreEvents()) {
        var ev = file.readEvent();
        if (ev.getEventType().getName().endsWith("." + simpleEventName)) {
          count++;
        }
      }
    }
    return count;
  }
}
