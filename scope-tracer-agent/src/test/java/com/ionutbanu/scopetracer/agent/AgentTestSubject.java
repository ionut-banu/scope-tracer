package com.ionutbanu.scopetracer.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Minimal program that exercises {@code StructuredTaskScope} under a JFR recording. Invoked by
 * {@link ScopeTracerAgentIT} and {@link ScopeTracerAgentFilterIT} in a forked JVM with the agent
 * attached; writes the recording to the path given as {@code args[0]}.
 *
 * <p>Scopes opened with {@code open(Joiner, UnaryOperator)}:
 *
 * <ul>
 *   <li><b>Named scope</b> ({@code "agent-it-scope"}) — three tasks: two {@code Callable} and one
 *       {@code Runnable}. All succeed.
 *   <li><b>Unnamed scope</b> ({@value #UNNAMED_TWO_ARG_SCOPE}) — one {@code Callable} task that
 *       succeeds. The agent derives the name from the call-site stack frame.
 *   <li>Fail, cancel, outer/inner and {@value #CONCURRENT_SCOPE_COUNT} concurrent named scopes.
 * </ul>
 *
 * <p>Scopes opened with the one- and zero-argument overloads, which the JDK implements by calling
 * {@code open(Joiner, UnaryOperator)} (issue #51). Each is opened from a method of its own, so its
 * derived name is that method:
 *
 * <ul>
 *   <li>{@value #NO_ARG_SCOPE} — {@code open()}, two tasks that succeed.
 *   <li>{@value #JOINER_SCOPE} — {@code open(Joiner.awaitAllSuccessfulOrThrow())}, two tasks that
 *       succeed.
 *   <li>{@value #NO_ARG_FAIL_SCOPE} — {@code open()}, one task that throws.
 *   <li>{@value #ANY_SUCCESS_SCOPE} — {@code open(Joiner.anySuccessfulOrThrow())}, a winner and a
 *       loser that blocks until the scope cancels it.
 *   <li>{@value #NESTED_LEVEL_0} → {@value #NESTED_LEVEL_1} → {@value #NESTED_LEVEL_2} — {@code
 *       open()} in a task of a scope opened with {@code open()}, two levels deep.
 *   <li>{@value #REPEATED_SCOPE} — the same method called {@value #REPEATED_COUNT} times, so two
 *       scopes with one derived name.
 *   <li>{@value #CONCURRENT_OPEN_SCOPE} — {@value #CONCURRENT_SCOPE_COUNT} platform threads each
 *       calling {@code open()} at once.
 * </ul>
 */
public final class AgentTestSubject {

  static final String NAMED_SCOPE = "agent-it-scope";
  static final String FAIL_SCOPE = "agent-fail-scope";
  static final String CANCEL_SCOPE = "agent-cancel-scope";
  static final String OUTER_SCOPE = "agent-outer-scope";
  static final String INNER_SCOPE = "agent-inner-scope";
  static final String CONCURRENT_SCOPE_BASE = "agent-concurrent-scope";
  static final int CONCURRENT_SCOPE_COUNT = 4;

  /** Derived name of the unnamed scope opened with {@code open(Joiner, UnaryOperator)}. */
  static final String UNNAMED_TWO_ARG_SCOPE = "AgentTestSubject#main";

  static final String NO_ARG_SCOPE = "AgentTestSubject#openNoArgs";
  static final String JOINER_SCOPE = "AgentTestSubject#openWithJoiner";
  static final String NO_ARG_FAIL_SCOPE = "AgentTestSubject#openNoArgsFailing";
  static final String ANY_SUCCESS_SCOPE = "AgentTestSubject#openAnySuccessful";
  static final String NESTED_LEVEL_0 = "AgentTestSubject#nestedLevel0";
  static final String NESTED_LEVEL_1 = "AgentTestSubject#nestedLevel1";
  static final String NESTED_LEVEL_2 = "AgentTestSubject#nestedLevel2";
  static final String REPEATED_SCOPE = "AgentTestSubject#openRepeated";
  static final int REPEATED_COUNT = 2;
  static final String CONCURRENT_OPEN_SCOPE = "AgentTestSubject#openConcurrently";

  static final String FAILURE_MESSAGE = "intentional failure";
  static final String NO_ARG_FAILURE_MESSAGE = "intentional failure under open()";
  static final String WINNER_RESULT = "winner";

  /** Every derived (unnamed) scope name the subject produces. */
  static final List<String> DERIVED_NAMES =
      List.of(
          UNNAMED_TWO_ARG_SCOPE,
          NO_ARG_SCOPE,
          JOINER_SCOPE,
          NO_ARG_FAIL_SCOPE,
          ANY_SUCCESS_SCOPE,
          NESTED_LEVEL_0,
          NESTED_LEVEL_1,
          NESTED_LEVEL_2,
          REPEATED_SCOPE,
          CONCURRENT_OPEN_SCOPE);

  /**
   * One per {@code open} call: named, unnamed, fail, cancel, outer, inner, the concurrent named
   * scopes, then no-arg, joiner, no-arg-fail, any-success, three nested levels, the repeated scopes
   * and the concurrent {@code open()} scopes.
   */
  static final int EXPECTED_SCOPE_COUNT =
      6 + CONCURRENT_SCOPE_COUNT + 4 + 3 + REPEATED_COUNT + CONCURRENT_SCOPE_COUNT;

  private AgentTestSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      // Named scope: three tasks — two Callable, one Runnable (delegates to Callable internally)
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(NAMED_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              Thread.sleep(10);
              return "result-1";
            });
        scope.fork(
            () -> {
              Thread.sleep(5);
              return "result-2";
            });
        // Runnable overload — traced via fork(Callable) delegation
        scope.fork((Runnable) () -> {});
        scope.join();
      }

      // Unnamed scope: name must be derived from the call-site by ScopeNameDeriver
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              Thread.sleep(5);
              return "result-a";
            });
        scope.join();
      }

      // Failing scope: one task throws; catch ExecutionException so the process exits normally.
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(FAIL_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              throw new IllegalStateException(FAILURE_MESSAGE);
            });
        try {
          scope.join();
        } catch (ExecutionException ignored) {
          // expected — one task failed
        }
      }

      // Cancellation scope: one task blocks, a sibling failure shuts the scope down,
      // causing the blocked task to be cancelled via InterruptedException.
      var taskLive = new CountDownLatch(1);
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(CANCEL_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              taskLive.countDown();
              Thread.sleep(Long.MAX_VALUE); // blocks until scope shuts down
              return null;
            });
        scope.fork(
            () -> {
              taskLive.await(); // ensure the other task is live before we fail
              throw new RuntimeException("trigger cancellation");
            });
        try {
          scope.join();
        } catch (ExecutionException ignored) {
          // expected
        }
      }

      // Nested scope: outer task opens an inner StructuredTaskScope — the agent must detect
      // the parent-child relationship via thread-ID matching.
      try (var outer =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(OUTER_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        outer.fork(
            () -> {
              try (var inner =
                  StructuredTaskScope.open(
                      Joiner.awaitAllSuccessfulOrThrow(),
                      c ->
                          c.withName(INNER_SCOPE)
                              .withThreadFactory(Thread.ofVirtual().factory()))) {
                inner.fork(() -> "inner-result");
                inner.join();
              }
              return "outer-result";
            });
        outer.join();
      }

      // Concurrent scopes: N threads each open a scope simultaneously.
      // The agent must assign distinct scopeIds despite concurrent SCOPE_ID_COUNTER increments.
      var cReady = new CountDownLatch(CONCURRENT_SCOPE_COUNT);
      var cRelease = new CountDownLatch(1);
      var cThreads = new ArrayList<Thread>();
      for (int i = 0; i < CONCURRENT_SCOPE_COUNT; i++) {
        final int idx = i;
        cThreads.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        cReady.countDown();
                        cRelease.await();
                        try (var s =
                            StructuredTaskScope.open(
                                Joiner.awaitAllSuccessfulOrThrow(),
                                c ->
                                    c.withName(CONCURRENT_SCOPE_BASE + "-" + idx)
                                        .withThreadFactory(Thread.ofVirtual().factory()))) {
                          s.fork(() -> "c" + idx);
                          s.join();
                        }
                      } catch (Exception e) {
                        throw new RuntimeException(e);
                      }
                    }));
      }
      cReady.await();
      cRelease.countDown();
      for (var t : cThreads) t.join();

      // --- the one- and zero-argument overloads (issue #51) ---
      openNoArgs();
      openWithJoiner();
      openNoArgsFailing();
      openAnySuccessful();
      nestedLevel0();
      for (int i = 0; i < REPEATED_COUNT; i++) {
        openRepeated(i);
      }
      openConcurrentlyFromThreads();

      recording.stop();
      recording.dump(jfrOut);
    }
  }

  /** {@code open()}: two tasks that succeed. */
  private static void openNoArgs() throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> "no-arg-1");
      scope.fork(() -> "no-arg-2");
      scope.join();
    }
  }

  /** {@code open(Joiner)}: two tasks that succeed. */
  private static void openWithJoiner() throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open(Joiner.<String>awaitAllSuccessfulOrThrow())) {
      scope.fork(() -> "joiner-1");
      scope.fork(() -> "joiner-2");
      scope.join();
    }
  }

  /** {@code open()}: one task that throws. */
  private static void openNoArgsFailing() throws InterruptedException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(
          () -> {
            throw new IllegalStateException(NO_ARG_FAILURE_MESSAGE);
          });
      try {
        scope.join();
      } catch (ExecutionException expected) {
        // the task failed, which is the point
      }
    }
  }

  /**
   * {@code open(Joiner.anySuccessfulOrThrow())}: the loser is forked first and is running before
   * the winner returns, then blocks until the winner's success cancels the scope.
   */
  private static void openAnySuccessful() throws InterruptedException, ExecutionException {
    var loserRunning = new CountDownLatch(1);
    var never = new CountDownLatch(1);
    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork(
          () -> {
            loserRunning.countDown();
            never.await(); // interrupted when the scope is cancelled
            return "loser";
          });
      scope.fork(
          () -> {
            loserRunning.await();
            return WINNER_RESULT;
          });
      String result = scope.join();
      if (!WINNER_RESULT.equals(result)) {
        throw new AssertionError("anySuccessfulOrThrow returned " + result);
      }
    }
  }

  /** {@code open()} whose one task opens {@link #nestedLevel1()}. */
  private static void nestedLevel0() throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> nestedLevel1());
      scope.join();
    }
  }

  /** {@code open()} in a task of {@link #nestedLevel0()}; its one task opens level 2. */
  private static String nestedLevel1() throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> nestedLevel2());
      scope.join();
    }
    return "level-1";
  }

  /** {@code open()} in a task of {@link #nestedLevel1()}, with one leaf task. */
  private static String nestedLevel2() throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> "level-2-leaf");
      scope.join();
    }
    return "level-2";
  }

  /** {@code open()} from one method called more than once: one derived name, several scopes. */
  private static void openRepeated(int call) throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> "repeated-" + call);
      scope.join();
    }
  }

  /** {@value #CONCURRENT_SCOPE_COUNT} platform threads released together into {@code open()}. */
  private static void openConcurrentlyFromThreads() throws InterruptedException {
    var ready = new CountDownLatch(CONCURRENT_SCOPE_COUNT);
    var release = new CountDownLatch(1);
    var threads = new ArrayList<Thread>();
    for (int i = 0; i < CONCURRENT_SCOPE_COUNT; i++) {
      final int idx = i;
      threads.add(
          Thread.ofPlatform()
              .name("open-concurrently-" + idx)
              .start(
                  () -> {
                    try {
                      ready.countDown();
                      release.await();
                      openConcurrently(idx);
                    } catch (InterruptedException | ExecutionException e) {
                      throw new RuntimeException(e);
                    }
                  }));
    }
    ready.await();
    release.countDown();
    for (var t : threads) t.join();
  }

  private static void openConcurrently(int idx) throws InterruptedException, ExecutionException {
    try (var scope = StructuredTaskScope.open()) {
      scope.fork(() -> "concurrent-open-" + idx);
      scope.join();
    }
  }
}
