package dev.scopetracer.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Minimal program that exercises {@code StructuredTaskScope} under a JFR recording. Invoked by
 * {@link ScopeTracerAgentIT} in a forked JVM with the agent attached; writes the recording to the
 * path given as {@code args[0]}.
 *
 * <p>Two scopes are opened to cover both naming modes:
 *
 * <ul>
 *   <li><b>Named scope</b> ({@code "agent-it-scope"}) — three tasks: two {@code Callable} and one
 *       {@code Runnable}. All succeed.
 *   <li><b>Unnamed scope</b> — one {@code Callable} task that succeeds. The agent derives the name
 *       from the call-site stack frame.
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

  private AgentTestSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);

    try (var recording = new Recording()) {
      recording.enable("dev.scopetracer.*");
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

      // Failing scope: one task throws; catch FailedException so the process exits normally.
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(FAIL_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              throw new IllegalStateException("intentional failure");
            });
        try {
          scope.join();
        } catch (StructuredTaskScope.FailedException ignored) {
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
        } catch (StructuredTaskScope.FailedException ignored) {
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

      recording.stop();
      recording.dump(jfrOut);
    }
  }
}
