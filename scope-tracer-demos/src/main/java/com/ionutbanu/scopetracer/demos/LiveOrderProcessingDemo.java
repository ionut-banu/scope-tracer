package com.ionutbanu.scopetracer.demos;

import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Long-running e-commerce order-processing demo with multi-level scope nesting and on-demand JFR
 * monitoring — using plain {@link StructuredTaskScope} with no {@code TracedScope} dependency.
 *
 * <p>This demo is intentionally written as if it were existing production code that you <em>do not
 * own</em>: it uses the standard JDK API only. Tracing is added entirely from the outside by
 * attaching the scope-tracer agent at launch. The only concession to observability is naming each
 * scope via {@code c.withName()} — a single-argument config call that is part of the JDK API.
 *
 * <p>Runs continuously until Ctrl+C, processing one order every 2–3 seconds through a two-stage
 * pipeline:
 *
 * <ol>
 *   <li><b>Fulfillment</b> ({@code order-processing-NNN}) — three branches in parallel:
 *       <ul>
 *         <li>Order validation (fast sanity check)
 *         <li>Payment pipeline ({@code payment-pipeline-NNN}) — fraud check + card authorisation in
 *             parallel, then a sequential capture step
 *         <li>Inventory reservation ({@code inventory-reservation-NNN}) — two warehouses checked in
 *             parallel, then stock allocated
 *       </ul>
 *   <li><b>Dispatch</b> ({@code order-dispatch-NNN}) — courier assignment, label generation, and
 *       customer notification in parallel. Only runs if fulfillment succeeded.
 * </ol>
 *
 * <p>The application manages no JFR recording itself. Control tracing from a second terminal using
 * {@code jcmd} — the PID and ready-to-paste commands are printed at startup.
 *
 * <p><b>Step 1 — build and launch with the agent:</b>
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
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.LiveOrderProcessingDemo
 * }</pre>
 *
 * <p><b>Step 2 — in a second terminal, copy the {@code jcmd} commands printed at startup.</b>
 *
 * <p><b>Step 3 — open the HTML report in your browser.</b>
 *
 * <p>Press Ctrl+C to stop the service.
 */
public final class LiveOrderProcessingDemo {

  private LiveOrderProcessingDemo() {}

  // ── Simulated catalogue ───────────────────────────────────────────────────────

  private static final List<String> CUSTOMERS =
      List.of("alice", "bob", "carol", "dave", "eve", "frank", "grace", "heidi");

  private static final List<List<String>> ITEM_SETS =
      List.of(
          List.of("widget", "gadget"),
          List.of("premium-widget"),
          List.of("basic-item", "basic-item"),
          List.of("deluxe-gadget"),
          List.of("budget-item"),
          List.of("pro-kit", "addon"),
          List.of("ultra-widget"));

  // ── Entry point ──────────────────────────────────────────────────────────────

