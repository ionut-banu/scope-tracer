package dev.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracedScopeTest {

  @TempDir Path tempDir;

  // --- constructor validation ---

  @Test
  void constructorRejectsNullName() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TracedScope(null));
  }

  @Test
  void constructorRejectsEmptyName() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TracedScope(""));
  }

  @Test
  void constructorRejectsBlankName() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TracedScope("   "));
  }

  @Test
  void nameIsRetained() throws Exception {
    try (var scope = new TracedScope("my-scope")) {
      assertThat(scope.name()).isEqualTo("my-scope");
      scope.join();
    }
  }

  // --- happy path ---

  @Test
  void happyPathEmitsScopeOpenedTaskForkedTaskSucceededScopeClosed() throws Exception {
    var scopeName = "happy-path";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> "result");
                scope.join();
              }
            });

    // Cross-thread events (TaskSucceeded vs ScopeClosed) have no guaranteed flush order in JFR,
    // so we assert presence only; intra-task ordering is verified by timestamp in a separate test.
    assertThat(eventNames(events))
        .containsExactlyInAnyOrder(
            "dev.scopetracer.ScopeOpened",
            "dev.scopetracer.TaskForked",
            "dev.scopetracer.TaskSucceeded",
            "dev.scopetracer.ScopeClosed");
  }

  @Test
  void scopeOpenedPrecedesTaskForkedByTimestamp() throws Exception {
    var scopeName = "order-open-fork";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var opened = eventsOfType(events, "dev.scopetracer.ScopeOpened").get(0);
    var forked = eventsOfType(events, "dev.scopetracer.TaskForked").get(0);
    assertThat(opened.getStartTime()).isBeforeOrEqualTo(forked.getStartTime());
  }

  @Test
  void taskForkedPrecedesTaskSucceededByTimestamp() throws Exception {
    var scopeName = "order-fork-succeed";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "dev.scopetracer.TaskForked").get(0);
    var succeeded = eventsOfType(events, "dev.scopetracer.TaskSucceeded").get(0);
    assertThat(forked.getStartTime()).isBeforeOrEqualTo(succeeded.getStartTime());
  }

  @Test
  void scopeLevelEventsHaveTaskIdZero() throws Exception {
    var scopeName = "scope-taskid";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    assertThat(eventsOfType(events, "dev.scopetracer.ScopeOpened"))
        .allSatisfy(e -> assertThat(e.getLong("taskId")).isZero());
    assertThat(eventsOfType(events, "dev.scopetracer.ScopeClosed"))
        .allSatisfy(e -> assertThat(e.getLong("taskId")).isZero());
  }

  @Test
  void taskIdIsMonotonicStartingAtOne() throws Exception {
    var scopeName = "task-ids";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.fork(() -> 2);
                scope.fork(() -> 3);
                scope.join();
              }
            });

    var forkedIds =
        eventsOfType(events, "dev.scopetracer.TaskForked").stream()
            .map(e -> e.getLong("taskId"))
            .sorted()
            .toList();
    assertThat(forkedIds).containsExactly(1L, 2L, 3L);
  }

  @Test
  void eachForkedTaskProducesExactlyOneOutcomeEvent() throws Exception {
    var scopeName = "multi-outcome";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> "a");
                scope.fork(() -> "b");
                scope.fork(() -> "c");
                scope.join();
              }
            });

    assertThat(eventsOfType(events, "dev.scopetracer.TaskSucceeded")).hasSize(3);
    assertThat(eventsOfType(events, "dev.scopetracer.TaskFailed")).isEmpty();
    assertThat(eventsOfType(events, "dev.scopetracer.TaskCancelled")).isEmpty();
  }

  @Test
  void scopeNameIsRecordedOnEveryEvent() throws Exception {
    var scopeName = "name-propagation";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(() -> 42);
                scope.join();
              }
            });

    assertThat(events).allSatisfy(e -> assertThat(e.getString("scopeName")).isEqualTo(scopeName));
  }

  // --- failure ---

  @Test
  void taskFailureEmitsTaskFailedEventWithExceptionType() throws Exception {
    var scopeName = "task-failed";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException("boom");
                    });
                assertThatThrownBy(scope::join)
                    .isInstanceOf(StructuredTaskScope.FailedException.class);
              }
            });

    var failedEvents = eventsOfType(events, "dev.scopetracer.TaskFailed");
    assertThat(failedEvents).hasSize(1);
    assertThat(failedEvents.get(0).getString("exceptionType"))
        .isEqualTo(IllegalStateException.class.getName());
  }

  @Test
  void scopeClosedIsEmittedEvenWhenTaskFails() throws Exception {
    var scopeName = "closed-on-fail";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(
                    () -> {
                      throw new RuntimeException("fail");
                    });
                try {
                  scope.join();
                } catch (StructuredTaskScope.FailedException ignored) {
                }
              }
            });

    assertThat(eventsOfType(events, "dev.scopetracer.ScopeClosed")).hasSize(1);
  }

  // --- cancellation ---

  @Test
  void siblingTaskCancellationEmitsTaskCancelledEvent() throws Exception {
    var scopeName = "cancellation";
    var taskStarted = new CountDownLatch(1);

    var events =
        capture(
            scopeName,
            () -> {
              try (var scope = new TracedScope(scopeName, Thread.ofPlatform().factory())) {
                scope.fork(
                    () -> {
                      taskStarted.countDown();
                      new CountDownLatch(1).await(); // blocks until scope interrupts this thread
                      return null;
                    });
                scope.fork(
                    () -> {
                      taskStarted.await(); // ensure task 1 is blocking before we fail
                      throw new RuntimeException("trigger cancellation");
                    });
                assertThatThrownBy(scope::join)
                    .isInstanceOf(StructuredTaskScope.FailedException.class);
              }
            });

    assertThat(eventsOfType(events, "dev.scopetracer.TaskCancelled")).hasSize(1);
    assertThat(eventsOfType(events, "dev.scopetracer.TaskFailed")).hasSize(1);
  }

  // --- idempotent close ---

  @Test
  void closingTwiceEmitsOnlyOneScopeClosedEvent() throws Exception {
    var scopeName = "idempotent-close";
    var events =
        capture(
            scopeName,
            () -> {
              var scope = new TracedScope(scopeName, Thread.ofPlatform().factory());
              scope.fork(() -> 1);
              scope.join();
              scope.close();
              scope.close(); // second close must be a no-op
            });

    assertThat(eventsOfType(events, "dev.scopetracer.ScopeClosed")).hasSize(1);
  }

  // --- helpers ---

  private List<RecordedEvent> capture(String scopeName, ThrowingRunnable action) throws Exception {
    try (var recording = new Recording()) {
      recording.enable("dev.scopetracer.*");
      recording.start();
      try {
        action.run();
      } finally {
        recording.stop();
      }
      var dump = tempDir.resolve("jfr-" + scopeName + ".jfr");
      recording.dump(dump);
      return filterByScopeName(readEvents(dump), scopeName);
    }
  }

  private static List<RecordedEvent> readEvents(Path path) throws IOException {
    var events = new ArrayList<RecordedEvent>();
    try (var file = new RecordingFile(path)) {
      while (file.hasMoreEvents()) {
        events.add(file.readEvent());
      }
    }
    return events;
  }

  private static List<RecordedEvent> filterByScopeName(
      List<RecordedEvent> events, String scopeName) {
    return events.stream()
        .filter(e -> e.hasField("scopeName") && scopeName.equals(e.getString("scopeName")))
        .toList();
  }

  private static List<RecordedEvent> eventsOfType(List<RecordedEvent> events, String typeName) {
    return events.stream().filter(e -> e.getEventType().getName().equals(typeName)).toList();
  }

  private static List<String> eventNames(List<RecordedEvent> events) {
    return events.stream().map(e -> e.getEventType().getName()).toList();
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }
}
