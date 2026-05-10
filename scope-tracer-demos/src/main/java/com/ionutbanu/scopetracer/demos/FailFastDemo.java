package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.util.concurrent.StructuredTaskScope;

/**
 * Cancellation demo: one task fails quickly, causing its still-running sibling to be cancelled.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * mvn -pl scope-tracer-demos exec:java -Dexec.mainClass=com.ionutbanu.scopetracer.demos.FailFastDemo
 * }</pre>
 *
 * Produces {@code target/fail-fast.jfr} and {@code target/fail-fast.html}.
 */
public final class FailFastDemo {

  private FailFastDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("=== FailFastDemo ===");

    DemoRunner.run(
        "fail-fast",
        () -> {
          try (var scope = TracedScope.open("fail-fast")) {
            scope.fork(FailFastDemo::checkInventory);
            scope.fork(FailFastDemo::slowEnrichment);
            try {
              scope.join();
            } catch (StructuredTaskScope.FailedException e) {
              System.out.println("scope failed: " + e.getCause().getMessage());
            }
          }
        });
  }

  private static String checkInventory() throws InterruptedException {
    Thread.sleep(50);
    throw new RuntimeException("out of stock");
  }

  private static String slowEnrichment() throws InterruptedException {
    Thread.sleep(500);
    return "enriched";
  }
}
