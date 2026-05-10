package com.ionutbanu.scopetracer.demos;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * Zero-code-change tracing demo: uses {@code StructuredTaskScope} directly (no {@code
 * TracedScope}). Run with the scope-tracer-agent attached via {@code -javaagent} to produce a
 * traced report without any source modifications.
 *
 * <p>Demonstrates two scope-naming modes:
 *
 * <ul>
 *   <li><b>Named scope</b> — {@code Config.withName("checkout-flow")} is used; the report shows the
 *       configured name.
 *   <li><b>Anonymous scope</b> — no name configured; the agent derives the name from the call-site
 *       stack frame.
 * </ul>
 *
 * <p>Also demonstrates that {@code fork(Runnable)} tasks are traced automatically: the JDK
 * delegates {@code fork(Runnable)} to {@code fork(Callable)} internally, so no additional
 * instrumentation is required.
 *
 * <p><b>Run with:</b>
 *
 * <pre>{@code
 * mvn -q package -DskipTests
 * CP=$(mvn -pl scope-tracer-demos -q dependency:build-classpath -DforceStdout)
 * JARS="scope-tracer-core/target/scope-tracer-core-0.1.0-SNAPSHOT.jar:scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT.jar:scope-tracer-demos/target/scope-tracer-demos-0.1.0-SNAPSHOT.jar:$CP"
 *
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar \
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.AgentDemo
 * }</pre>
 *
 * Produces {@code target/agent-demo.jfr} and {@code target/agent-demo.html}.
 */
public final class AgentDemo {

  private AgentDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("=== AgentDemo (zero-code-change tracing) ===");

    DemoRunner.run(
        "agent-demo",
        () -> {
          // Named scope: Config.withName() → report shows "checkout-flow"
          try (var scope =
              StructuredTaskScope.open(
                  Joiner.awaitAllSuccessfulOrThrow(),
                  c ->
                      c.withName("checkout-flow")
                          .withThreadFactory(Thread.ofVirtual().factory()))) {

            var price = scope.fork(() -> fetchPrice());
            // fork(Runnable) — also traced; delegates to fork(Callable) internally
            scope.fork((Runnable) () -> validateCart());
            var shipping = scope.fork(() -> fetchShipping());

            try {
              scope.join();
              System.out.println("price    : " + price.get());
              System.out.println("shipping : " + shipping.get());
            } catch (StructuredTaskScope.FailedException e) {
              System.out.println("scope failed: " + e.getCause().getMessage());
            }
          }

          // Anonymous scope: no Config.withName() → name derived from call-site stack frame
          try (var scope =
              StructuredTaskScope.open(
                  Joiner.awaitAllSuccessfulOrThrow(),
                  c -> c.withThreadFactory(Thread.ofVirtual().factory()))) {

            scope.fork(() -> fetchInventory());
            scope.join();
          }
        });
  }

  private static String fetchPrice() throws InterruptedException {
    Thread.sleep(80);
    return "$19.99";
  }

  private static String fetchShipping() throws InterruptedException {
    Thread.sleep(120);
    return "arrives in 3 days";
  }

  // Runnable tasks cannot declare throws InterruptedException; re-interrupt the thread instead.
  private static void validateCart() {
    try {
      Thread.sleep(60);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String fetchInventory() throws InterruptedException {
    Thread.sleep(50);
    return "42 in stock";
  }
}
