package com.ionutbanu.scopetracer.analyzer;

import com.ionutbanu.scopetracer.core.TracedScope;
import com.ionutbanu.scopetracer.core.events.ScopeClosedEvent;
import com.ionutbanu.scopetracer.core.events.ScopeOpenedEvent;
import com.ionutbanu.scopetracer.core.events.TaskForkedEvent;
import com.ionutbanu.scopetracer.core.events.TaskSucceededEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import jdk.jfr.Recording;

/**
 * Recordings for the CLI tests, made from real {@code TracedScope}s under a real {@link Recording}
 * (spec §4, the oracle). A scope "left open" is dumped while its task is blocked, which is what a
 * recording written while a program is still running holds; it is released and closed afterwards.
 */
final class TestRecordings {

  private TestRecordings() {}

  /** One action run while a recording of scope-tracer events is on; the file is dumped after. */
  @FunctionalInterface
  interface Body {
    void run(Recording recording, Path jfr) throws Exception;
  }

  static Path record(Path jfr, Body body) throws Exception {
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      body.run(recording, jfr);
      if (recording.getState() == jdk.jfr.RecordingState.RUNNING) {
        recording.stop();
        recording.dump(jfr);
      }
    }
    return jfr;
  }

  /** {@code roots} closed root scopes named {@code name}, one successful task each. */
  static Path complete(Path jfr, String name, int roots) throws Exception {
    return record(
        jfr,
        (r, f) -> {
          for (int i = 0; i < roots; i++) {
            try (var scope = TracedScope.open(name)) {
              scope.fork(() -> "ok");
              scope.join();
            }
          }
        });
  }

  /**
   * A closed scope whose task opens a closed child scope whose task opens a closed grandchild:
   * three nested, complete scopes.
   */
  static Path nested(Path jfr) throws Exception {
    return record(
        jfr,
        (r, f) -> {
          try (var root = TracedScope.open("cli-root")) {
            root.fork(
                () -> {
                  try (var child = TracedScope.open("cli-child")) {
                    child.fork(
                        () -> {
                          try (var grandchild = TracedScope.open("cli-grandchild")) {
                            grandchild.fork(() -> "leaf");
                            grandchild.join();
                          }
                          return "child";
                        });
                    child.join();
                  }
                  return "root";
                });
            root.fork(() -> "sibling");
            root.join();
          }
        });
  }

  /**
   * One closed scope, then {@code open} scopes named {@code name} left open, each with one task
   * blocked when the recording is dumped. With {@code depth > 1} the open scopes are nested: each
   * blocked task opens the next one, and the innermost task blocks.
   */
  static Path leftOpen(Path jfr, String name, int open, int depth) throws Exception {
    return record(
        jfr,
        (recording, f) -> {
          try (var scope = TracedScope.open("cli-closed")) {
            scope.fork(() -> "ok");
            scope.join();
          }
          var release = new CountDownLatch(1);
          var running = new CountDownLatch(open * depth);
          var owners = new ArrayList<Thread>();
          for (int i = 0; i < open; i++) {
            owners.add(Thread.ofPlatform().start(() -> openNested(name, depth, running, release)));
          }
          running.await();
          recording.dump(f);
          recording.stop();
          release.countDown();
          for (var owner : owners) owner.join();
        });
  }

  private static void openNested(
      String name, int depth, CountDownLatch running, CountDownLatch release) {
    try (var scope = TracedScope.open(name)) {
      scope.fork(
          () -> {
            running.countDown();
            if (depth > 1) {
              openNested(name, depth - 1, running, release);
            } else {
              release.await();
            }
            return null;
          });
      scope.join();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** One scope with no tasks, left open: a scope without a close and no task at all. */
  static Path scopeWithoutTasksLeftOpen(Path jfr) throws Exception {
    return record(
        jfr,
        (recording, f) -> {
          try (var scope = TracedScope.open("cli-no-tasks")) {
            recording.dump(f);
            recording.stop();
            scope.join();
          }
        });
  }

  /**
   * A closed scope with {@code tasks} forks and no completion, as when JFR dropped the completion
   * events. Committed directly: a real scope cannot close before its tasks complete.
   */
  static Path tasksWithoutCompletion(Path jfr, int tasks) throws Exception {
    return record(
        jfr,
        (r, f) -> {
          long scopeId = 7_000_000_001L;
          var opened = new ScopeOpenedEvent();
          opened.scopeId = scopeId;
          opened.scopeName = "cli-dropped";
          opened.threadName = Thread.currentThread().getName();
          opened.commit();
          for (int t = 1; t <= tasks; t++) {
            var forked = new TaskForkedEvent();
            forked.scopeId = scopeId;
            forked.scopeName = "cli-dropped";
            forked.taskId = t;
            forked.threadName = Thread.currentThread().getName();
            forked.commit();
          }
          var closed = new ScopeClosedEvent();
          closed.scopeId = scopeId;
          closed.scopeName = "cli-dropped";
          closed.threadName = Thread.currentThread().getName();
          closed.commit();
        });
  }

  /**
   * Scope-tracer events with no {@code ScopeOpened}, as in a recording started after the scope was
   * opened: with {@code fork}, one {@code TaskForked} for task 2, then {@code completions} {@code
   * TaskSucceeded}. With {@code completeScopes > 0}, that many complete scopes are recorded first.
   */
  static Path orphans(Path jfr, int completeScopes, boolean fork, int completions)
      throws Exception {
    return record(
        jfr,
        (r, f) -> {
          for (int i = 0; i < completeScopes; i++) {
            try (var scope = TracedScope.open("cli-complete")) {
              scope.fork(() -> "ok");
              scope.join();
            }
          }
          long scopeId = 7_000_000_002L;
          if (fork) {
            var forked = new TaskForkedEvent();
            forked.scopeId = scopeId;
            forked.scopeName = "cli-orphan";
            forked.taskId = 2;
            forked.threadName = Thread.currentThread().getName();
            forked.commit();
          }
          for (long taskId = 1; taskId <= completions; taskId++) {
            var succeeded = new TaskSucceededEvent();
            succeeded.scopeId = scopeId;
            succeeded.scopeName = "cli-orphan";
            succeeded.taskId = taskId;
            succeeded.threadName = Thread.currentThread().getName();
            succeeded.commit();
          }
        });
  }

  /** A recording with no scope-tracer events: nothing is enabled but JFR's own. */
  static Path empty(Path jfr) throws IOException {
    try (var recording = new Recording()) {
      recording.enable("jdk.JVMInformation");
      recording.start();
      recording.stop();
      recording.dump(jfr);
    }
    return jfr;
  }

  static Path resource(String name) throws URISyntaxException {
    return Path.of(TestRecordings.class.getClassLoader().getResource(name).toURI());
  }
}
