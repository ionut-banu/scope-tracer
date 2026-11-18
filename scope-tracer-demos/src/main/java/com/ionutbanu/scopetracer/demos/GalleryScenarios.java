package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * The scenarios behind the gallery: one method per join policy, each a few lines of {@link
 * TracedScope} code. {@link GalleryMain} records every one and renders its report.
 *
 * <p>Every snippet on the gallery page is the text between a pair of {@code gallery:begin} and
 * {@code gallery:end} marker comments in this file, extracted by {@link Snippets}, so a snippet can
 * never differ from the code that ran. Do not delete the markers, and keep each method's timings
 * apart as the comments say: the gate in {@link GalleryMain} rejects a recording whose shape is not
 * the one declared in {@link GalleryCatalog}.
 *
 * <p>Timing rules, measured on idle and loaded machines (issue #59):
 *
 * <ul>
 *   <li>the events that decide a scope's shape (the first failure, the first success, the timeout)
 *       come at least 150 ms after the scope opens and at least 100 ms apart, so a main thread that
 *       is descheduled between two {@code fork} calls does not reorder them;
 *   <li>a task that must end up cancelled sleeps 2 s, so the scope's length does not depend on it;
 *   <li>tasks are named with {@code fork(name, ...)}, so a line shift in this file does not change
 *       a report.
 * </ul>
 */
final class GalleryScenarios {

  private GalleryScenarios() {}

  /** Sleeps {@code millis} milliseconds, then returns {@code value}. */
  static <T> T work(long millis, T value) throws InterruptedException {
    Thread.sleep(millis);
    return value;
  }

  /** Sleeps {@code millis} milliseconds, then throws {@code failure}. */
  static <T> T fail(long millis, Exception failure) throws Exception {
    Thread.sleep(millis);
    throw failure;
  }

  static void failFast() throws Exception {
    // gallery:begin fail-fast
    try (var scope = TracedScope.open("fail-fast")) {
      scope.fork("profile", () -> work(50, "profile"));
      scope.fork(
          "inventory-check",
          () -> fail(200, new IllegalStateException("inventory service unavailable")));
      scope.fork("recommendations", () -> work(2000, "recommendations"));
      scope.fork("reviews", () -> work(2000, "reviews"));
      try {
        scope.join();
      } catch (ExecutionException e) {
        // e.getCause() is the IllegalStateException; the two slow tasks were interrupted
      }
    }
    // gallery:end fail-fast
  }

  static void allSuccessful() throws Exception {
    // gallery:begin all-successful
    try (var scope = TracedScope.open("all-successful", Joiner.<String>allSuccessfulOrThrow())) {
      scope.fork("product", () -> work(150, "product"));
      scope.fork("pricing", () -> work(250, "pricing"));
      scope.fork("shipping", () -> work(400, "shipping"));
      scope.join(); // the results, in the order the tasks were forked
    }
    // gallery:end all-successful
  }

  static void anySuccessful() throws Exception {
    // gallery:begin any-successful
    try (var scope = TracedScope.open("any-successful", Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork("mirror-1", () -> fail(200, new ConnectException("mirror-1 refused")));
      scope.fork("mirror-2", () -> work(400, "mirror-2"));
      scope.fork("mirror-3", () -> work(2000, "mirror-3"));
      scope.fork("mirror-4", () -> work(2000, "mirror-4"));
      scope.join(); // the first result; the failure of mirror-1 cancelled nothing
    }
    // gallery:end any-successful
  }

  static void awaitAll() throws Exception {
    // gallery:begin await-all
    try (var scope = TracedScope.open("await-all", Joiner.<String>allUntil(subtask -> false))) {
      scope.fork("thumbnail", () -> work(150, "thumbnail"));
      scope.fork("metadata", () -> fail(300, new IllegalStateException("metadata unreadable")));
      scope.fork("transcript", () -> work(600, "transcript"));
      scope.join(); // waits for every task; the predicate never asks to cancel
    }
    // gallery:end await-all
  }

  static void customJoiner() throws Exception {
    // gallery:begin custom-joiner
    record Quorum<T>(int needed, List<T> results) implements Joiner<T, List<T>, RuntimeException> {
      Quorum(int needed) {
        this(needed, Collections.synchronizedList(new ArrayList<>()));
      }

      @Override
      public boolean onComplete(Subtask<T> subtask) {
        if (subtask.state() == Subtask.State.SUCCESS) {
          results.add(subtask.get());
        }
        return results.size() >= needed; // true cancels the scope
      }

      @Override
      public List<T> result() {
        return List.copyOf(results);
      }

      @Override
      public List<T> timeout() {
        return List.copyOf(results);
      }
    }

    try (var scope = TracedScope.open("custom-joiner", new Quorum<String>(2))) {
      scope.fork("replica-1", () -> work(150, "replica-1"));
      scope.fork("replica-2", () -> work(300, "replica-2"));
      scope.fork("replica-3", () -> work(2000, "replica-3"));
      scope.join(); // returns as soon as two replicas answered
    }
    // gallery:end custom-joiner
  }

  static void timeout() throws Exception {
    // gallery:begin timeout
    try (var scope =
        TracedScope.open(
            "timeout",
            Joiner.<String>awaitAllSuccessfulOrThrow(),
            configuration -> configuration.withTimeout(Duration.ofMillis(450)))) {
      scope.fork("fast", () -> work(150, "fast"));
      scope.fork("slow-1", () -> work(2000, "slow-1"));
      scope.fork("slow-2", () -> work(2000, "slow-2"));
      try {
        scope.join();
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof StructuredTaskScope.CancelledByTimeoutException)) {
          throw e;
        }
        // the timeout cancelled the scope: "slow-1" and "slow-2" were interrupted
      }
    }
    // gallery:end timeout
  }

  static void nested() throws Exception {
    // gallery:begin nested
    try (var request = TracedScope.open("request")) {
      request.fork(
          "handle-checkout",
          () -> {
            try (var checkout = TracedScope.open("checkout")) {
              checkout.fork(
                  "take-payment",
                  () -> {
                    try (var payment = TracedScope.open("payment")) {
                      payment.fork("fraud-check", () -> work(150, "clear"));
                      payment.fork("card-auth", () -> work(300, "authorised"));
                      payment.join();
                    }
                    return "paid";
                  });
              checkout.fork("reserve-stock", () -> work(150, "reserved"));
              checkout.join();
            }
            return "checked out";
          });
      request.fork("load-session", () -> work(100, "session"));
      request.join();
    }
    // gallery:end nested
  }

  static void ignoresInterrupt() throws Exception {
    // gallery:begin ignores-interrupt
    try (var scope = TracedScope.open("ignores-interrupt")) {
      scope.fork("reserve-stock", () -> fail(60, new IllegalStateException("out of stock")));
      scope.fork("price-lookup", () -> work(2000, "price"));
      scope.fork(
          "stubborn",
          () -> {
            long end = System.nanoTime() + 700_000_000L;
            while (System.nanoTime() < end) {
              try {
                Thread.sleep(20);
              } catch (InterruptedException e) {
                // swallowed: the task keeps working although its scope was cancelled
              }
            }
            return "stubborn";
          });
      try {
        scope.join();
      } catch (ExecutionException e) {
        // the failure cancelled the scope at once, but close() below waits for "stubborn"
      }
    }
    // gallery:end ignores-interrupt
  }
}
