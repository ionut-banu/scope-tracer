package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.core.TracedScope;

/**
 * Nested scopes demo: an outer scope forks a task that opens its own inner {@code TracedScope}.
 *
 * <p>The recording captures two separate scopes with overlapping time windows, illustrating how
 * hierarchical task trees appear in the JFR output and HTML report.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * mvn -pl scope-tracer-demos exec:java -Dexec.mainClass=com.ionutbanu.scopetracer.demos.NestedScopesDemo
 * }</pre>
 *
 * Produces {@code target/nested-scopes.jfr} and {@code target/nested-scopes.html}.
 */
public final class NestedScopesDemo {

  private NestedScopesDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("=== NestedScopesDemo ===");

    DemoRunner.run(
        "nested-scopes",
        () -> {
          try (var outer = TracedScope.open("order-processing")) {
            var payment = outer.fork(NestedScopesDemo::processPayment);
            var notification = outer.fork(NestedScopesDemo::sendNotification);
            outer.join();
            System.out.println("payment      : " + payment.get());
            System.out.println("notification : " + notification.get());
          }
        });
  }

  private static String processPayment() throws Exception {
    // Inner scope: authorise and capture run in parallel within the payment task.
    try (var inner = TracedScope.open("payment-steps")) {
      var authorise = inner.fork(NestedScopesDemo::authorise);
      var capture = inner.fork(NestedScopesDemo::capture);
      inner.join();
      return authorise.get() + " + " + capture.get();
    }
  }

  private static String authorise() throws InterruptedException {
    Thread.sleep(80);
    return "authorised";
  }

  private static String capture() throws InterruptedException {
    Thread.sleep(60);
    return "captured";
  }

  private static String sendNotification() throws InterruptedException {
    Thread.sleep(120);
    return "notification sent";
  }
}
