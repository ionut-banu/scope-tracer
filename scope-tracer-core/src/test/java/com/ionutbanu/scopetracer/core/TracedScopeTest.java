package com.ionutbanu.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Configuration;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracedScopeTest {

  private static final String SELF = "com.ionutbanu.scopetracer.core.TracedScopeTest";

  @TempDir Path tempDir;

  // --- factory validation ---

  /** Every factory form rejects a null name before anything is opened or emitted. */
  @Test
  void everyFactoryRejectsNullName() {
    assertEveryFactoryRejectsName(null);
  }

  @Test
  void everyFactoryRejectsEmptyName() {
    assertEveryFactoryRejectsName("");
  }

  @Test
  void everyFactoryRejectsBlankName() {
    assertEveryFactoryRejectsName("   ");
  }

  private static void assertEveryFactoryRejectsName(String name) {
    assertThatIllegalArgumentException().isThrownBy(() -> TracedScope.open(name));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TracedScope.open(name, Joiner.awaitAllSuccessfulOrThrow()));
    assertThatIllegalArgumentException().isThrownBy(() -> TracedScope.open(name, cfg -> cfg));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> TracedScope.open(name, Joiner.awaitAllSuccessfulOrThrow(), cfg -> cfg));
  }

  @Test
  void openFactoryRejectsNullName() {
    assertThatIllegalArgumentException().isThrownBy(() -> TracedScope.open(null));
  }

  @Test
  void nameIsRetained() throws Exception {
    try (var scope = TracedScope.open("my-scope")) {
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
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "result");
                scope.join();
              }
            });

    // Cross-thread events (TaskSucceeded vs ScopeClosed) have no guaranteed flush order in JFR,
    // so we assert presence only; intra-task ordering is verified by timestamp in a separate test.
    assertThat(eventNames(events))
        .containsExactlyInAnyOrder(
            "com.ionutbanu.scopetracer.ScopeOpened",
            "com.ionutbanu.scopetracer.TaskForked",
            "com.ionutbanu.scopetracer.TaskSucceeded",
            "com.ionutbanu.scopetracer.ScopeClosed");
  }

  @Test
  void scopeOpenedPrecedesTaskForkedByTimestamp() throws Exception {
    var scopeName = "order-open-fork";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var opened = eventsOfType(events, "com.ionutbanu.scopetracer.ScopeOpened").get(0);
    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked").get(0);
    assertThat(opened.getStartTime()).isBeforeOrEqualTo(forked.getStartTime());
  }

  @Test
  void taskForkedPrecedesTaskSucceededByTimestamp() throws Exception {
    var scopeName = "order-fork-succeed";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked").get(0);
    var succeeded = eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded").get(0);
    assertThat(forked.getStartTime()).isBeforeOrEqualTo(succeeded.getStartTime());
  }

  @Test
  void scopeLevelEventsHaveTaskIdZero() throws Exception {
    var scopeName = "scope-taskid";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> 1);
                scope.join();
              }
            });

    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeOpened"))
        .allSatisfy(e -> assertThat(e.getLong("taskId")).isZero());
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeClosed"))
        .allSatisfy(e -> assertThat(e.getLong("taskId")).isZero());
  }

  @Test
  void taskIdIsMonotonicStartingAtOne() throws Exception {
    var scopeName = "task-ids";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> 1);
                scope.fork(() -> 2);
                scope.fork(() -> 3);
                scope.join();
              }
            });

    var forkedIds =
        eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked").stream()
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
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "a");
                scope.fork(() -> "b");
                scope.fork(() -> "c");
                scope.join();
              }
            });

    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded")).hasSize(3);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed")).isEmpty();
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).isEmpty();
  }

  @Test
  void scopeNameIsRecordedOnEveryEvent() throws Exception {
    var scopeName = "name-propagation";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException("boom");
                    });
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    var failedEvents = eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed");
    assertThat(failedEvents).hasSize(1);
    assertThat(failedEvents.get(0).getString("exceptionType"))
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(failedEvents.get(0).getString("exceptionMessage")).isEqualTo("boom");
    assertThat(failedEvents.get(0).getString("exceptionStackTrace"))
        .contains("IllegalStateException");
  }

  @Test
  void taskFailureWithNoMessageEmitsNullExceptionMessage() throws Exception {
    var scopeName = "task-failed-no-message";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new IllegalStateException(); // no message
                    });
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    var failedEvents = eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed");
    assertThat(failedEvents).hasSize(1);
    assertThat(failedEvents.get(0).getString("exceptionMessage")).isNull();
  }

  @Test
  void scopeClosedIsEmittedEvenWhenTaskFails() throws Exception {
    var scopeName = "closed-on-fail";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(
                    () -> {
                      throw new RuntimeException("fail");
                    });
                try {
                  scope.join();
                } catch (ExecutionException ignored) {
                }
              }
            });

    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeClosed")).hasSize(1);
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
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
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
                assertThatThrownBy(scope::join).isInstanceOf(ExecutionException.class);
              }
            });

    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).hasSize(1);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed")).hasSize(1);
  }

  // --- tracing never changes behaviour ---

  /**
   * Spec §1: a traced scope throws what a plain {@link StructuredTaskScope} running the same tasks
   * throws. On JDK 27 the default joiner's {@code join()} throws {@link ExecutionException} with
   * the failed task's exception as its cause.
   */
  @Test
  void defaultJoinerFailureThrowsTheSameExceptionAsAPlainScope() throws Exception {
    var failure = new IllegalStateException("boom");

    Throwable plain;
    try (var scope =
        StructuredTaskScope.open(
            Joiner.<Object>awaitAllSuccessfulOrThrow(),
            cf -> cf.withThreadFactory(Thread.ofPlatform().factory()))) {
      scope.fork(() -> "ok");
      scope.fork(
          () -> {
            throw failure;
          });
      plain = catchJoin(scope::join);
    }

    Throwable traced;
    try (var scope =
        TracedScope.open(
            "same-exception", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
      scope.fork(() -> "ok");
      scope.fork(
          () -> {
            throw failure;
          });
      traced = catchJoin(scope::join);
    }

    assertThat(plain).isInstanceOf(ExecutionException.class).hasCause(failure);
    assertThat(traced).isNotNull().hasSameClassAs(plain);
    assertThat(traced.getCause()).isSameAs(plain.getCause());
  }

  private static Throwable catchJoin(ThrowingRunnable join) {
    try {
      join.run();
      return null;
    } catch (Exception e) {
      return e;
    }
  }

  // --- idempotent close ---

  @Test
  void closingTwiceEmitsOnlyOneScopeClosedEvent() throws Exception {
    var scopeName = "idempotent-close";
    var events =
        capture(
            scopeName,
            () -> {
              var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()));
              scope.fork(() -> 1);
              scope.join();
              scope.close();
              scope.close(); // second close must be a no-op
            });

    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeClosed")).hasSize(1);
  }

  // --- custom joiner: racing tasks ---

  /**
   * With {@code anySuccessfulResultOrThrow()}, the scope shuts down as soon as one task returns
   * normally. The other task observes the scope's shutdown signal (interrupt) and emits a {@code
   * TaskCancelled} event. {@link TracedScope#join()} returns the winner's result directly.
   */
  @Test
  void racingJoinerReturnsFirstSuccessAndCancelsLoser() throws Exception {
    var scopeName = "racing-joiner";
    // loserBlocking is counted down when the loser task is inside its indefinite await(),
    // guaranteeing the scope will interrupt a live thread when the winner returns.
    var loserBlocking = new CountDownLatch(1);
    Object[] resultHolder = new Object[1];

    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName,
                      Joiner.anySuccessfulOrThrow(),
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                // Loser parks first; winner waits for loser to be blocking before returning.
                scope.fork(
                    () -> {
                      loserBlocking.countDown();
                      new CountDownLatch(1).await(); // blocked until scope shuts down
                      return "loser";
                    });
                scope.fork(
                    () -> {
                      loserBlocking.await(); // ensure loser is live and blocked
                      return "winner";
                    });
                resultHolder[0] = scope.join(); // returns the winner's result
              }
            });

    assertThat(resultHolder[0]).isEqualTo("winner");
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded")).hasSize(1);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).hasSize(1);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeClosed")).hasSize(1);
  }

  /**
   * With {@code allUntil(subtask -> false)} (JDK 27's replacement for the removed {@code
   * awaitAll()}), the scope waits for all tasks regardless of outcome. A failed task emits {@code
   * TaskFailed} but does not cause {@link TracedScope#join()} to throw — the caller inspects the
   * returned subtasks directly. All events for both tasks must be present.
   */
  @Test
  void allUntilNeverJoinerCollectsAllOutcomes() throws Exception {
    var scopeName = "all-until-never-joiner";
    var states = new ArrayList<Subtask.State>();
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName,
                      Joiner.allUntil(subtask -> false),
                      cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "ok");
                scope.fork(
                    () -> {
                      throw new RuntimeException("partial failure");
                    });
                // does NOT throw even though one task failed
                for (var subtask : scope.join()) {
                  states.add(subtask.state());
                }
              }
            });

    assertThat(states).containsExactly(Subtask.State.SUCCESS, Subtask.State.FAILED);
    // Both tasks ran to completion (one success, one failure); no cancellations.
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded")).hasSize(1);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed")).hasSize(1);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).isEmpty();
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.ScopeClosed")).hasSize(1);
  }

  // --- scopeId consistency and uniqueness ---

  /**
   * Every JFR event emitted for a single scope must carry the same {@code scopeId} value. The
   * parser uses {@code scopeId} as its primary key to associate task events with their scope;
   * inconsistency here would silently corrupt the parsed model.
   */
  @Test
  void scopeIdIsConsistentAcrossAllEventsOfOneScope() throws Exception {
    var scopeName = "scopeid-consistency";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "x");
                scope.join();
              }
            });

    var scopeIds = events.stream().map(e -> e.getLong("scopeId")).distinct().toList();
    assertThat(scopeIds).as("all events for a single scope must carry the same scopeId").hasSize(1);
  }

  /**
   * The static {@code SCOPE_ID_COUNTER} is the primary parser key; two concurrent instances must
   * receive distinct ids regardless of scheduling order.
   */
  @Test
  void scopeIdIsDistinctAcrossConcurrentInstances() throws Exception {
    var jfr = tempDir.resolve("concurrent-scopes.jfr");
    int count = 8;
    var allReady = new CountDownLatch(count);
    var start = new CountDownLatch(1);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      // Launch N scopes concurrently, each on its own thread, all released at the same instant.
      var threads = new ArrayList<Thread>();
      for (int i = 0; i < count; i++) {
        final int idx = i;
        threads.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      allReady.countDown();
                      try {
                        start.await();
                        try (var scope =
                            TracedScope.open(
                                "concurrent-" + idx,
                                cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                          scope.fork(() -> idx);
                          scope.join();
                        }
                      } catch (Exception e) {
                        throw new RuntimeException(e);
                      }
                    }));
      }
      allReady.await(); // wait until all threads are ready
      start.countDown(); // release all at once
      for (var t : threads) t.join();

      recording.stop();
      recording.dump(jfr);
    }

    var scopeIds = new HashSet<Long>();
    try (var file = new RecordingFile(jfr)) {
      while (file.hasMoreEvents()) {
        var ev = file.readEvent();
        if ("com.ionutbanu.scopetracer.ScopeOpened".equals(ev.getEventType().getName())) {
          scopeIds.add(ev.getLong("scopeId"));
        }
      }
    }
    assertThat(scopeIds).hasSize(count);
  }

  // --- join() interruption ---

  /**
   * {@link TracedScope#join()} must propagate {@link InterruptedException} immediately when the
   * owner thread is interrupted, without swallowing or wrapping it.
   */
  @Test
  void joinThrowsInterruptedExceptionWhenOwnerIsInterrupted() throws Exception {
    var taskRunning = new CountDownLatch(1);
    var unblock = new CountDownLatch(1);

    try (var scope =
        TracedScope.open(
            "join-interrupted", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
      scope.fork(
          () -> {
            taskRunning.countDown();
            unblock.await(); // holds until we unblock after the assertion
            return null;
          });
      taskRunning.await();
      Thread.currentThread().interrupt(); // set interrupt flag before join
      assertThatThrownBy(scope::join).isInstanceOf(InterruptedException.class);
      Thread.interrupted(); // clear flag so close() + task cleanup work correctly
      unblock.countDown();
    }
  }

  // --- task naming ---

  /** Explicit name overload stamps the supplied label verbatim on the TaskForkedEvent. */
  @Test
  void forkWithExplicitNameStampsTaskName() throws Exception {
    var scopeName = "explicit-task-name";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork("findUser", () -> "u");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    assertThat(forked.get(0).getString("taskName")).isEqualTo("findUser");
  }

  /**
   * When the user-supplied {@link java.util.concurrent.Callable} is a real (non-lambda) class, the
   * deriver returns its simple class name.
   */
  @Test
  void forkWithCallableClassDerivesSimpleName() throws Exception {
    var scopeName = "callable-class-name";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(new NamedTask());
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    assertThat(forked.get(0).getString("taskName")).isEqualTo("NamedTask");
  }

  /**
   * For a lambda, the class-name shortcut returns null (synthetic class) and the deriver falls back
   * to the caller frame — formatted as {@code SimpleClass#method}. The test method name is what we
   * expect because the fork happens inline here.
   */
  @Test
  void forkWithLambdaDerivesCallerFrame() throws Exception {
    var scopeName = "lambda-caller-frame";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "v");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var name = forked.get(0).getString("taskName");
    // Caller frame — must reference the test class. Method may collapse to the enclosing test
    // method name (lambda$forkWithLambdaDerivesCallerFrame$N → forkWithLambdaDerivesCallerFrame).
    assertThat(name).startsWith("TracedScopeTest#");
  }

  /**
   * Null-safe: an explicit null taskName is allowed (the renderer falls back to the index).
   * Verifies the field is preserved as null on the event.
   */
  @Test
  void forkWithNullExplicitNameStampsNullTaskName() throws Exception {
    var scopeName = "null-task-name";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork((String) null, () -> "v");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    assertThat(forked.get(0).getString("taskName")).isNull();
  }

  /**
   * The whole point of this feature: an explicitly-named fork still gets its call site captured,
   * independently of the label. Before this change, callSite* fields didn't exist at all.
   */
  @Test
  void forkWithExplicitNameStillCapturesCallSite() throws Exception {
    var scopeName = "explicit-name-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork("findUser", () -> "u");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("taskName")).isEqualTo("findUser");
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF);
    assertThat(event.getString("callSiteMethodName"))
        .isEqualTo("forkWithExplicitNameStillCapturesCallSite");
    assertThat(event.getInt("callSiteLine")).isPositive();
  }

  /** Auto-derived forks populate the same callSite* fields as the explicit-name path. */
  @Test
  void forkWithLambdaAlsoCapturesCallSite() throws Exception {
    var scopeName = "lambda-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(() -> "v");
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF);
    assertThat(event.getString("callSiteMethodName"))
        .isEqualTo("forkWithLambdaAlsoCapturesCallSite");
    assertThat(event.getInt("callSiteLine")).isPositive();
  }

  /** The named-Callable-class tier has no single enclosing method or line. */
  @Test
  void forkWithCallableClassHasNoCallSiteMethodOrLine() throws Exception {
    var scopeName = "callable-class-callsite";
    var events =
        capture(
            scopeName,
            () -> {
              try (var scope =
                  TracedScope.open(
                      scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                scope.fork(new NamedTask());
                scope.join();
              }
            });

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF + "$NamedTask");
    assertThat(event.getString("callSiteMethodName")).isNull();
    assertThat(event.getInt("callSiteLine")).isZero();
    assertThat(event.getString("taskName")).isEqualTo("NamedTask");
  }

  /**
   * {@code callSiteClassName} is the binary name of the class that called {@code fork}, uncut: a
   * fork in a method of a nested class records {@code TracedScopeTest$NestedForker}, while {@code
   * taskName} keeps the outer simple name.
   */
  @Test
  void forkInsideNestedClassRecordsTheNestedBinaryName() throws Exception {
    var scopeName = "nested-class-callsite";
    var events = capture(scopeName, () -> NestedForker.forkOnce(scopeName));

    var forked = eventsOfType(events, "com.ionutbanu.scopetracer.TaskForked");
    assertThat(forked).hasSize(1);
    var event = forked.get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF + "$NestedForker");
    assertThat(event.getString("callSiteMethodName")).isEqualTo("forkOnce");
    assertThat(event.getInt("callSiteLine")).isPositive();
    assertThat(event.getString("taskName"))
        .isEqualTo("TracedScopeTest#forkOnce:" + event.getInt("callSiteLine"));
  }

  /** Opens a scope and forks one lambda from a method of a static nested class. */
  private static final class NestedForker {
    static void forkOnce(String scopeName) throws Exception {
      try (var scope =
          TracedScope.open(
              scopeName, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
        scope.fork(() -> "n");
        scope.join();
      }
    }
  }

  /** Non-lambda Callable used as test fixture for the class-name derivation tier. */
  private static final class NamedTask implements java.util.concurrent.Callable<String> {
    @Override
    public String call() {
      return "named";
    }
  }

  // --- the mirror rule: TracedScope's public API is StructuredTaskScope's (#95, #87, #80) ---

  private static final String OPENED = "com.ionutbanu.scopetracer.ScopeOpened";
  private static final String FORKED = "com.ionutbanu.scopetracer.TaskForked";
  private static final String SUCCEEDED = "com.ionutbanu.scopetracer.TaskSucceeded";
  private static final String FAILED = "com.ionutbanu.scopetracer.TaskFailed";
  private static final String CANCELLED = "com.ionutbanu.scopetracer.TaskCancelled";
  private static final String CLOSED = "com.ionutbanu.scopetracer.ScopeClosed";

  /**
   * The public instance methods {@code TracedScope} has that {@code StructuredTaskScope} does not:
   * the labelled fork and the name accessor. Anything else extra fails {@link
   * #publicApiMirrorsStructuredTaskScope()}.
   */
  private static final Set<String> TRACED_SCOPE_ADDITIONS =
      Set.of(
          "instance <U extends T> java.util.concurrent.StructuredTaskScope$Subtask<U>"
              + " fork(java.lang.String, java.util.concurrent.Callable<? extends U>)",
          "instance java.lang.String name()");

  @Test
  void hasNoPublicConstructorAndExactlyOnePrivateOne() {
    assertThat(TracedScope.class.getConstructors()).isEmpty();
    assertThat(TracedScope.class.getDeclaredConstructors())
        .singleElement()
        .satisfies(c -> assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
  }

  @Test
  void typeParametersAreStructuredTaskScopes() {
    assertThat(typeParameters(TracedScope.class.getTypeParameters()))
        .isEqualTo(typeParameters(StructuredTaskScope.class.getTypeParameters()))
        .isEqualTo("<T, R, R_X extends java.lang.Throwable>");
  }

  /**
   * Every public static method of {@code StructuredTaskScope} has exactly one {@code TracedScope}
   * counterpart with a leading {@code String} parameter and otherwise the same type parameters,
   * bounds, parameter types, return type (modulo the class) and exceptions; every public instance
   * method has an identical counterpart; and {@code TracedScope} has no other public method than
   * {@link #TRACED_SCOPE_ADDITIONS}. Read from the running JDK, so a JDK that adds or removes a
   * method fails this test.
   */
  @Test
  void publicApiMirrorsStructuredTaskScope() {
    assertThat(mirrorDifferences(TracedScope.class, TRACED_SCOPE_ADDITIONS)).isEmpty();
    // The factory list itself, so a reader sees what is compared.
    assertThat(publicSignatures(TracedScope.class))
        .filteredOn(s -> s.startsWith("static"))
        .containsExactlyInAnyOrder(
            "static <T, R, R_X extends java.lang.Throwable>"
                + " com.ionutbanu.scopetracer.core.TracedScope<T, R, R_X> open(java.lang.String,"
                + " java.util.concurrent.StructuredTaskScope$Joiner<? super T, ? extends R, R_X>,"
                + " java.util.function.UnaryOperator<java.util.concurrent.StructuredTaskScope"
                + "$Configuration>)",
            "static <T, R, R_X extends java.lang.Throwable>"
                + " com.ionutbanu.scopetracer.core.TracedScope<T, R, R_X> open(java.lang.String,"
                + " java.util.concurrent.StructuredTaskScope$Joiner<? super T, ? extends R, R_X>)",
            "static <T> com.ionutbanu.scopetracer.core.TracedScope<T, java.lang.Void,"
                + " java.util.concurrent.ExecutionException> open(java.lang.String,"
                + " java.util.function.UnaryOperator<java.util.concurrent.StructuredTaskScope"
                + "$Configuration>)",
            "static <T> com.ionutbanu.scopetracer.core.TracedScope<T, java.lang.Void,"
                + " java.util.concurrent.ExecutionException> open(java.lang.String)");
  }

  /** The mirror check itself fails on a missing and on an extra public method. */
  @Test
  void mirrorCheckReportsMissingAndExtraMethods() {
    assertThat(mirrorDifferences(NotAMirror.class, Set.of()))
        .contains(
            "missing: static <T> com.ionutbanu.scopetracer.core.TracedScopeTest$NotAMirror<T,"
                + " java.lang.Void, java.util.concurrent.ExecutionException>"
                + " open(java.lang.String)",
            "missing: instance boolean isCancelled()",
            "extra: instance void cancel()")
        .doesNotContain(
            "missing: instance <U extends T> java.util.concurrent.StructuredTaskScope$Subtask<U>"
                + " fork(java.lang.Runnable)");
  }

  /** Has {@code fork(Runnable)}, lacks everything else, and adds {@code cancel()}. */
  @SuppressWarnings("unused")
  public static final class NotAMirror<T, R, R_X extends Throwable> {
    public <U extends T> Subtask<U> fork(Runnable task) {
      throw new UnsupportedOperationException();
    }

    public void cancel() {}
  }

  private static List<String> mirrorDifferences(Class<?> candidate, Set<String> additions) {
    var expected = new HashSet<String>();
    for (var m : StructuredTaskScope.class.getMethods()) {
      if (!Modifier.isPublic(m.getModifiers())) continue;
      if (Modifier.isStatic(m.getModifiers())) {
        var params = new ArrayList<String>();
        params.add("java.lang.String");
        params.addAll(typeNames(m.getGenericParameterTypes()));
        var returnType =
            m.getGenericReturnType()
                .getTypeName()
                .replace("java.util.concurrent.StructuredTaskScope<", candidate.getName() + "<");
        expected.add(signature("static", m, returnType, params));
      } else {
        expected.add(signature(m));
      }
    }
    var actual = new HashSet<>(publicSignatures(candidate));
    var differences = new ArrayList<String>();
    for (var s : expected) {
      if (!actual.contains(s)) differences.add("missing: " + s);
    }
    for (var s : actual) {
      if (!expected.contains(s) && !additions.contains(s)) differences.add("extra: " + s);
    }
    return differences;
  }

  /** Every public method of {@code type}, static or instance, except those of {@link Object}. */
  private static List<String> publicSignatures(Class<?> type) {
    return Arrays.stream(type.getMethods())
        .filter(m -> m.getDeclaringClass() != Object.class)
        .map(TracedScopeTest::signature)
        .toList();
  }

  private static String signature(Method m) {
    return signature(
        Modifier.isStatic(m.getModifiers()) ? "static" : "instance",
        m,
        m.getGenericReturnType().getTypeName(),
        typeNames(m.getGenericParameterTypes()));
  }

  private static String signature(
      String kind, Method m, String returnType, List<String> parameterTypes) {
    var typeParameters = typeParameters(m.getTypeParameters());
    var exceptions = typeNames(m.getGenericExceptionTypes());
    return kind
        + (typeParameters.isEmpty() ? "" : " " + typeParameters)
        + " "
        + returnType
        + " "
        + m.getName()
        + "("
        + String.join(", ", parameterTypes)
        + ")"
        + (exceptions.isEmpty() ? "" : " throws " + String.join(", ", exceptions));
  }

  private static String typeParameters(TypeVariable<?>[] variables) {
    if (variables.length == 0) return "";
    return Arrays.stream(variables)
        .map(
            v -> {
              var bounds =
                  Arrays.stream(v.getBounds())
                      .filter(b -> b != Object.class)
                      .map(Type::getTypeName)
                      .toList();
              return bounds.isEmpty()
                  ? v.getName()
                  : v.getName() + " extends " + String.join(" & ", bounds);
            })
        .collect(Collectors.joining(", ", "<", ">"));
  }

  private static List<String> typeNames(Type[] types) {
    return Arrays.stream(types).map(Type::getTypeName).toList();
  }

  // --- every factory form behaves as a plain scope (spec §1), read off a recording (§4) ---

  @Test
  void openNameThrowsTheSameExceptionAsPlainOpen() throws Exception {
    var failure = new IllegalStateException("boom");
    Throwable plain;
    try (var scope = StructuredTaskScope.open()) {
      plain = joinFailingPair(scope::fork, scope::join, failure);
    }
    var name = "parity-open-name";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                traced[0] = joinFailingPair(scope::fork, scope::join, failure);
              }
            });
    assertSameFailure(plain, traced[0], ExecutionException.class, failure);
    assertFailingPairEvents(events);
  }

  @Test
  void openNameJoinerThrowsTheSameExceptionAsPlainOpenJoiner() throws Exception {
    var failure = new IllegalStateException("boom");
    Throwable plain;
    try (var scope = StructuredTaskScope.open(Joiner.<Object>allSuccessfulOrThrow())) {
      plain = joinFailingPair(scope::fork, scope::join, failure);
    }
    var name = "parity-open-name-joiner";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name, Joiner.<Object>allSuccessfulOrThrow())) {
                traced[0] = joinFailingPair(scope::fork, scope::join, failure);
              }
            });
    assertSameFailure(plain, traced[0], ExecutionException.class, failure);
    assertFailingPairEvents(events);
  }

  @Test
  void openNameConfigThrowsTheSameExceptionAsPlainOpenConfig() throws Exception {
    var failure = new IllegalStateException("boom");
    UnaryOperator<Configuration> platform =
        cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory());
    Throwable plain;
    try (var scope = StructuredTaskScope.open(platform)) {
      plain = joinFailingPair(scope::fork, scope::join, failure);
    }
    var name = "parity-open-name-config";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name, platform)) {
                traced[0] = joinFailingPair(scope::fork, scope::join, failure);
              }
            });
    assertSameFailure(plain, traced[0], ExecutionException.class, failure);
    assertFailingPairEvents(events);
  }

  @Test
  void openNameJoinerConfigThrowsTheSameExceptionAsPlainOpenJoinerConfig() throws Exception {
    var failure = new IllegalStateException("boom");
    UnaryOperator<Configuration> platform =
        cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory());
    Throwable plain;
    try (var scope =
        StructuredTaskScope.open(Joiner.<Object>awaitAllSuccessfulOrThrow(), platform)) {
      plain = joinFailingPair(scope::fork, scope::join, failure);
    }
    var name = "parity-open-name-joiner-config";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope =
                  TracedScope.open(name, Joiner.<Object>awaitAllSuccessfulOrThrow(), platform)) {
                traced[0] = joinFailingPair(scope::fork, scope::join, failure);
              }
            });
    assertSameFailure(plain, traced[0], ExecutionException.class, failure);
    assertFailingPairEvents(events);
  }

  /** A joiner with a mapped exception makes {@code R_X} that exception, as on a plain scope. */
  @Test
  void mappedExceptionJoinerThrowsTheSameIOExceptionAsAPlainScope() throws Exception {
    var failure = new IllegalStateException("boom");
    Throwable plain;
    try (var scope =
        StructuredTaskScope.open(
            Joiner.<Object, IOException>allSuccessfulOrThrow(IOException::new))) {
      plain = joinFailingPair(scope::fork, scope::join, failure);
    }
    var name = "parity-mapped-exception";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope =
                  TracedScope.open(
                      name, Joiner.<Object, IOException>allSuccessfulOrThrow(IOException::new))) {
                traced[0] = joinFailingPair(scope::fork, scope::join, failure);
              }
            });
    assertSameFailure(plain, traced[0], IOException.class, failure);
    assertFailingPairEvents(events);
  }

  /**
   * With {@code Joiner.allSuccessfulOrThrow(cause -> new IOException(cause))}, {@code join()} is
   * declared to throw {@link IOException}: this method compiles only because {@code catch
   * (IOException e)} is the right clause.
   */
  @Test
  void mappedExceptionJoinIsCaughtAsIOException() throws InterruptedException {
    var failure = new IllegalStateException("boom");
    Throwable caught = null;
    try (var scope =
        TracedScope.open(
            "mapped-catch",
            Joiner.<String, IOException>allSuccessfulOrThrow(cause -> new IOException(cause)))) {
      scope.fork(
          () -> {
            throw failure;
          });
      List<String> unused = scope.join();
      assertThat(unused).isNotNull();
    } catch (IOException e) {
      caught = e;
    }
    assertThat(caught).isInstanceOf(IOException.class).hasCause(failure);
  }

  /**
   * #87: {@code open("sneaky")}'s {@code join()} declares {@link ExecutionException}, so the task's
   * checked exception cannot escape undeclared. This method declares only {@code
   * InterruptedException}; it compiles only because of the catch clause, which receives the
   * failure.
   */
  @Test
  void sneakyCheckedExceptionIsCaughtAsExecutionException() throws InterruptedException {
    Throwable caught = null;
    try (var scope = TracedScope.open("sneaky")) {
      scope.fork(
          () -> {
            throw new IOException("task failed");
          });
      scope.join();
    } catch (ExecutionException e) {
      caught = e;
    }
    assertThat(caught)
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOf(IOException.class)
        .hasMessage("task failed");
  }

  @Test
  void everyFactoryFormEmitsOneScopeOpenedOneScopeClosedAndATaskForkedPerFork() throws Exception {
    Map<String, Function<String, TracedScope<Object, ?, ExecutionException>>> forms =
        new LinkedHashMap<>();
    forms.put("open-name", n -> TracedScope.open(n));
    forms.put("open-name-joiner", n -> TracedScope.open(n, Joiner.<Object>allSuccessfulOrThrow()));
    forms.put("open-name-config", n -> TracedScope.open(n, cfg -> cfg));
    forms.put(
        "open-name-joiner-config",
        n -> TracedScope.open(n, Joiner.<Object>awaitAllSuccessfulOrThrow(), cfg -> cfg));

    for (var form : forms.entrySet()) {
      var name = "count-" + form.getKey();
      var events =
          capture(
              name,
              () -> {
                try (var scope = form.getValue().apply(name)) {
                  scope.fork(() -> 1);
                  scope.fork(() -> 2);
                  scope.fork(() -> 3);
                  scope.join();
                }
              });
      assertThat(eventsOfType(events, OPENED)).as(name).hasSize(1);
      assertThat(eventsOfType(events, CLOSED)).as(name).hasSize(1);
      assertThat(eventsOfType(events, FORKED)).as(name).hasSize(3);
      assertThat(eventsOfType(events, SUCCEEDED)).as(name).hasSize(3);
    }
  }

  /**
   * {@code withTimeout} in the configuration function takes effect: a task that outlives it makes
   * {@code join()} throw what a plain scope throws, {@link ExecutionException} caused by the JDK's
   * {@code CancelledByTimeoutException}. The traced run's events are asserted as observed.
   */
  @Test
  void configFunctionTimeoutThrowsTheSameExceptionAsAPlainScope() throws Exception {
    UnaryOperator<Configuration> timeout = cfg -> cfg.withTimeout(Duration.ofMillis(100));
    Throwable plain;
    try (var scope = StructuredTaskScope.open(timeout)) {
      scope.fork(
          () -> {
            new CountDownLatch(1).await(); // outlives the timeout; interrupted by it
            return null;
          });
      plain = catchJoin(scope::join);
    }
    var name = "config-timeout";
    var traced = new Throwable[1];
    var cancelled = new boolean[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name, timeout)) {
                scope.fork(
                    () -> {
                      new CountDownLatch(1).await();
                      return null;
                    });
                traced[0] = catchJoin(scope::join);
                cancelled[0] = scope.isCancelled();
              }
            });

    assertThat(plain).isInstanceOf(ExecutionException.class);
    assertThat(plain.getCause().getClass().getName())
        .isEqualTo("java.util.concurrent.StructuredTaskScope$CancelledByTimeoutException");
    assertThat(traced[0]).hasSameClassAs(plain);
    assertThat(traced[0].getCause()).hasSameClassAs(plain.getCause());
    assertThat(cancelled[0]).isTrue();
    assertThat(eventNames(events)).containsExactlyInAnyOrder(OPENED, FORKED, CANCELLED, CLOSED);
  }

  /** {@code withThreadFactory} in the configuration function makes the threads the tasks run on. */
  @Test
  void configFunctionThreadFactoryRunsTheTasks() throws Exception {
    var name = "config-thread-factory";
    var onVirtualThread = new boolean[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope =
                  TracedScope.<Boolean>open(
                      name,
                      cfg ->
                          cfg.withThreadFactory(
                              Thread.ofPlatform().name("cfg-factory-", 0).factory()))) {
                Subtask<Boolean> virtual = scope.fork(() -> Thread.currentThread().isVirtual());
                scope.join();
                onVirtualThread[0] = virtual.get();
              }
            });
    assertThat(onVirtualThread[0]).isFalse();
    assertThat(eventsOfType(events, SUCCEEDED))
        .singleElement()
        .satisfies(e -> assertThat(e.getString("threadName")).startsWith("cfg-factory-"));
  }

  /** Without a thread factory in the configuration function, tasks run on virtual threads. */
  @Test
  void tasksRunOnVirtualThreadsByDefault() throws Exception {
    try (var scope = TracedScope.<Boolean>open("default-virtual", cfg -> cfg)) {
      Subtask<Boolean> virtual = scope.fork(() -> Thread.currentThread().isVirtual());
      scope.join();
      assertThat(virtual.get()).isTrue();
    }
  }

  /**
   * A configuration function that throws propagates from {@code open} as from a plain scope, emits
   * nothing and leaves nothing open: the enclosing scope still closes without a structure
   * violation.
   */
  @Test
  void throwingConfigFunctionPropagatesEmitsNothingAndLeavesNothingOpen() throws Exception {
    var failure = new IllegalStateException("config boom");
    UnaryOperator<Configuration> throwing =
        cfg -> {
          throw failure;
        };
    assertThat(catchThrowable(() -> StructuredTaskScope.open(throwing))).isSameAs(failure);

    var name = "throwing-config";
    var events =
        captureAll(
            name,
            () -> {
              try (var outer = TracedScope.open("throwing-config-outer")) {
                assertThat(catchThrowable(() -> TracedScope.open(name, throwing)))
                    .isSameAs(failure);
                assertThat(
                        catchThrowable(
                            () ->
                                TracedScope.open(
                                    name, Joiner.awaitAllSuccessfulOrThrow(), throwing)))
                    .isSameAs(failure);
                outer.join();
              }
            });
    assertThat(filterByScopeName(events, name)).isEmpty();
    assertThat(eventNames(filterByScopeName(events, "throwing-config-outer")))
        .containsExactlyInAnyOrder(OPENED, CLOSED);
  }

  /** A null joiner or configuration function throws what the plain factory throws. */
  @Test
  void nullJoinerOrConfigFunctionThrowsWhatAPlainScopeThrows() throws Exception {
    Joiner<Object, Void, ExecutionException> noJoiner = null;
    UnaryOperator<Configuration> noConfig = null;
    var plainJoiner = catchThrowable(() -> StructuredTaskScope.open(noJoiner));
    var plainJoinerConfig = catchThrowable(() -> StructuredTaskScope.open(noJoiner, cfg -> cfg));
    var plainConfig = catchThrowable(() -> StructuredTaskScope.open(noConfig));
    var plainJoinerNoConfig =
        catchThrowable(
            () -> StructuredTaskScope.open(Joiner.<Object>awaitAllSuccessfulOrThrow(), noConfig));
    assertThat(List.of(plainJoiner, plainJoinerConfig, plainConfig, plainJoinerNoConfig))
        .allSatisfy(t -> assertThat(t).isInstanceOf(NullPointerException.class));

    var name = "null-arguments";
    var events =
        capture(
            name,
            () -> {
              assertThat(catchThrowable(() -> TracedScope.open(name, noJoiner)))
                  .hasSameClassAs(plainJoiner);
              assertThat(catchThrowable(() -> TracedScope.open(name, noJoiner, cfg -> cfg)))
                  .hasSameClassAs(plainJoinerConfig);
              assertThat(catchThrowable(() -> TracedScope.open(name, noConfig)))
                  .hasSameClassAs(plainConfig);
              assertThat(
                      catchThrowable(
                          () ->
                              TracedScope.open(
                                  name, Joiner.<Object>awaitAllSuccessfulOrThrow(), noConfig)))
                  .hasSameClassAs(plainJoinerNoConfig);
            });
    assertThat(events).isEmpty();
  }

  /**
   * {@code cfg.withName("other")} names the underlying scope, but every event carries the {@code
   * name} argument as {@code scopeName}.
   */
  @Test
  void configFunctionWithNameDoesNotChangeScopeName() throws Exception {
    var name = "the-name-argument";
    var other = "other-name-from-config";
    var events =
        captureAll(
            name,
            () -> {
              try (var scope = TracedScope.open(name, cfg -> cfg.withName(other))) {
                scope.fork(() -> "x");
                scope.join();
              }
            });
    assertThat(filterByScopeName(events, other)).isEmpty();
    assertThat(eventNames(filterByScopeName(events, name)))
        .containsExactlyInAnyOrder(OPENED, FORKED, SUCCEEDED, CLOSED);
  }

  /** Scopes that share a name get distinct scope ids, nested two deep and in sequence. */
  @Test
  void scopesWithTheSameNameGetDistinctScopeIds() throws Exception {
    var name = "same-name";
    var events =
        capture(
            name,
            () -> {
              try (var outer = TracedScope.open(name, cfg -> cfg.withName("outer"))) {
                outer.fork(
                    () -> {
                      try (var inner = TracedScope.open(name)) {
                        inner.fork(() -> 1);
                        inner.join();
                      }
                      return null;
                    });
                outer.join();
              }
              try (var sequential = TracedScope.open(name)) {
                sequential.join();
              }
            });
    var ids = eventsOfType(events, OPENED).stream().map(e -> e.getLong("scopeId")).toList();
    assertThat(ids).hasSize(3).doesNotHaveDuplicates();
    assertThat(eventsOfType(events, CLOSED).stream().map(e -> e.getLong("scopeId")))
        .containsExactlyInAnyOrderElementsOf(ids);
  }

  private static Throwable joinFailingPair(
      Function<Callable<Object>, Subtask<Object>> fork,
      ThrowingRunnable join,
      RuntimeException failure) {
    // The sibling is running before the failure, so it returns normally whatever the joiner does.
    var siblingRunning = new CountDownLatch(1);
    fork.apply(
        () -> {
          siblingRunning.countDown();
          return "ok";
        });
    fork.apply(
        () -> {
          siblingRunning.await();
          throw failure;
        });
    return catchJoin(join);
  }

  private static void assertSameFailure(
      Throwable plain, Throwable traced, Class<?> expectedType, RuntimeException failure) {
    assertThat(plain).isInstanceOf(expectedType).hasCause(failure);
    assertThat(traced).isNotNull().hasSameClassAs(plain);
    assertThat(traced.getCause()).isSameAs(plain.getCause());
  }

  /** Task 1 (the sibling) succeeded, task 2 failed, and the scope opened and closed once. */
  private static void assertFailingPairEvents(List<RecordedEvent> events) {
    assertThat(eventsOfType(events, OPENED)).hasSize(1);
    assertThat(eventsOfType(events, FORKED)).hasSize(2);
    assertThat(eventsOfType(events, FAILED))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getLong("taskId")).isEqualTo(2L);
              assertThat(e.getString("exceptionType"))
                  .isEqualTo(IllegalStateException.class.getName());
            });
    assertThat(eventsOfType(events, SUCCEEDED))
        .singleElement()
        .satisfies(e -> assertThat(e.getLong("taskId")).isEqualTo(1L));
    assertThat(eventsOfType(events, CANCELLED)).isEmpty();
    assertThat(eventsOfType(events, CLOSED)).hasSize(1);
  }

  // --- fork(Runnable) and isCancelled(), mirrored from StructuredTaskScope ---

  @Test
  void forkRunnableEmitsTheSameEventsAsForkCallable() throws Exception {
    var name = "fork-runnable";
    var ran = new AtomicBoolean();
    var subtask = new ArrayList<Subtask<String>>();
    var events =
        capture(
            name,
            () -> {
              try (var scope =
                  TracedScope.<String>open(
                      name, cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
                Runnable task = () -> ran.set(true);
                subtask.add(scope.fork(task));
                scope.join();
              }
            });
    assertThat(ran).isTrue();
    assertThat(subtask.get(0).state()).isEqualTo(Subtask.State.SUCCESS);
    assertThat(subtask.get(0).get()).isNull();
    assertThat(eventNames(events)).containsExactlyInAnyOrder(OPENED, FORKED, SUCCEEDED, CLOSED);
  }

  @Test
  void forkRunnableFailureEmitsTaskFailedAndThrowsLikeAPlainScope() throws Exception {
    var failure = new IllegalStateException("runnable boom");
    Runnable failing =
        () -> {
          throw failure;
        };
    Throwable plain;
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(failing);
      plain = catchJoin(scope::join);
    }
    var name = "fork-runnable-failure";
    var traced = new Throwable[1];
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                scope.fork(failing);
                traced[0] = catchJoin(scope::join);
              }
            });
    assertSameFailure(plain, traced[0], ExecutionException.class, failure);
    assertThat(eventsOfType(events, FAILED))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getString("exceptionType"))
                  .isEqualTo(IllegalStateException.class.getName());
              assertThat(e.getString("exceptionMessage")).isEqualTo("runnable boom");
            });
    assertThat(eventsOfType(events, SUCCEEDED)).isEmpty();
  }

  /** A lambda {@code Runnable} gets the caller frame as its label and call site. */
  @Test
  void forkRunnableLambdaCapturesCallSite() throws Exception {
    var name = "fork-runnable-callsite";
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                Runnable task = () -> {};
                scope.fork(task);
                scope.join();
              }
            });
    var event = eventsOfType(events, FORKED).get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF);
    assertThat(event.getString("callSiteMethodName"))
        .isEqualTo("forkRunnableLambdaCapturesCallSite");
    assertThat(event.getInt("callSiteLine")).isPositive();
    assertThat(event.getString("taskName"))
        .isEqualTo(
            "TracedScopeTest#forkRunnableLambdaCapturesCallSite:" + event.getInt("callSiteLine"));
  }

  /**
   * A named {@code Runnable} class gets the calling frame as its label and call site, not its
   * class: the agent sees only the JDK's wrapper around a plain scope's {@code Runnable} and
   * records the calling frame, and the two producers stamp the same ({@code ProducerCallSiteIT} in
   * the agent checks it).
   */
  @Test
  void forkRunnableClassCapturesTheCallingFrame() throws Exception {
    var name = "fork-runnable-class";
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                scope.fork(new NamedRunnable());
                scope.join();
              }
            });
    var event = eventsOfType(events, FORKED).get(0);
    assertThat(event.getString("callSiteClassName")).isEqualTo(SELF);
    assertThat(event.getString("callSiteMethodName"))
        .isEqualTo("forkRunnableClassCapturesTheCallingFrame");
    assertThat(event.getInt("callSiteLine")).isPositive();
    assertThat(event.getString("taskName"))
        .isEqualTo(
            "TracedScopeTest#forkRunnableClassCapturesTheCallingFrame:"
                + event.getInt("callSiteLine"));
  }

  /** Callable, Runnable and labelled forks share one task-id sequence. */
  @Test
  void forkRunnableTakesTheNextTaskId() throws Exception {
    var name = "fork-mixed-ids";
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                scope.fork(() -> "callable");
                scope.fork(new NamedRunnable());
                scope.fork("labelled", () -> "labelled");
                scope.join();
              }
            });
    var labels = new TreeMap<Long, String>();
    eventsOfType(events, FORKED)
        .forEach(e -> labels.put(e.getLong("taskId"), e.getString("taskName")));
    assertThat(labels).containsOnlyKeys(1L, 2L, 3L);
    assertThat(labels.get(1L)).startsWith("TracedScopeTest#forkRunnableTakesTheNextTaskId:");
    assertThat(labels.get(2L)).startsWith("TracedScopeTest#forkRunnableTakesTheNextTaskId:");
    assertThat(labels.get(3L)).isEqualTo("labelled");
    assertThat(eventsOfType(events, SUCCEEDED)).hasSize(3);
  }

  /** Forking null throws what a plain scope throws, before anything is emitted. */
  @Test
  void forkingNullThrowsLikeAPlainScopeAndEmitsNothing() throws Exception {
    Callable<Object> noCallable = null;
    Runnable noRunnable = null;
    Throwable plainCallable;
    Throwable plainRunnable;
    try (var scope = StructuredTaskScope.open()) {
      plainCallable = catchThrowable(() -> scope.fork(noCallable));
      plainRunnable = catchThrowable(() -> scope.fork(noRunnable));
      scope.join();
    }
    assertThat(plainCallable).isInstanceOf(NullPointerException.class);
    assertThat(plainRunnable).isInstanceOf(NullPointerException.class);

    var name = "fork-null";
    var events =
        capture(
            name,
            () -> {
              try (var scope = TracedScope.open(name)) {
                assertThat(catchThrowable(() -> scope.fork(noCallable)))
                    .hasSameClassAs(plainCallable);
                assertThat(catchThrowable(() -> scope.fork(noRunnable)))
                    .hasSameClassAs(plainRunnable);
                assertThat(catchThrowable(() -> scope.fork("label", noCallable)))
                    .hasSameClassAs(plainCallable);
                scope.join();
              }
            });
    assertThat(eventNames(events)).containsExactlyInAnyOrder(OPENED, CLOSED);
  }

  /**
   * {@code isCancelled()} agrees with a plain scope before {@code join}, after a joiner cancels the
   * scope, and after {@code close}; it emits nothing.
   */
  @Test
  void isCancelledAgreesWithAPlainScope() throws Exception {
    var plain = new ArrayList<Boolean>();
    var plainScope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow());
    try (plainScope) {
      plain.add(plainScope.isCancelled());
      plainScope.fork(() -> "first");
      plainScope.join();
      plain.add(plainScope.isCancelled());
    }
    plain.add(plainScope.isCancelled());

    var name = "is-cancelled";
    var traced = new ArrayList<Boolean>();
    var events =
        capture(
            name,
            () -> {
              var scope = TracedScope.open(name, Joiner.<String>anySuccessfulOrThrow());
              try (scope) {
                traced.add(scope.isCancelled());
                scope.fork(() -> "first");
                scope.join();
                traced.add(scope.isCancelled());
              }
              traced.add(scope.isCancelled());
            });
    assertThat(plain).containsExactly(false, true, true);
    assertThat(traced).isEqualTo(plain);
    assertThat(eventNames(events)).containsExactlyInAnyOrder(OPENED, FORKED, SUCCEEDED, CLOSED);
  }

  /**
   * Under the default joiner a successful scope is never cancelled, not even by {@code close()}
   * after {@code join()}; {@code close()} before {@code join()} cancels it. As on a plain scope.
   */
  @Test
  void isCancelledUnderTheDefaultJoinerAgreesWithAPlainScope() throws Exception {
    var plain = new ArrayList<Boolean>();
    var plainJoined = StructuredTaskScope.open();
    try (plainJoined) {
      plain.add(plainJoined.isCancelled());
      plainJoined.fork(() -> "x");
      plainJoined.join();
      plain.add(plainJoined.isCancelled());
    }
    plain.add(plainJoined.isCancelled());
    var plainUnjoined = StructuredTaskScope.open();
    plainUnjoined.close();
    plain.add(plainUnjoined.isCancelled());

    var traced = new ArrayList<Boolean>();
    var tracedJoined = TracedScope.open("is-cancelled-default");
    try (tracedJoined) {
      traced.add(tracedJoined.isCancelled());
      tracedJoined.fork(() -> "x");
      tracedJoined.join();
      traced.add(tracedJoined.isCancelled());
    }
    traced.add(tracedJoined.isCancelled());
    var tracedUnjoined = TracedScope.open("is-cancelled-unjoined");
    tracedUnjoined.close();
    traced.add(tracedUnjoined.isCancelled());

    assertThat(plain).containsExactly(false, false, false, true);
    assertThat(traced).isEqualTo(plain);
  }

  /** A named Runnable class, which {@code fork(Runnable)} still labels by the calling frame. */
  private static final class NamedRunnable implements Runnable {
    @Override
    public void run() {}
  }

  // --- helpers ---

  private List<RecordedEvent> capture(String scopeName, ThrowingRunnable action) throws Exception {
    return filterByScopeName(captureAll(scopeName, action), scopeName);
  }

  /** Every scope-tracer event recorded while {@code action} ran, whatever its scope name. */
  private List<RecordedEvent> captureAll(String fileName, ThrowingRunnable action)
      throws Exception {
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      try {
        action.run();
      } finally {
        recording.stop();
      }
      var dump = tempDir.resolve("jfr-" + fileName + ".jfr");
      recording.dump(dump);
      return readEvents(dump);
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
