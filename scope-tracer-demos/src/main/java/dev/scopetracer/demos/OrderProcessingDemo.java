package dev.scopetracer.demos;

import dev.scopetracer.core.TracedScope;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.random.RandomGenerator;

/**
 * Realistic e-commerce order processing pipeline demonstrating multi-level scope nesting.
 *
 * <p>Each order goes through two sequential stages:
 *
 * <ol>
 *   <li><b>Fulfillment</b> ({@code order-processing-ORD-NNN}) — three parallel tasks: order
 *       validation, a nested {@code payment-pipeline} scope (fraud check + card authorisation in
 *       parallel, then a sequential capture step), and a nested {@code inventory-reservation} scope
 *       (two warehouses checked in parallel, then stock allocated).
 *   <li><b>Dispatch</b> ({@code order-dispatch-ORD-NNN}) — three parallel tasks: courier
 *       assignment, label generation, and customer notification. Only runs if fulfillment
 *       succeeded.
 * </ol>
 *
 * <p>Interesting things to observe in the HTML report:
 *
 * <ul>
 *   <li>The critical path alternates between the payment and inventory branches — whichever takes
 *       longer determines the fulfillment scope duration. Visible as the gold-outlined bar.
 *   <li>High-value orders (≥ £500) have a 30 % chance of failing the fraud check. When they do, the
 *       {@code payment-pipeline} and its parent {@code order-processing-*} scope both turn red, and
 *       the {@code order-dispatch-*} scope is skipped entirely.
 *   <li>Warehouse B is occasionally slow, making it the critical-path task inside {@code
 *       inventory-reservation}.
 *   <li>The capture step runs sequentially <em>after</em> the parallel fraud + auth tasks, so the
 *       {@code payment-pipeline} scope bar extends slightly past the last task bar — a real-world
 *       pattern where a scope mixes parallel fan-out with sequential follow-up work.
 *   <li>All {@code order-processing-*} scopes are grouped in the report, as are {@code
 *       order-dispatch-*} scopes.
 *   <li>Nested scopes ({@code payment-pipeline}, {@code inventory-reservation}) appear indented
 *       below the task that opened them, with a breadcrumb link.
 * </ul>
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
 * java --enable-preview -cp "$JARS" dev.scopetracer.demos.OrderProcessingDemo
 * }</pre>
 *
 * Produces {@code target/order-processing.jfr} and {@code target/order-processing.html}.
 */
public final class OrderProcessingDemo {

  private OrderProcessingDemo() {}

  // ── Domain model ─────────────────────────────────────────────────────────────

  record Order(String id, String customerId, double amount, List<String> items) {}

  // ── Entry point ──────────────────────────────────────────────────────────────

  public static void main(String[] args) throws Exception {
    System.out.println("=== OrderProcessingDemo ===");

    var orders =
        List.of(
            new Order("ORD-001", "cust-alice", 149.99, List.of("widget", "gadget")),
            new Order("ORD-002", "cust-bob", 849.99, List.of("premium-widget")),
            new Order("ORD-003", "cust-carol", 39.99, List.of("basic-item", "basic-item")),
            new Order("ORD-004", "cust-dave", 649.99, List.of("deluxe-gadget")),
            new Order("ORD-005", "cust-eve", 29.99, List.of("budget-item")));

    DemoRunner.run(
        "order-processing",
        () -> {
          var rng = RandomGenerator.getDefault();
          for (var order : orders) {
            processOrder(order, rng);
          }
        });
  }

  // ── Stage orchestration ──────────────────────────────────────────────────────

  private static void processOrder(Order order, RandomGenerator rng) throws InterruptedException {
    System.out.printf(
        "[%s] %-12s  £%.2f  %d item(s)%n",
        order.id(), order.customerId(), order.amount(), order.items().size());

    if (!runFulfillment(order, rng)) return; // dispatch only on success

    runDispatch(order, rng);
  }

  /**
   * Fulfillment stage: validation, payment pipeline, and inventory reservation run in parallel. The
   * slowest of the three determines the scope's total duration (and its critical-path task).
   */
  private static boolean runFulfillment(Order order, RandomGenerator rng)
      throws InterruptedException {
    try (var scope = TracedScope.open("order-processing-" + order.id())) {
      var validation = scope.fork(() -> validateOrder(order, rng));
      var payment = scope.fork(() -> runPaymentPipeline(order, rng));
      var inventory = scope.fork(() -> runInventoryReservation(order, rng));
      scope.join();
      System.out.printf(
          "[%s] ✓ fulfilled  payment=%s  stock=%s%n", order.id(), payment.get(), inventory.get());
      return true;
    } catch (StructuredTaskScope.FailedException e) {
      // Unwrap nested FailedExceptions to surface the original error message.
      Throwable cause = e.getCause();
      while (cause instanceof StructuredTaskScope.FailedException && cause.getCause() != null) {
        cause = cause.getCause();
      }
      System.out.printf("[%s] ✗ fulfillment failed: %s%n", order.id(), cause.getMessage());
      return false;
    }
  }

  /** Dispatch stage: courier, label, and notification run in parallel after fulfillment. */
  private static void runDispatch(Order order, RandomGenerator rng) throws InterruptedException {
    try (var scope = TracedScope.open("order-dispatch-" + order.id())) {
      scope.fork(() -> assignCourier(order, rng));
      scope.fork(() -> generateLabel(order, rng));
      scope.fork(() -> notifyCustomer(order, rng));
      scope.join();
      System.out.printf("[%s] ✓ dispatched%n", order.id());
    } catch (StructuredTaskScope.FailedException e) {
      System.out.printf("[%s] ✗ dispatch failed: %s%n", order.id(), e.getCause().getMessage());
    }
  }

