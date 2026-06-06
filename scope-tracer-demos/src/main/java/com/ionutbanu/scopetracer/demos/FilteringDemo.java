package com.ionutbanu.scopetracer.demos;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

/**
 * Demonstrates the agent's capture-filter and sampling arguments.
 *
 * <p>Opens five scope types every iteration:
 *
 * <ul>
 *   <li>{@code payment-flow} — critical business scope
 *   <li>{@code checkout-flow} — critical business scope
 *   <li>{@code inventory-check} — background maintenance scope
 *   <li>{@code audit-log} — noisy compliance scope (good exclude candidate)
 *   <li>{@code health-probe} — repeated many times; ideal for {@code sample.rate} demo
 * </ul>
 *
 * <p><b>Scenario 1 — no filters (baseline): all 5 types appear in the report.</b>
 *
 * <pre>{@code
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-<VERSION>-agent.jar \
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
 * }</pre>
 *
 * <p><b>Scenario 2 — include only scopes matching {@code *-flow}:</b>
 *
 * <pre>{@code
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-<VERSION>-agent.jar=include.name=*-flow \
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
 * }</pre>
 *
 * <p><b>Scenario 3 — exclude the noisy audit-log scope:</b>
 *
 * <pre>{@code
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-<VERSION>-agent.jar=exclude.name=audit-* \
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
 * }</pre>
 *
 * <p><b>Scenario 4 — 30 % sample rate (health-probe produces ~3 of 10 captured):</b>
 *
 * <pre>{@code
 * java --enable-preview \
 *      -javaagent:scope-tracer-agent/target/scope-tracer-agent-<VERSION>-agent.jar=sample.rate=0.3 \
 *      -cp "$JARS" com.ionutbanu.scopetracer.demos.FilteringDemo
 * }</pre>
 *
 * <p>Each run writes {@code target/filtering-demo.jfr} and {@code target/filtering-demo.html}.
 */
public final class FilteringDemo {

  private FilteringDemo() {}

  public static void main(String[] args) throws Exception {
    System.out.println("=== FilteringDemo (capture filters & sampling) ===");
    System.out.println("Opening scopes: payment-flow, checkout-flow, inventory-check,");
    System.out.println("                audit-log, health-probe (×10)");
    System.out.println("The agent filter args control which ones appear in the report.");
    System.out.println();

    DemoRunner.run(
        "filtering-demo",
        () -> {
          runPaymentFlow();
          runCheckoutFlow();
          runInventoryCheck();
          runAuditLog();

          // health-probe runs 10 times to make sample.rate=0.3 clearly visible
          for (int i = 0; i < 10; i++) {
            runHealthProbe(i + 1);
          }
        });
  }

  // --- individual scopes -------------------------------------------------------

  private static void runPaymentFlow() throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("payment-flow").withThreadFactory(Thread.ofVirtual().factory()))) {

      scope.fork(() -> chargeCard());
      scope.fork(() -> sendReceipt());
      scope.join();
    }
    System.out.println("  payment-flow      done");
  }

  private static void runCheckoutFlow() throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("checkout-flow").withThreadFactory(Thread.ofVirtual().factory()))) {

      scope.fork(() -> validateCart());
      scope.fork(() -> reserveStock());
      scope.fork(() -> applyDiscount());
      scope.join();
    }
    System.out.println("  checkout-flow     done");
  }

  private static void runInventoryCheck() throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("inventory-check").withThreadFactory(Thread.ofVirtual().factory()))) {

      scope.fork(() -> queryWarehouse());
      scope.join();
    }
    System.out.println("  inventory-check   done");
  }

  private static void runAuditLog() throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("audit-log").withThreadFactory(Thread.ofVirtual().factory()))) {

      scope.fork(() -> writeAuditEntry());
      scope.fork(() -> notifyCompliance());
      scope.join();
    }
    System.out.println("  audit-log         done");
  }

  private static void runHealthProbe(int n) throws Exception {
    try (var scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            c -> c.withName("health-probe").withThreadFactory(Thread.ofVirtual().factory()))) {

      scope.fork(() -> pingDatabase());
      scope.fork(() -> pingCache());
      scope.join();
    }
    System.out.println("  health-probe #" + n + "   done");
  }

  // --- simulated tasks ---------------------------------------------------------

  private static String chargeCard() throws InterruptedException {
    Thread.sleep(90);
    return "charged";
  }

  private static String sendReceipt() throws InterruptedException {
    Thread.sleep(40);
    return "sent";
  }

  private static String validateCart() throws InterruptedException {
    Thread.sleep(30);
    return "valid";
  }

  private static String reserveStock() throws InterruptedException {
    Thread.sleep(60);
    return "reserved";
  }

  private static String applyDiscount() throws InterruptedException {
    Thread.sleep(20);
    return "applied";
  }

  private static String queryWarehouse() throws InterruptedException {
    Thread.sleep(50);
    return "42 units";
  }

  private static String writeAuditEntry() throws InterruptedException {
    Thread.sleep(15);
    return "written";
  }

  private static String notifyCompliance() throws InterruptedException {
    Thread.sleep(10);
    return "notified";
  }

  private static String pingDatabase() throws InterruptedException {
    Thread.sleep(8);
    return "ok";
  }

  private static String pingCache() throws InterruptedException {
    Thread.sleep(5);
    return "ok";
  }
}
