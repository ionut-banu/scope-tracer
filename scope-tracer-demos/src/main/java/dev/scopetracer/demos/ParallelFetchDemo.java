package dev.scopetracer.demos;

import dev.scopetracer.core.TracedScope;

/**
 * Happy-path demo: three service calls run in parallel, all succeed.
 *
 * <p>Run with:
 *
 * <pre>{@code
 * mvn -pl scope-tracer-demos exec:java -Dexec.mainClass=dev.scopetracer.demos.ParallelFetchDemo
 * }</pre>
 *
 * Produces {@code target/parallel-fetch.jfr} and {@code target/parallel-fetch.html}.
 */
public final class ParallelFetchDemo {

  private ParallelFetchDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("=== ParallelFetchDemo ===");

    DemoRunner.run(
        "parallel-fetch",
        () -> {
          try (var scope = new TracedScope("parallel-fetch")) {
            var pricing = scope.fork(ParallelFetchDemo::fetchPrice);
            var inventory = scope.fork(ParallelFetchDemo::fetchInventory);
            var shipping = scope.fork(ParallelFetchDemo::fetchShipping);
            scope.join();
            System.out.println("price     : " + pricing.get());
            System.out.println("inventory : " + inventory.get());
            System.out.println("shipping  : " + shipping.get());
          }
        });
  }

  private static String fetchPrice() throws InterruptedException {
    Thread.sleep(100);
    return "$29.99";
  }

  private static String fetchInventory() throws InterruptedException {
    Thread.sleep(150);
    return "42 units in stock";
  }

  private static String fetchShipping() throws InterruptedException {
    Thread.sleep(80);
    return "arrives in 2 days";
  }
}