  // ── Nested scopes ────────────────────────────────────────────────────────────

  /**
   * Payment pipeline: fraud check and card authorisation run in parallel, then card capture runs
   * sequentially after both complete. The sequential capture step is intentionally <em>not</em> a
   * separate task — it runs after {@code scope.join()} and extends the scope's total duration
   * beyond the last task bar. This mirrors a common real-world pattern where a scope mixes parallel
   * fan-out with sequential follow-up work.
   *
   * <p>The scope is named {@code payment-pipeline-NNN} (numeric order suffix) so that the JFR
   * parser can track each order's pipeline independently. The renderer groups all {@code
   * payment-pipeline-*} scopes under a single "payment-pipeline" group heading.
   */
  private static String runPaymentPipeline(Order order, RandomGenerator rng) throws Exception {
    try (var scope = TracedScope.open("payment-pipeline-" + numericId(order))) {
      var fraud = scope.fork(() -> checkFraud(order, rng));
      var auth = scope.fork(() -> authorizeCard(order, rng));
      scope.join(); // throws FailedException if fraud check rejects
      // Sequential step: runs after parallel tasks complete, inside the same scope
      return capturePayment(auth.get(), rng);
    } catch (StructuredTaskScope.FailedException e) {
      // Re-throw as RuntimeException so the outer scope sees the original cause directly,
      // rather than a wrapped FailedException chain that obscures the root error message.
      throw new RuntimeException(e.getCause().getMessage(), e.getCause());
    }
  }

  /**
   * Inventory reservation: two warehouses are checked in parallel, then stock is allocated from
   * whichever has more units available. Warehouse B is occasionally slow (~25 % of calls), making
   * it the critical-path task in this scope.
   */
  private static String runInventoryReservation(Order order, RandomGenerator rng) throws Exception {
    try (var scope = TracedScope.open("inventory-reservation-" + numericId(order))) {
      var warehouseA = scope.fork(() -> checkWarehouse("warehouse-A", order, rng));
      var warehouseB = scope.fork(() -> checkWarehouse("warehouse-B", order, rng));
      scope.join();
      return allocateStock(warehouseA.get(), warehouseB.get(), order, rng);
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Extracts the numeric suffix from an order ID: {@code "ORD-001"} → {@code "001"}. */
  private static String numericId(Order order) {
    return order.id().replaceAll("\\D+", "");
  }

  // ── Leaf tasks ───────────────────────────────────────────────────────────────

  /** Quick sanity check: address, item availability constraints, region limits. */
  private static String validateOrder(Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(15 + rng.nextInt(25));
    return "valid";
  }

  /**
   * Fraud detection service call. High-value orders (≥ £500) carry a 30 % risk of being declined —
   * when this happens the whole fulfillment pipeline fails and is skipped in the report as a red
   * failed scope.
   */
  private static String checkFraud(Order order, RandomGenerator rng) throws InterruptedException {
    Thread.sleep(80 + rng.nextInt(120));
    if (order.amount() >= 500.0 && rng.nextInt(10) < 3) {
      throw new RuntimeException("fraud-check: high-risk transaction declined for " + order.id());
    }
    return "cleared";
  }

  /** Card authorisation round-trip to the payment gateway. */
  private static String authorizeCard(Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(60 + rng.nextInt(90));
    return "auth-" + Integer.toHexString(rng.nextInt(0xFFFF)).toUpperCase();
  }

  /**
   * Sequential card capture — runs after authorisation completes. Extends the {@code
   * payment-pipeline} scope duration beyond the last parallel task bar.
   */
  private static String capturePayment(String authCode, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(30 + rng.nextInt(50));
    return "captured:" + authCode;
  }

  /**
   * Warehouse stock query. Warehouse B is intentionally biased to be slow 25 % of the time so the
   * critical-path highlight alternates between the two warehouses across orders.
   */
  private static int checkWarehouse(String warehouse, Order order, RandomGenerator rng)
      throws InterruptedException {
    boolean slow = "warehouse-B".equals(warehouse) && rng.nextInt(4) == 0;
    Thread.sleep(slow ? 160 + rng.nextInt(100) : 40 + rng.nextInt(80));
    return 5 + rng.nextInt(95); // units available
  }

  /** Allocates from the warehouse with more stock; records the chosen location. */
  private static String allocateStock(int unitsA, int unitsB, Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(20 + rng.nextInt(30));
    String source = unitsA >= unitsB ? "warehouse-A" : "warehouse-B";
    return source + " (×" + order.items().size() + ")";
  }

  /** Courier matching — connects order weight/destination to available courier capacity. */
  private static String assignCourier(Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(50 + rng.nextInt(100));
    return "courier assigned";
  }

  /** PDF label generation including barcode encoding and customs declarations. */
  private static String generateLabel(Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(35 + rng.nextInt(65));
    return "label generated";
  }

  /** Sends confirmation email and push notification to the customer. */
  private static String notifyCustomer(Order order, RandomGenerator rng)
      throws InterruptedException {
    Thread.sleep(20 + rng.nextInt(40));
    return "notified";
  }
}
