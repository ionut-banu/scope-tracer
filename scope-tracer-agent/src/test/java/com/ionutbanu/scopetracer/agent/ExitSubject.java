package com.ionutbanu.scopetracer.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * A program that ends in a chosen way, run under the agent by {@link AgentReportAtExitIT}, {@link
 * AgentRecordIT} and {@link AgentRenamedJarIT}. It starts no recording of its own: the recording
 * comes from {@code -XX:StartFlightRecording} or from the agent's {@code record} argument.
 *
 * <p>{@code args[0]} is the mode:
 *
 * <ul>
 *   <li>{@code none} - opens no scope.
 *   <li>{@code scopes <name>,<name>,...} - one closed scope per name, one task each.
 *   <li>{@code first} - the first statement of {@code main} opens {@value #FIRST_SCOPE}.
 *   <li>{@code tree} - the trace compared between the two routes: {@value #TREE_FAIL} (default
 *       joiner, one task throws while its sibling is still running), {@value #TREE_NEST} whose task
 *       opens {@value #TREE_LEVEL_1} whose task opens {@value #TREE_LEVEL_2}, and {@value
 *       #TREE_TWIN} opened twice.
 *   <li>{@code mixed} - {@value #MIXED_SCOPE} (default joiner) with one task that returns and, once
 *       it has, one that throws.
 *   <li>{@code mixed-tree} - {@code mixed}, then {@code tree}.
 *   <li>{@code many <n>} - {@code n} closed scopes of one task each.
 *   <li>{@code stdin} - one closed scope, prints {@value #READY}, returns after a line on stdin.
 *   <li>Exit paths, each after one closed scope {@value #EXIT_SCOPE}: {@code return}; {@code exit}
 *       ({@code System.exit(3)} while {@value #OPEN_AT_EXIT} is open on another thread); {@code
 *       throw} (an uncaught exception out of {@code main}); {@code hang} ({@value #OPEN_AT_EXIT}
 *       open on another thread and {@code main} blocked, until the test ends the process); {@code
 *       halt} ({@code Runtime.halt(0)}).
 * </ul>
 *
 * <p>The last line on stdout is {@value #DONE}, or {@value #SCOPE_RUNNING} for {@code exit} and
 * {@code hang}, printed once {@value #OPEN_AT_EXIT}'s task is running. With {@code
 * -Dexitsubject.marker=<path>} a shutdown hook of the program's own writes that file.
 */
public final class ExitSubject {

  static final String DONE = "done";
  static final String READY = "ready";
  static final String SCOPE_RUNNING = "scope running";
  static final String EXIT_SCOPE = "exit-scope";
  static final String OPEN_AT_EXIT = "open-at-exit";
  static final String FIRST_SCOPE = "first-statement";
  static final String TREE_FAIL = "tree-fail";
  static final String TREE_NEST = "tree-nest";
  static final String TREE_LEVEL_1 = "tree-level-1";
  static final String TREE_LEVEL_2 = "tree-level-2";
  static final String TREE_TWIN = "tree-twin";
  static final String MIXED_SCOPE = "mixed";
  static final String FAILURE = "task failed on purpose";
  static final String MAIN_FAILURE = "main failed on purpose";
  static final String MARKER_PROPERTY = "exitsubject.marker";

  private ExitSubject() {}

  public static void main(String[] args) throws Exception {
    if (args[0].equals("first")) {
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(FIRST_SCOPE))) {
        scope.fork(() -> "first");
        scope.join();
      }
      System.out.println(DONE);
      return;
    }
    String marker = System.getProperty(MARKER_PROPERTY);
    if (marker != null) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Files.writeString(Path.of(marker), "hook ran");
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  }));
    }
    switch (args[0]) {
      case "none" -> System.out.println(DONE);
      case "scopes" -> {
        for (String name : args[1].split(",")) {
          closedScope(name);
        }
        System.out.println(DONE);
      }
      case "tree" -> {
        tree();
        System.out.println(DONE);
      }
      case "mixed" -> {
        mixed();
        System.out.println(DONE);
      }
      case "mixed-tree" -> {
        mixed();
        tree();
        System.out.println(DONE);
      }
      case "many" -> {
        int n = Integer.parseInt(args[1]);
        for (int i = 0; i < n; i++) {
          closedScope("many");
        }
        System.out.println(DONE);
      }
      case "stdin" -> {
        closedScope(EXIT_SCOPE);
        System.out.println(READY);
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        System.out.println(DONE);
      }
      case "return" -> {
        closedScope(EXIT_SCOPE);
        System.out.println(DONE);
      }
      case "exit" -> {
        closedScope(EXIT_SCOPE);
        openOnAnotherThread();
        System.out.println(SCOPE_RUNNING);
        System.exit(3);
      }
      case "throw" -> {
        closedScope(EXIT_SCOPE);
        throw new IllegalStateException(MAIN_FAILURE);
      }
      case "hang" -> {
        closedScope(EXIT_SCOPE);
        openOnAnotherThread();
        System.out.println(SCOPE_RUNNING);
        new CountDownLatch(1).await();
      }
      case "halt" -> {
        closedScope(EXIT_SCOPE);
        System.out.println(DONE);
        System.out.flush();
        Runtime.getRuntime().halt(0);
      }
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  private static void closedScope(String name) throws Exception {
    try (var scope =
        StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(name))) {
      scope.fork(() -> name);
      scope.join();
    }
  }

  /** Opens {@value #OPEN_AT_EXIT} on a platform thread and returns once its task is running. */
  private static void openOnAnotherThread() throws InterruptedException {
    var running = new CountDownLatch(1);
    Thread.ofPlatform()
        .name("open-at-exit-owner")
        .start(
            () -> {
              try (var scope =
                  StructuredTaskScope.open(
                      Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(OPEN_AT_EXIT))) {
                scope.fork(
                    () -> {
                      running.countDown();
                      new CountDownLatch(1).await();
                      return null;
                    });
                scope.join();
              } catch (Exception e) {
                // the task never finishes; the JVM exits around it
              }
            });
    running.await();
  }

  /**
   * One task returns, then the other throws: the first has already returned when the scope is
   * cancelled, so it ends {@code TaskSucceeded} and the second {@code TaskFailed}.
   */
  private static void mixed() throws InterruptedException {
    var returned = new CountDownLatch(1);
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(MIXED_SCOPE))) {
      scope.fork(
          () -> {
            returned.countDown();
            return "returned";
          });
      scope.fork(
          () -> {
            returned.await();
            throw new IllegalStateException(FAILURE);
          });
      scope.join();
    } catch (java.util.concurrent.ExecutionException expected) {
      // the failure is the point
    }
  }

  private static void tree() throws Exception {
    // Default joiner (awaitAllSuccessfulOrThrow): the failing task throws only once its sibling is
    // running, so the sibling is still running when the scope is cancelled.
    var siblingRunning = new CountDownLatch(1);
    try (var scope =
        StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(TREE_FAIL))) {
      scope.fork(
          () -> {
            siblingRunning.countDown();
            new CountDownLatch(1).await();
            return "never";
          });
      scope.fork(
          () -> {
            siblingRunning.await();
            throw new IllegalStateException(FAILURE);
          });
      scope.join();
    } catch (java.util.concurrent.ExecutionException expected) {
      // the failure is the point
    }

    try (var scope =
        StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(TREE_NEST))) {
      scope.fork(
          () -> {
            try (var level1 =
                StructuredTaskScope.open(
                    Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(TREE_LEVEL_1))) {
              level1.fork(
                  () -> {
                    closedScope(TREE_LEVEL_2);
                    return "level-2";
                  });
              level1.join();
            }
            return "level-1";
          });
      scope.join();
    }

    closedScope(TREE_TWIN);
    closedScope(TREE_TWIN);
  }
}
