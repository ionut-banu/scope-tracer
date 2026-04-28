package dev.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TraceModel;
import dev.scopetracer.core.TracedScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrParserTest {

  @TempDir Path tempDir;

  // --- helpers ---

  private TraceModel capture(String label, ThrowingRunnable action) throws Exception {
    var jfr = tempDir.resolve(label + ".jfr");
    try (var recording = new Recording()) {
      recording.enable("dev.scopetracer.*");
      recording.start();
      try {
        action.run();
      } finally {
        recording.stop();
      }
      recording.dump(jfr);
    }
    return JfrParser.parse(jfr);
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  // --- single task success ---

  @Test
  void singleSuccessfulTask() throws Exception {
    var model =
        capture(
            "single-success",
            () -> {
              try (var scope = new TracedScope("single-success", Thread.ofPlatform().factory())) {
                scope.fork(() -> "result");
                scope.join();
              }
            });

    assertThat(model.scopes()).hasSize(1);
    var scope = model.scopes().get(0);
    assertThat(scope.name()).isEqualTo("single-success");
    assertThat(scope.openTime()).isNotNull();
    assertThat(scope.closeTime()).isNotNull();
    assertThat(scope.closeTime()).isAfterOrEqualTo(scope.openTime());

    assertThat(scope.tasks()).hasSize(1);
    var task = scope.tasks().get(0);
    assertThat(task.taskId()).isEqualTo(1L);
    assertThat(task.outcome()).isInstanceOf(TaskOutcome.Success.class);
    assertThat(task.completionTime()).isNotNull();
    assertThat(task.completionTime()).isAfterOrEqualTo(task.forkTime());
  }

  // --- multiple tasks all succeed ---

  @Test
  void threeSuccessfulTasksHaveMonotonicIds() throws Exception {
    var model =
        capture(
            "three-success",
            () -> {
              try (var scope = new TracedScope("three-success", Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.fork(() -> 2);
                scope.fork(() -> 3);
                scope.join();
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(3);
    assertThat(tasks.stream().map(t -> t.taskId()).sorted().toList()).containsExactly(1L, 2L, 3L);
    assertThat(tasks)
        .allSatisfy(t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class));
  }

  @Test
  void tasksAreSortedByForkTime() throws Exception {
    var model =
        capture(
            "fork-order",
            () -> {
              try (var scope = new TracedScope("fork-order", Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.fork(() -> 2);
                scope.fork(() -> 3);
                scope.join();
              }
            });

    var tasks = model.scopes().get(0).tasks();
    for (int i = 1; i < tasks.size(); i++) {
      assertThat(tasks.get(i).forkTime()).isAfterOrEqualTo(tasks.get(i - 1).forkTime());
    }
  }

  // --- task failure ---

  @Test
  void failedTaskHasCorrectExceptionType() throws Exception {
    var model =
        capture(
            "task-failed",
            () -> {
              try (var scope = new TracedScope("task-failed", Thread.ofPlatform().factory())) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException("boom");
                    });
                assertThatThrownBy(scope::join)
                    .isInstanceOf(StructuredTaskScope.FailedException.class);
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(1);
    var outcome = tasks.get(0).outcome();
    assertThat(outcome).isInstanceOf(TaskOutcome.Failed.class);
    assertThat(((TaskOutcome.Failed) outcome).exceptionType())
        .isEqualTo(IllegalStateException.class.getName());
  }

  // --- cancellation ---

  @Test
  void cancelledSiblingHasCancelledOutcome() throws Exception {
    var taskStarted = new CountDownLatch(1);
    var model =
        capture(
            "cancellation",
            () -> {
              try (var scope = new TracedScope("cancellation", Thread.ofPlatform().factory())) {
                scope.fork(
                    () -> {
                      taskStarted.countDown();
                      new CountDownLatch(1).await();
                      return null;
                    });
                scope.fork(
                    () -> {
                      taskStarted.await();
                      throw new RuntimeException("trigger");
                    });
                assertThatThrownBy(scope::join)
                    .isInstanceOf(StructuredTaskScope.FailedException.class);
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(2);
    var outcomes = tasks.stream().map(t -> t.outcome()).toList();
    assertThat(outcomes).anySatisfy(o -> assertThat(o).isInstanceOf(TaskOutcome.Failed.class));
    assertThat(outcomes).anySatisfy(o -> assertThat(o).isInstanceOf(TaskOutcome.Cancelled.class));
  }

  // --- multiple scopes in one recording ---

  @Test
  void multipleScopesAreSortedByOpenTime() throws Exception {
    var model =
        capture(
            "multi-scope",
            () -> {
              try (var s1 = new TracedScope("scope-alpha", Thread.ofPlatform().factory())) {
                s1.fork(() -> "a");
                s1.join();
              }
              try (var s2 = new TracedScope("scope-beta", Thread.ofPlatform().factory())) {
                s2.fork(() -> "b");
                s2.join();
              }
            });

    assertThat(model.scopes()).hasSize(2);
    assertThat(model.scopes().get(0).name()).isEqualTo("scope-alpha");
    assertThat(model.scopes().get(1).name()).isEqualTo("scope-beta");
    assertThat(model.scopes().get(0).openTime())
        .isBeforeOrEqualTo(model.scopes().get(1).openTime());
  }

  // --- scope record fields ---

  @Test
  void scopeRecordOwnerThreadNameIsPopulated() throws Exception {
    var model =
        capture(
            "owner-thread",
            () -> {
              try (var scope = new TracedScope("owner-thread", Thread.ofPlatform().factory())) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    assertThat(model.scopes().get(0).ownerThreadName()).isNotBlank();
  }

  @Test
  void taskForkTimeIsWithinScopeWindow() throws Exception {
    var model =
        capture(
            "time-bounds",
            () -> {
              try (var scope = new TracedScope("time-bounds", Thread.ofPlatform().factory())) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var scope = model.scopes().get(0);
    for (var task : scope.tasks()) {
      assertThat(task.forkTime()).isAfterOrEqualTo(scope.openTime());
      if (scope.closeTime() != null) {
        assertThat(task.forkTime()).isBeforeOrEqualTo(scope.closeTime());
      }
    }
  }

  // --- nesting detection ---

  @Test
  void nestedScopeDetectsParentRef() throws Exception {
    var model =
        capture(
            "nested-scope-detection",
            () -> {
              try (var outer = new TracedScope("order-processing", Thread.ofPlatform().factory())) {
                outer.fork(
                    () -> {
                      try (var inner =
                          new TracedScope("payment-steps", Thread.ofPlatform().factory())) {
                        inner.fork(() -> "authorise");
                        inner.join();
                      }
                      return "payment-done";
                    });
                outer.join();
              }
            });

    var paymentSteps =
        model.scopes().stream()
            .filter(s -> s.name().equals("payment-steps"))
            .findFirst()
            .orElseThrow();

    assertThat(paymentSteps.parent()).isNotNull();
    assertThat(paymentSteps.parent().scopeName()).isEqualTo("order-processing");
  }

  // --- empty recording ---

  @Test
  void emptyRecordingProducesEmptyModel() throws IOException {
    var jfr = tempDir.resolve("empty.jfr");
    try (var recording = new Recording()) {
      recording.start();
      recording.stop();
      recording.dump(jfr);
    }
    var model = JfrParser.parse(jfr);
    assertThat(model.scopes()).isEmpty();
  }
}
