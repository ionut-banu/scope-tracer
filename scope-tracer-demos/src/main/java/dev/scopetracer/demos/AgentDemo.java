package dev.scopetracer.demos;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * Zero-code-change tracing demo: uses {@code StructuredTaskScope} directly (no {@code
 * TracedScope}). Run with the scope-tracer-agent attached via {@code -javaagent} to produce a
 * traced report without any source modifications.
 *
 * <p>Three tasks are forked into the scope: two succeed, one fails, causing the third to be
 * cancelled. This exercises success, failure, and cancellation paths in the generated report.
 *
 * <p><b>Run with:</b>
 *
 * <pre>{@code
 * mvn -q package -DskipTests
 * CP=$(mvn -pl scope-tracer-demos -q dependency:build-classpath -DforceStdout)
 * JARS="scope-tracer-core/target/scope-tracer-core-0.1.0-SNAPSHOT.jar:\
 * scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT.jar:\
 * scope-tracer-demos/target/scope-tracer-demos-0.1.0-SNAPSHOT.jar:$CP"
 *
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar \
 *      -cp "$JARS" dev.scopetracer.demos.AgentDemo
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
          try (var scope =
              StructuredTaskScope.open(
                  Joiner.awaitAllSuccessfulOrThrow(),
                  c -> c.withName("agent-demo").withThreadFactory(Thread.ofVirtual().factory()))) {

            var price = scope.fork(AgentDemo::fetchPrice);
            var shipping = scope.fork(AgentDemo::fetchShipping);
            var inventory = scope.fork(AgentDemo::checkInventory);

            try {
              scope.join();
              System.out.println("price    : " + price.get());
              System.out.println("shipping : " + shipping.get());
              System.out.println("inventory: " + inventory.get());
            } catch (StructuredTaskScope.FailedException e) {
              System.out.println("scope failed: " + e.getCause().getMessage());
            }
          }
        });
  }

  private static String fetchPrice() throws InterruptedException {
    Thread.sleep(80);
    return "$19.99";
  }

  private static String fetchShipping() throws InterruptedException {
    Thread.sleep(300);
    return "arrives in 3 days";
  }

  private static String checkInventory() throws InterruptedException {
    Thread.sleep(40);
    throw new RuntimeException("warehouse unreachable");
  }
}
