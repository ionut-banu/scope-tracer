package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ionutbanu.scopetracer.analyzer.model.CallSite;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.core.TracedScope;
import com.ionutbanu.scopetracer.core.events.ScopeClosedEvent;
import com.ionutbanu.scopetracer.core.events.ScopeOpenedEvent;
import com.ionutbanu.scopetracer.core.events.TaskForkedEvent;
import com.ionutbanu.scopetracer.core.events.TaskSucceededEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrParserTest {

  @TempDir Path tempDir;

  // --- helpers ---

  private TraceModel capture(String label, ThrowingRunnable action) throws Exception {
    var jfr = tempDir.resolve(label + ".jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
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
              try (var scope =
                  TracedScope.open(
                      "single-success",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      "three-success",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      "fork-order", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      "task-failed", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException("boom");
                    });
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(1);
    var outcome = tasks.get(0).outcome();
    assertThat(outcome).isInstanceOf(TaskOutcome.Failed.class);
    var failed = (TaskOutcome.Failed) outcome;
    assertThat(failed.exceptionType()).isEqualTo(IllegalStateException.class.getName());
    assertThat(failed.exceptionMessage()).isEqualTo("boom");
    assertThat(failed.stackTrace()).isNotBlank();
  }

  @Test
  void stackTraceIsPreservedThroughParser() throws Exception {
    var model =
        capture(
            "stack-trace-round-trip",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "stack-trace-round-trip",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException("trace-test");
                    });
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    var outcome = (TaskOutcome.Failed) model.scopes().get(0).tasks().get(0).outcome();
    assertThat(outcome.stackTrace()).isNotBlank();
    assertThat(outcome.stackTrace()).contains("IllegalStateException");
    assertThat(outcome.stackTrace()).doesNotContain("(truncated)");
  }

  // --- cancellation ---

  @Test
  void cancelledSiblingHasCancelledOutcome() throws Exception {
    var taskStarted = new CountDownLatch(1);
    var model =
        capture(
            "cancellation",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "cancellation",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
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
              try (var s1 =
                  TracedScope.open(
                      "scope-alpha", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                s1.fork(() -> "a");
                s1.join();
              }
              try (var s2 =
                  TracedScope.open(
                      "scope-beta", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      "owner-thread",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      "time-bounds", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var outer =
                  TracedScope.open(
                      "order-processing",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                outer.fork(
                    () -> {
                      try (var inner =
                          TracedScope.open(
                              "payment-steps",
                              cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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

  // --- two-level nesting ---

  /**
   * Exercises {@code detectNesting} with three levels of scope containment (grandparent → parent →
   * child). Each level must resolve its direct parent only — not any ancestor further up the chain.
   */
  @Test
  void twoLevelNestedScopeDetectsGrandparentRef() throws Exception {
    var model =
        capture(
            "two-level-nesting",
            () -> {
              try (var grandparent =
                  TracedScope.open(
                      "grandparent-scope",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                grandparent.fork(
                    () -> {
                      try (var parent =
                          TracedScope.open(
                              "parent-scope",
                              cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                        parent.fork(
                            () -> {
                              try (var child =
                                  TracedScope.open(
                                      "child-scope",
                                      cfg ->
                                          cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                                child.fork(() -> "leaf");
                                child.join();
                              }
                              return "child-done";
                            });
                        parent.join();
                      }
                      return "parent-done";
                    });
                grandparent.join();
              }
            });

    var parentScope =
        model.scopes().stream()
            .filter(s -> s.name().equals("parent-scope"))
            .findFirst()
            .orElseThrow();
    var childScope =
        model.scopes().stream()
            .filter(s -> s.name().equals("child-scope"))
            .findFirst()
            .orElseThrow();

    assertThat(parentScope.parent()).isNotNull();
    assertThat(parentScope.parent().scopeName()).isEqualTo("grandparent-scope");
    assertThat(childScope.parent()).isNotNull();
    assertThat(childScope.parent().scopeName()).isEqualTo("parent-scope");
  }

  // --- concurrent same-name scope isolation ---

  /**
   * Two same-named scopes running simultaneously on separate threads must not be erroneously linked
   * as parent/child. {@code detectNesting} uses thread-ID + time containment; concurrent scopes on
   * different threads with non-overlapping thread IDs must both have {@code null} parent.
   */
  @Test
  void concurrentSameNameScopesAreNotMutuallyAssignedAsParent() throws Exception {
    var model =
        capture(
            "concurrent-same-name",
            () -> {
              var ready = new CountDownLatch(2);
              var release = new CountDownLatch(1);
              var t1 =
                  Thread.ofPlatform()
                      .start(
                          () -> {
                            try {
                              ready.countDown();
                              release.await();
                              try (var scope =
                                  TracedScope.open(
                                      "parallel-scope",
                                      cfg ->
                                          cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                                scope.fork(() -> "t1");
                                scope.join();
                              }
                            } catch (Exception e) {
                              throw new RuntimeException(e);
                            }
                          });
              var t2 =
                  Thread.ofPlatform()
                      .start(
                          () -> {
                            try {
                              ready.countDown();
                              release.await();
                              try (var scope =
                                  TracedScope.open(
                                      "parallel-scope",
                                      cfg ->
                                          cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                                scope.fork(() -> "t2");
                                scope.join();
                              }
                            } catch (Exception e) {
                              throw new RuntimeException(e);
                            }
                          });
              ready.await();
              release.countDown();
              t1.join();
              t2.join();
            });

    var records = model.scopes().stream().filter(s -> "parallel-scope".equals(s.name())).toList();
    assertThat(records).hasSize(2);
    // Concurrent scopes on separate threads must not be linked as parent/child.
    assertThat(records).allSatisfy(s -> assertThat(s.parent()).isNull());
  }

  // --- scopeId uniqueness for same-named scopes ---

  /**
   * Regression test: two sequential scopes with identical names must produce two distinct
   * ScopeRecords with different scopeIds. Before the scopeId fix, name-based keying collapsed them.
   */
  @Test
  void twoScopesWithSameNameHaveDistinctScopeIds() throws Exception {
    var model =
        capture(
            "same-name-scopes",
            () -> {
              try (var s1 =
                  TracedScope.open(
                      "duplicate-name",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                s1.fork(() -> "first");
                s1.join();
              }
              try (var s2 =
                  TracedScope.open(
                      "duplicate-name",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                s2.fork(() -> "second");
                s2.join();
              }
            });

    var records = model.scopes().stream().filter(s -> "duplicate-name".equals(s.name())).toList();
    assertThat(records).hasSize(2);
    assertThat(records.get(0).scopeId()).isNotEqualTo(records.get(1).scopeId());
    assertThat(records).allSatisfy(s -> assertThat(s.tasks()).hasSize(1));
    assertThat(records)
        .allSatisfy(
            s -> assertThat(s.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class));
  }

  /**
   * A configuration function's {@code withName} names the underlying scope only: the parsed scope
   * is named by the {@code name} argument, which every event carries as {@code scopeName}.
   */
  @Test
  void configFunctionWithNameDoesNotRenameTheParsedScope() throws Exception {
    var model =
        capture(
            "config-with-name",
            () -> {
              try (var scope =
                  TracedScope.open("the-name-argument", cfg -> cfg.withName("other-name"))) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    assertThat(model.scopes()).noneMatch(s -> "other-name".equals(s.name()));
    assertThat(model.scopes().stream().filter(s -> "the-name-argument".equals(s.name())))
        .singleElement()
        .satisfies(s -> assertThat(s.tasks()).hasSize(1));
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

  // --- out-of-order JFR event buffering (pendingCompletions / pendingCloses) ---

  /**
   * JFR flushes virtual-thread buffers independently; task-completion events can appear in the file
   * before {@code ScopeOpened}. The parser buffers such events and applies them once the open event
   * is seen. This test uses virtual threads with many concurrent tasks — where such reordering is
   * empirically likely — and verifies the reconstructed model is always correct.
   */
  @Test
  void virtualThreadScopeWithManyConcurrentTasksProducesCorrectModel() throws Exception {
    var model =
        capture(
            "vt-concurrent",
            () -> {
              // Default factory is virtual threads — JFR buffer flush ordering is not guaranteed.
              try (var scope = TracedScope.open("vt-concurrent")) {
                for (int i = 0; i < 20; i++) {
                  final int id = i;
                  scope.fork(() -> id * id);
                }
                scope.join();
              }
            });

    assertThat(model.scopes()).hasSize(1);
    var scope = model.scopes().get(0);
    assertThat(scope.tasks()).hasSize(20);
    assertThat(scope.closeTime()).isNotNull();
    assertThat(scope.tasks())
        .allSatisfy(t -> assertThat(t.outcome()).isInstanceOf(TaskOutcome.Success.class))
        .allSatisfy(t -> assertThat(t.completionTime()).isNotNull());
  }

  // --- truncated recording ---

  /**
   * When a recording is stopped while a task is still running, the file contains {@code
   * ScopeOpened} and {@code TaskForked} but no completion or close events. The parser must produce
   * a {@code ScopeRecord} with {@code null} {@code closeTime} and a {@code TaskRecord} with {@code
   * null} {@code completionTime} and {@code null} {@code outcome}.
   */
  @Test
  void truncatedRecordingYieldsNullCompletionTimeAndOutcome() throws Exception {
    var jfr = tempDir.resolve("truncated.jfr");
    var taskStarted = new CountDownLatch(1);
    var unblock = new CountDownLatch(1);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      // fork() must be called from the owner thread — the task callable runs concurrently.
      var scope =
          TracedScope.open(
              "truncated-scope", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()));
      scope.fork(
          () -> {
            taskStarted.countDown();
            unblock.await(); // blocks until we let it go
            return "done";
          });

      taskStarted.await(); // task is running; TaskForked emitted, no completion yet

      // Dump while the task is still blocked — TaskSucceeded and ScopeClosed are NOT in the file.
      recording.stop();
      recording.dump(jfr);

      // Clean up: unblock task, let scope close normally (events go nowhere, recording is stopped).
      unblock.countDown();
      scope.join();
      scope.close();
    }

    var model = JfrParser.parse(jfr);
    assertThat(model.scopes()).hasSize(1);
    var scope = model.scopes().get(0);
    assertThat(scope.name()).isEqualTo("truncated-scope");
    assertThat(scope.closeTime()).isNull();
    assertThat(scope.tasks()).hasSize(1);
    assertThat(scope.tasks().get(0).completionTime()).isNull();
    assertThat(scope.tasks().get(0).outcome()).isNull();
  }

  // --- null exceptionMessage round-trip ---

  /**
   * When a task throws an exception with no message ({@code getMessage()} returns {@code null}),
   * {@code TaskFailedEvent.exceptionMessage} is {@code null} in the recording. The parser must
   * preserve this as {@code null} in {@code TaskOutcome.Failed.exceptionMessage()} — not convert it
   * to an empty string or a default value.
   */
  @Test
  void nullExceptionMessageIsPreservedThroughParser() throws Exception {
    var model =
        capture(
            "null-ex-message",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "null-ex-message",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException(); // no message — getMessage() returns null
                    });
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(1);
    var outcome = (TaskOutcome.Failed) tasks.get(0).outcome();
    assertThat(outcome.exceptionType()).isEqualTo(IllegalStateException.class.getName());
    assertThat(outcome.exceptionMessage()).isNull();
  }

  // --- taskName round-trip ---

  /**
   * Explicit task names from {@link TracedScope#fork(String, java.util.concurrent.Callable)} round-
   * trip through the JFR file into {@link
   * com.ionutbanu.scopetracer.analyzer.model.TaskRecord#taskName()}.
   */
  @Test
  void explicitTaskNameSurvivesRoundTrip() throws Exception {
    var model =
        capture(
            "task-name-roundtrip",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "task-name-roundtrip",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork("findUser", () -> 1);
                scope.fork("loadCart", () -> 2);
                scope.join();
              }
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks).hasSize(2);
    assertThat(tasks.stream().map(t -> t.taskName()).sorted().toList())
        .containsExactly("findUser", "loadCart");
  }

  /** Auto-derived task names also survive the round-trip and are non-blank for lambdas. */
  @Test
  void autoDerivedTaskNameSurvivesRoundTrip() throws Exception {
    var model =
        capture(
            "task-name-auto",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "task-name-auto",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    var task = model.scopes().get(0).tasks().get(0);
    // Caller-frame fallback for lambdas — must reference this test class.
    assertThat(task.taskName()).startsWith("JfrParserTest#");
  }

  // --- callSite round-trip ---

  /**
   * The fix this feature delivers: call-site data is captured even when an explicit label is
   * supplied to {@link TracedScope#fork(String, java.util.concurrent.Callable)}.
   */
  @Test
  void explicitlyNamedForkStillCapturesCallSite() throws Exception {
    var model =
        capture(
            "explicit-name-callsite",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "explicit-name-callsite",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork("findUser", () -> 1);
                scope.join();
              }
            });

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.taskName()).isEqualTo("findUser");
    assertThat(task.callSite()).isNotNull();
    assertThat(task.callSite().className()).isEqualTo(JfrParserTest.class.getName());
    assertThat(task.callSite().methodName()).isEqualTo("explicitlyNamedForkStillCapturesCallSite");
    assertThat(task.callSite().line()).isPositive();
  }

  /** Auto-derived forks also populate {@code callSite}. */
  @Test
  void autoDerivedForkCapturesCallSite() throws Exception {
    var model =
        capture(
            "auto-derived-callsite",
            () -> {
              try (var scope =
                  TracedScope.open(
                      "auto-derived-callsite",
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.callSite()).isNotNull();
    assertThat(task.callSite().className()).isEqualTo(JfrParserTest.class.getName());
    assertThat(task.callSite().methodName()).isEqualTo("autoDerivedForkCapturesCallSite");
    assertThat(task.callSite().line()).isPositive();
  }

  // --- callSite from hand-built events: what the agent records, before and after #86 ---

  /**
   * An agent recording now carries the call site, stamped by {@code ForkAdvice} as {@code
   * TracedScope} does: binary class name, method and line for a lambda, class only for a named
   * {@code Callable}.
   */
  @Test
  void agentShapedForksGiveTheirCallSites() throws Exception {
    var model =
        capture(
            "agent-shaped",
            () -> {
              opened(1, "Traced#main");
              forked(1, "Traced#main", 1, "Traced#main:7", "com.acme.Traced", "main", 7);
              forked(1, "Traced#main", 2, "Named", "com.acme.Plain$Named", null, 0);
              succeeded(1, "Traced#main", 1);
              succeeded(1, "Traced#main", 2);
              closed(1, "Traced#main");
            });

    var tasks = model.scopes().get(0).tasks();
    assertThat(tasks)
        .extracting(TaskRecord::callSite)
        .containsExactlyInAnyOrder(
            new CallSite("com.acme.Traced", "main", 7),
            new CallSite("com.acme.Plain$Named", null, null));
  }

  /**
   * A recording made by the agent before #86: the {@code callSite*} fields exist (they belong to
   * core's event) but were never set, so {@code hasField} is true and the values are null / 0. The
   * task has no call site and keeps its {@code taskName}, which the plugin falls back to.
   */
  @Test
  void agentForkFromBeforeTheCallSiteFieldsWereStampedHasNoCallSite() throws Exception {
    var model =
        capture(
            "agent-before",
            () -> {
              opened(1, "Traced#main");
              forked(1, "Traced#main", 1, "Traced#main:7", null, null, 0);
              succeeded(1, "Traced#main", 1);
              closed(1, "Traced#main");
            });

    var task = model.scopes().get(0).tasks().get(0);
    assertThat(task.callSite()).isNull();
    assertThat(task.taskName()).isEqualTo("Traced#main:7");
    assertThat(task.outcome()).isInstanceOf(TaskOutcome.Success.class);
  }

  /**
   * A task whose {@code TaskForked} JFR dropped is handled as before: the model is built from the
   * forks, so it is not in the model and there is no call site to show for it.
   */
  @Test
  void taskWithoutForkEventIsLeftOutAsBefore() throws Exception {
    var model =
        capture(
            "fork-dropped",
            () -> {
              opened(1, "s");
              forked(1, "s", 1, "Traced#main:7", "com.acme.Traced", "main", 7);
              succeeded(1, "s", 1);
              succeeded(1, "s", 2);
              closed(1, "s");
            });

    assertThat(model.scopes().get(0).tasks())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.taskId()).isEqualTo(1L);
              assertThat(t.callSite()).isEqualTo(new CallSite("com.acme.Traced", "main", 7));
            });
  }

  private static void opened(long scopeId, String name) {
    var e = new ScopeOpenedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = 0;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  /** A {@code TaskForked} as {@code ForkAdvice} commits it. */
  private static void forked(
      long scopeId,
      String name,
      long taskId,
      String taskName,
      String callSiteClass,
      String callSiteMethod,
      int callSiteLine) {
    var e = new TaskForkedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.taskName = taskName;
    e.threadName = Thread.currentThread().getName();
    e.callSiteClassName = callSiteClass;
    e.callSiteMethodName = callSiteMethod;
    e.callSiteLine = callSiteLine;
    e.commit();
  }

  private static void succeeded(long scopeId, String name, long taskId) {
    var e = new TaskSucceededEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  private static void closed(long scopeId, String name) {
    var e = new ScopeClosedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = 0;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }
}
