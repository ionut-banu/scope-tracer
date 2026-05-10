package com.ionutbanu.scopetracer.demos;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Long-running service demo that shows how to use JFR start/stop for on-demand monitoring of a
 * running application — no restart required.
 *
 * <p>The service simulates an order-processing loop: every ~2 seconds it handles one order by
 * running three parallel service calls (pricing, inventory, shipping) inside a {@code
 * StructuredTaskScope}. Occasionally one call is slow (visible as the critical-path task in the
 * report) and occasionally pricing fails (visible as a failed scope).
 *
 * <p>The application itself manages no JFR recording. You control tracing from outside using {@code
 * jcmd}. The PID and ready-to-paste commands are printed at startup.
 *
 * <p><b>Step 1 — build and launch (with the agent):</b>
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
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.LiveServiceDemo
 * }</pre>
 *
 * <p><b>Step 2 — in a second terminal, copy the jcmd commands printed at startup.</b>
 *
 * <p><b>Step 3 — open the HTML report in your browser.</b>
 *
 * <p>Press Ctrl+C to stop the service.
 */
public final class LiveServiceDemo {

  private LiveServiceDemo() {}

  public static void main(String[] args) throws InterruptedException {
    long pid = ProcessHandle.current().pid();
    String analyzerJar =
        "scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT-executable.jar";

    System.out.println("╔══════════════════════════════════════════════════════════════╗");
    System.out.println("║              LiveServiceDemo — on-demand monitoring          ║");
    System.out.println("╚══════════════════════════════════════════════════════════════╝");
    System.out.println();
    System.out.println("  Service PID: " + pid);
    System.out.println();
    System.out.println("  ── Turn monitoring ON ──────────────────────────────────────");
    System.out.printf("  jcmd %d JFR.start name=trace filename=/tmp/scope-trace.jfr%n", pid);
    System.out.println();
    System.out.println("  ── Turn monitoring OFF (dumps the file) ────────────────────");
    System.out.printf("  jcmd %d JFR.stop name=trace%n", pid);
    System.out.println();
    System.out.println("  ── Analyze ─────────────────────────────────────────────────");
    System.out.printf(
        "  java --enable-preview \\%n"
            + "       -jar %s \\%n"
            + "       /tmp/scope-trace.jfr /tmp/report.html%n",
        analyzerJar);
    System.out.println("  open /tmp/report.html");
    System.out.println();
    System.out.println("  You can run JFR.start / JFR.stop as many times as you like.");
    System.out.println("  Each stop produces a new .jfr file capturing only that window.");
    System.out.println();
    System.out.println("  Press Ctrl+C to stop the service.");
    System.out.println("──────────────────────────────────────────────────────────────");
    System.out.println();

    var rng = RandomGenerator.getDefault();
    var counter = new AtomicInteger(0);
    Thread mainThread = Thread.currentThread();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("\n[service] shutting down — goodbye.");
                  mainThread.interrupt();
                }));

    while (!Thread.currentThread().isInterrupted()) {
      int reqId = counter.incrementAndGet();
      processOrder(reqId, rng);
      try {
        Thread.sleep(1500 + rng.nextInt(1000));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void processOrder(int reqId, RandomGenerator rng) {
    System.out.printf("[order-%03d] → dispatching parallel calls...%n", reqId);
    long start = System.currentTimeMillis();

    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("order-" + reqId).withThreadFactory(Thread.ofVirtual().factory()))) {

      var price = scope.fork(() -> fetchPrice(rng));
      var inventory = scope.fork(() -> fetchInventory(rng));
      var shipping = scope.fork(() -> fetchShipping(rng));

      scope.join();
      long elapsed = System.currentTimeMillis() - start;
      System.out.printf(
          "[order-%03d] ✓ %dms  price=%-8s  inventory=%-14s  shipping=%s%n",
          reqId, elapsed, price.get(), inventory.get(), shipping.get());

    } catch (StructuredTaskScope.FailedException e) {
      long elapsed = System.currentTimeMillis() - start;
      System.out.printf(
          "[order-%03d] ✗ %dms  failed: %s%n", reqId, elapsed, e.getCause().getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Simulates a pricing-service call. Fails ~10% of the time so the report shows red failed scopes
   * alongside successful ones.
   */
  private static String fetchPrice(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(50 + rng.nextInt(80));
    if (rng.nextInt(10) == 0) {
      throw new RuntimeException("pricing-service unavailable");
    }
    return "$" + (10 + rng.nextInt(90)) + ".99";
  }

  /**
   * Simulates an inventory check. Always succeeds; moderate, stable latency so it rarely appears as
   * the critical path.
   */
  private static String fetchInventory(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(40 + rng.nextInt(40));
    return (1 + rng.nextInt(200)) + " in stock";
  }

  /**
   * Simulates a shipping-estimate call. 25% of the time it is significantly slower than its
   * siblings — this makes it the critical-path task in the HTML report (gold outline on the SVG
   * bar, "← critical path +Xms" annotation in the table).
   */
  private static String fetchShipping(RandomGenerator rng) throws InterruptedException {
    boolean slow = rng.nextInt(4) == 0; // 25% chance of being the bottleneck
    Thread.sleep(slow ? 180 + rng.nextInt(120) : 30 + rng.nextInt(40));
    return "arrives in " + (1 + rng.nextInt(7)) + " days";
  }
}