  public static void main(String[] args) throws InterruptedException {
    long pid = ProcessHandle.current().pid();
    String agentJar = "scope-tracer-agent/target/scope-tracer-agent-0.1.0-SNAPSHOT-agent.jar";
    String analyzerJar =
        "scope-tracer-analyzer/target/scope-tracer-analyzer-0.1.0-SNAPSHOT-executable.jar";

    System.out.println("╔══════════════════════════════════════════════════════════════╗");
    System.out.println("║      LiveOrderProcessingDemo — on-demand monitoring          ║");
    System.out.println("╚══════════════════════════════════════════════════════════════╝");
    System.out.println();
    System.out.println("  Service PID: " + pid);
    System.out.println();
    System.out.println("  NOTE: launch with the agent to enable tracing:");
    System.out.printf("    -javaagent:%s%n", agentJar);
    System.out.println();
    System.out.println("  ── Turn monitoring ON ──────────────────────────────────────");
    System.out.printf("  jcmd %d JFR.start name=trace filename=/tmp/orders.jfr%n", pid);
    System.out.println();
    System.out.println("  ── Turn monitoring OFF (dumps the file) ────────────────────");
    System.out.printf("  jcmd %d JFR.stop name=trace%n", pid);
    System.out.println();
    System.out.println("  ── Analyze ─────────────────────────────────────────────────");
    System.out.printf(
        "  java --enable-preview \\%n"
            + "       -jar %s \\%n"
            + "       /tmp/orders.jfr /tmp/orders.html%n",
        analyzerJar);
    System.out.println("  open /tmp/orders.html");
    System.out.println();
    System.out.println("  You can run JFR.start / JFR.stop as many times as you like.");
    System.out.println("  Each stop produces a new .jfr capturing only that window.");
    System.out.println();
    System.out.println("  Press Ctrl+C to stop.");
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
      int n = counter.incrementAndGet();
      String orderId = String.format("%03d", n);
      String customerId = CUSTOMERS.get(rng.nextInt(CUSTOMERS.size()));
      double amount = 20.0 + rng.nextInt(900) + rng.nextDouble(); // £20–£920
      List<String> items = ITEM_SETS.get(rng.nextInt(ITEM_SETS.size()));

      processOrder(orderId, customerId, amount, items, rng);

      try {
        Thread.sleep(2000 + rng.nextInt(1500));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ── Stage orchestration ──────────────────────────────────────────────────────

  private static void processOrder(
      String orderId, String customerId, double amount, List<String> items, RandomGenerator rng) {
    System.out.printf(
        "[ORD-%s] %-8s  £%.2f  %d item(s)%n", orderId, customerId, amount, items.size());

    if (!runFulfillment(orderId, amount, items, rng)) return;

    runDispatch(orderId, rng);
  }

  private static boolean runFulfillment(
      String orderId, double amount, List<String> items, RandomGenerator rng) {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName("order-processing-" + orderId))) {
      var validation = scope.fork(() -> validateOrder(rng));
      var payment = scope.fork(() -> runPaymentPipeline(orderId, amount, rng));
      var inventory = scope.fork(() -> runInventoryReservation(orderId, items, rng));
      scope.join();
      System.out.printf(
          "[ORD-%s] ✓ fulfilled  payment=%s  stock=%s%n", orderId, payment.get(), inventory.get());
      return true;
    } catch (StructuredTaskScope.FailedException e) {
      Throwable cause = e.getCause();
      while (cause instanceof StructuredTaskScope.FailedException && cause.getCause() != null) {
        cause = cause.getCause();
      }
      System.out.printf("[ORD-%s] ✗ fulfillment failed: %s%n", orderId, cause.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void runDispatch(String orderId, RandomGenerator rng) {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName("order-dispatch-" + orderId))) {
      scope.fork(() -> assignCourier(rng));
      scope.fork(() -> generateLabel(rng));
      scope.fork(() -> notifyCustomer(rng));
      scope.join();
      System.out.printf("[ORD-%s] ✓ dispatched%n", orderId);
    } catch (StructuredTaskScope.FailedException e) {
      System.out.printf("[ORD-%s] ✗ dispatch failed: %s%n", orderId, e.getCause().getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ── Nested scopes ────────────────────────────────────────────────────────────

  private static String runPaymentPipeline(String orderId, double amount, RandomGenerator rng)
      throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName("payment-pipeline-" + orderId))) {
      var fraud = scope.fork(() -> checkFraud(orderId, amount, rng));
      var auth = scope.fork(() -> authorizeCard(rng));
      scope.join(); // throws FailedException if fraud check rejects
      return capturePayment(auth.get(), rng);
    } catch (StructuredTaskScope.FailedException e) {
      throw new RuntimeException(e.getCause().getMessage(), e.getCause());
    }
  }

  private static String runInventoryReservation(
      String orderId, List<String> items, RandomGenerator rng) throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("inventory-reservation-" + orderId))) {
      var warehouseA = scope.fork(() -> checkWarehouse("warehouse-A", rng));
      var warehouseB = scope.fork(() -> checkWarehouse("warehouse-B", rng));
      scope.join();
      return allocateStock(warehouseA.get(), warehouseB.get(), items, rng);
    }
  }

  // ── Leaf tasks ───────────────────────────────────────────────────────────────

  private static String validateOrder(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(15 + rng.nextInt(25));
    return "valid";
  }

  private static String checkFraud(String orderId, double amount, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(80 + rng.nextInt(120));
    if (amount >= 500.0 && rng.nextInt(10) < 3) {
      throw new RuntimeException("fraud-check: high-risk transaction declined for ORD-" + orderId);
    }
    return "cleared";
  }

  private static String authorizeCard(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(60 + rng.nextInt(90));
    return "auth-" + Integer.toHexString(rng.nextInt(0xFFFF)).toUpperCase();
  }

  private static String capturePayment(String authCode, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(30 + rng.nextInt(50));
    return "captured:" + authCode;
  }

  private static int checkWarehouse(String warehouse, RandomGenerator rng)
      throws InterruptedException {
    boolean slow = "warehouse-B".equals(warehouse) && rng.nextInt(4) == 0;
    Thread.sleep(slow ? 160 + rng.nextInt(100) : 40 + rng.nextInt(80));
    return 5 + rng.nextInt(95);
  }

  private static String allocateStock(
      int unitsA, int unitsB, List<String> items, RandomGenerator rng) throws InterruptedException {
    Thread.sleep(20 + rng.nextInt(30));
    String source = unitsA >= unitsB ? "warehouse-A" : "warehouse-B";
    return source + " (×" + items.size() + ")";
  }

  private static String assignCourier(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(50 + rng.nextInt(100));
    return "courier assigned";
  }

  private static String generateLabel(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(35 + rng.nextInt(65));
    return "label generated";
  }

  private static String notifyCustomer(RandomGenerator rng) throws InterruptedException {
    Thread.sleep(20 + rng.nextInt(40));
    return "notified";
  }
}
