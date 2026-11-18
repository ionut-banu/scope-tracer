package com.ionutbanu.scopetracer.demos;

import static com.ionutbanu.scopetracer.demos.Shape.cancelled;
import static com.ionutbanu.scopetracer.demos.Shape.failed;
import static com.ionutbanu.scopetracer.demos.Shape.nested;
import static com.ionutbanu.scopetracer.demos.Shape.scope;
import static com.ionutbanu.scopetracer.demos.Shape.succeeded;

import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.demos.Shape.ExpectedScope;
import com.ionutbanu.scopetracer.demos.Shape.TimingCheck;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The nine sections of the gallery, in page order: the code of each, the shape its report must
 * show, and the text around it. The report cannot name the join policy (no event carries it), so
 * each section says what the report does show and where it stops.
 */
final class GalleryCatalog {

  private GalleryCatalog() {}

  private static final String SCENARIOS_SOURCE =
      "scope-tracer-demos/src/main/java/com/ionutbanu/scopetracer/demos/GalleryScenarios.java";
  private static final String ORDERS_SOURCE =
      "scope-tracer-demos/src/main/java/com/ionutbanu/scopetracer/demos/OrderProcessingDemo.java";

  /** The timeout of the {@code timeout} scenario; its snippet shows the same number. */
  static final long TIMEOUT_MILLIS = 450;

  static final List<GalleryScenario> ALL =
      List.of(
          failFast(),
          allSuccessful(),
          anySuccessful(),
          awaitAll(),
          customJoiner(),
          timeout(),
          nestedScopes(),
          ignoresInterrupt(),
          orderProcessing());

  private static GalleryScenario scenario(
      String id,
      String heading,
      String lookFor,
      String cannotShow,
      Shape shape,
      GalleryScenario.Body body) {
    return new GalleryScenario(
        id,
        heading,
        lookFor,
        cannotShow,
        SCENARIOS_SOURCE,
        "docs/gallery/reports/" + id + ".html",
        "reports/" + id + ".html",
        false,
        false,
        shape,
        body);
  }

  private static GalleryScenario failFast() {
    return scenario(
        "fail-fast",
        "fail-fast: one failure cancels the rest",
        """
        <code>TracedScope.open(name)</code> uses the JDK's default joiner, which cancels the scope \
        at the first failure. Look for the red <b>inventory-check</b> row and the two orange \
        <b>cancelled</b> rows: <b>recommendations</b> and <b>reviews</b> were still running, and \
        end at the same instant as the failure (the report marks them <code>cancelled ← #n</code>, \
        pointing at the task that caused it). <b>profile</b> finished before the failure and \
        stays green.""",
        """
        The report does not name the join policy: nothing in the recording says which joiner the \
        scope was opened with, so this report looks the same as the failure case of \
        <code>allSuccessfulOrThrow()</code> below.""",
        Shape.of(
                scope(
                    "fail-fast",
                    succeeded("profile"),
                    failed(
                        "inventory-check", "IllegalStateException: inventory service unavailable"),
                    cancelled("recommendations"),
                    cancelled("reviews")))
            .with(
                cancelledWithin("fail-fast", "inventory-check", 50, "recommendations", "reviews")),
        GalleryScenarios::failFast);
  }

  private static GalleryScenario allSuccessful() {
    return scenario(
        "all-successful",
        "all-successful: wait for everything, keep the results",
        """
        <code>Joiner.allSuccessfulOrThrow()</code> waits for every task and returns their results. \
        Nothing fails, so nothing is cancelled. Look for the gold-outlined <b>critical path</b> \
        row: the task that finished last, <b>shipping</b>, is what the scope was waiting for, and \
        the scope's bar ends with it.""",
        """
        The report shows that the scope waited for the slowest task. It does not show that the \
        results were collected: a recording holds events, not values.""",
        Shape.of(
                scope(
                    "all-successful",
                    succeeded("product"),
                    succeeded("pricing"),
                    succeeded("shipping")))
            .with(finishesLast("all-successful", "shipping", 100)),
        GalleryScenarios::allSuccessful);
  }

  private static GalleryScenario anySuccessful() {
    return scenario(
        "any-successful",
        "any-successful: the first success wins",
        """
        <code>Joiner.anySuccessfulOrThrow()</code> returns the first result and cancels the rest. \
        Look at the order of events: <b>mirror-1</b> fails first and cancels nothing (a failure is \
        not the answer this joiner is waiting for), then <b>mirror-2</b> succeeds, and only then \
        are <b>mirror-3</b> and <b>mirror-4</b> cancelled, ending with the success and not with \
        the failure.""",
        """
        Compare with fail-fast above: there a failure cancels the siblings, here a success does. \
        The report shows who was cancelled and when, not the policy that decided it.""",
        Shape.of(
                scope(
                    "any-successful",
                    failed("mirror-1", "ConnectException: mirror-1 refused"),
                    succeeded("mirror-2"),
                    cancelled("mirror-3"),
                    cancelled("mirror-4")))
            .with(
                endsBefore("any-successful", "mirror-1", "mirror-2", 100),
                cancelledWithin("any-successful", "mirror-2", 50, "mirror-3", "mirror-4")),
        GalleryScenarios::anySuccessful);
  }

  private static GalleryScenario awaitAll() {
    return scenario(
        "await-all",
        "await-all: a failure cancels nothing",
        """
        <code>Joiner.allUntil(subtask -> false)</code> never asks to cancel, so it is the one \
        policy here where a failure leaves the siblings alone. Look for the red \
        <b>metadata</b> row with <b>no orange row</b> after it: <b>transcript</b> kept running \
        and succeeded, and the scope lasted until the slowest task. Nothing is cancelled because \
        the predicate is false for every subtask, so the joiner never cancels the scope; the \
        failure is just an outcome, reported by the subtask's state.""",
        """
        The report shows the scope outliving the failure. It does not show the predicate; the \
        same picture could come from any joiner that never cancels.""",
        Shape.of(
                scope(
                    "await-all",
                    succeeded("thumbnail"),
                    failed("metadata", "IllegalStateException: metadata unreadable"),
                    succeeded("transcript")))
            .with(closesAfterAllTasks("await-all")),
        GalleryScenarios::awaitAll);
  }

  private static GalleryScenario customJoiner() {
    return scenario(
        "custom-joiner",
        "custom-joiner: your own rule for when to stop",
        """
        A <code>Joiner</code> can be anything; this one is a quorum that cancels the scope once 2 \
        of 3 replicas have answered. Look for the two green rows and the single orange one: \
        <b>replica-3</b> is cancelled at the instant <b>replica-2</b> completes, because \
        <code>onComplete</code> returned <code>true</code>. JDK 27 requires the joiner to \
        implement <code>timeout()</code> as well.""",
        """
        The report shows the cancellation and the task that triggered it, not the class that \
        decided it.""",
        Shape.of(
                scope(
                    "custom-joiner",
                    succeeded("replica-1"),
                    succeeded("replica-2"),
                    cancelled("replica-3")))
            .with(cancelledWithin("custom-joiner", "replica-2", 50, "replica-3")),
        GalleryScenarios::customJoiner);
  }

  private static GalleryScenario timeout() {
    return scenario(
        "timeout",
        "timeout: the scope runs out of time",
        """
        <code>withTimeout(450 ms)</code> cancels the scope when the time is up, whatever the tasks \
        are doing. Look for one green row (<b>fast</b>, done before the deadline), two orange \
        rows, and the scope closing a few milliseconds after 450 ms. In the code, <code>join()</code> \
        throws an <code>ExecutionException</code> whose cause is \
        <code>StructuredTaskScope.CancelledByTimeoutException</code>.""",
        """
        The report does not mark the timeout itself (issue #66): the orange rows look like any \
        other cancellation, and it is the scope's length that gives the timeout away.""",
        Shape.of(scope("timeout", succeeded("fast"), cancelled("slow-1"), cancelled("slow-2")))
            .with(closesBetween("timeout", TIMEOUT_MILLIS - 20, TIMEOUT_MILLIS + 100)),
        GalleryScenarios::timeout);
  }

  private static GalleryScenario nestedScopes() {
    return scenario(
        "nested",
        "nested: a scope inside a task inside a scope",
        """
        A task can open a scope of its own, and the report shows who opened what. Three scopes: \
        <b>request</b>, <b>checkout</b> (opened by the task <b>handle-checkout</b>) and \
        <b>payment</b> (opened by <b>take-payment</b>). Each child is indented under the parent \
        scope, its heading says <code>↳ task n of &lt;parent&gt;</code>, and the row of the task \
        that opened it carries a <code>↳ &lt;child&gt;</code> link.""",
        """
        Nothing here fails, so this report says nothing about how a failure travels up through \
        the levels; the order-processing section below does.""",
        Shape.of(
            scope("request", succeeded("handle-checkout"), succeeded("load-session")),
            nested(
                "checkout",
                "request",
                "handle-checkout",
                succeeded("take-payment"),
                succeeded("reserve-stock")),
            nested(
                "payment",
                "checkout",
                "take-payment",
                succeeded("fraud-check"),
                succeeded("card-auth"))),
        GalleryScenarios::nested);
  }

  private static GalleryScenario ignoresInterrupt() {
    return scenario(
        "ignores-interrupt",
        "ignores-interrupt: a task that does not stop when asked",
        """
        Cancelling a scope interrupts its tasks; it cannot make them stop. <b>reserve-stock</b> \
        fails at once, <b>price-lookup</b> is interrupted and ends with it, but \
        <b>stubborn</b> swallows the interrupt and works on. Look for the green <b>stubborn</b> \
        row ending long after the red one, and for the scope's bar ending with it: \
        <code>close()</code> waits for every task.""",
        """
        The report cannot tell a task that ignored the interrupt from one that was never \
        interrupted. The only sign is the scope outliving its failure.""",
        Shape.of(
                scope(
                    "ignores-interrupt",
                    failed("reserve-stock", "IllegalStateException: out of stock"),
                    cancelled("price-lookup"),
                    succeeded("stubborn")))
            .with(ignoresInterruptTiming("ignores-interrupt")),
        GalleryScenarios::ignoresInterrupt);
  }

  private static GalleryScenario orderProcessing() {
    return new GalleryScenario(
        "order-processing",
        "order-processing: a pipeline, one order declined",
        """
        The order pipeline from <code>OrderProcessingDemo</code>: five orders, each a fulfilment \
        scope (validation, a nested <b>payment-pipeline</b> scope, a nested \
        <b>inventory-reservation</b> scope) and, if it succeeded, a dispatch scope. Repeated \
        scopes are grouped. One order, ORD-002, is declined by its fraud check: open its section \
        (the page below scrolls to it) to see the failure travel up from <b>fraud-check</b> \
        through <b>payment-pipeline-002</b> to <b>order-processing-ORD-002</b>, cancel the \
        inventory branch, and skip the dispatch.""",
        """
        This is the report that the landing page shows. It names no join policy either, and a \
        recording of a few scopes is easy to read; a real service gives you hundreds, which is \
        what the filters at the top of the report are for.""",
        ORDERS_SOURCE,
        "docs/example/order-processing-report.html",
        "../example/order-processing-report.html",
        true,
        true,
        orderShape(),
        OrderProcessingDemo::runOrders);
  }

  // ── Shapes ───────────────────────────────────────────────────────────────────

  /** The order pipeline: ORD-002 is declined by its fraud check (see {@code draws}). */
  static Shape orderShape() {
    var scopes = new ArrayList<ExpectedScope>();
    for (var n : List.of("001", "002", "003", "004", "005")) {
      var order = "ORD-" + n;
      var declined = n.equals("002");
      var fulfilment = "order-processing-" + order;
      scopes.add(
          scope(
              fulfilment,
              succeeded("validate-order"),
              declined ? failed("payment", "declined") : succeeded("payment"),
              declined ? cancelled("inventory") : succeeded("inventory")));
      scopes.add(
          nested(
              "payment-pipeline-" + n,
              fulfilment,
              "payment",
              declined ? failed("fraud-check", "declined") : succeeded("fraud-check"),
              succeeded("card-authorisation")));
      scopes.add(
          nested(
              "inventory-reservation-" + n,
              fulfilment,
              "inventory",
              succeeded("warehouse-A"),
              declined ? cancelled("warehouse-B") : succeeded("warehouse-B")));
      if (!declined) {
        scopes.add(
            scope(
                "order-dispatch-" + order,
                succeeded("assign-courier"),
                succeeded("generate-label"),
                succeeded("notify-customer")));
      }
    }
    return new Shape(scopes, List.of());
  }

  // ── Timing checks ────────────────────────────────────────────────────────────

  private static TaskRecord task(TraceModel model, String scope, String task) {
    return Shape.byName(model).get(scope).tasks().stream()
        .filter(t -> task.equals(t.taskName()))
        .findFirst()
        .orElseThrow();
  }

  private static long millis(Instant from, Instant to) {
    return Duration.between(from, to).toMillis();
  }

  /** Each named task ends within {@code limit} ms of {@code reference} (before or after). */
  static TimingCheck cancelledWithin(String scope, String reference, long limit, String... names) {
    return model -> {
      var problems = new ArrayList<String>();
      var anchor = task(model, scope, reference).completionTime();
      for (var name : names) {
        var gap = Math.abs(millis(anchor, task(model, scope, name).completionTime()));
        if (gap > limit) {
          problems.add(
              name + " ended " + gap + " ms from " + reference + ", expected at most " + limit);
        }
      }
      return problems;
    };
  }

  /** {@code first} ends at least {@code gap} ms before {@code second}. */
  static TimingCheck endsBefore(String scope, String first, String second, long gap) {
    return model -> {
      var actual =
          millis(
              task(model, scope, first).completionTime(),
              task(model, scope, second).completionTime());
      return actual >= gap
          ? List.of()
          : List.of(first + " ended " + actual + " ms before " + second + ", expected " + gap);
    };
  }

  /** {@code last} is the task that ends last, at least {@code gap} ms after the next one. */
  static TimingCheck finishesLast(String scope, String last, long gap) {
    return model -> {
      var tasks =
          Shape.byName(model).get(scope).tasks().stream()
              .sorted(
                  java.util.Comparator.comparing(
                      TaskRecord::completionTime, java.util.Comparator.reverseOrder()))
              .toList();
      if (!last.equals(tasks.get(0).taskName())) {
        return List.of(tasks.get(0).taskName() + " ended last, expected " + last);
      }
      var actual = millis(tasks.get(1).completionTime(), tasks.get(0).completionTime());
      return actual >= gap
          ? List.of()
          : List.of(last + " ended " + actual + " ms after the next task, expected " + gap);
    };
  }

  /** The scope closes after its last task completed. */
  static TimingCheck closesAfterAllTasks(String scope) {
    return model -> {
      var s = Shape.byName(model).get(scope);
      var lastEnd = s.tasks().stream().map(TaskRecord::completionTime).max(Instant::compareTo);
      return lastEnd.isPresent() && s.closeTime().isBefore(lastEnd.get())
          ? List.of("scope closed before its last task ended")
          : List.of();
    };
  }

  /** The scope's length, from open to close, is between {@code min} and {@code max} ms. */
  static TimingCheck closesBetween(String scope, long min, long max) {
    return model -> {
      var s = Shape.byName(model).get(scope);
      var actual = millis(s.openTime(), s.closeTime());
      return actual >= min && actual <= max
          ? List.of()
          : List.of("scope lasted " + actual + " ms, expected " + min + " to " + max);
    };
  }

  private static TimingCheck ignoresInterruptTiming(String scope) {
    return model -> {
      var problems = new ArrayList<String>();
      var s = Shape.byName(model).get(scope);
      var failedAt = task(model, scope, "reserve-stock").completionTime();
      var pickedUpAt = task(model, scope, "price-lookup").completionTime();
      var stubbornAt = task(model, scope, "stubborn").completionTime();
      if (millis(s.openTime(), failedAt) > 100 || millis(s.openTime(), pickedUpAt) > 100) {
        problems.add(
            "the failure and the cancellation came more than 100 ms after the scope opened");
      }
      if (millis(failedAt, stubbornAt) < 500) {
        problems.add("stubborn ended less than 500 ms after the failure");
      }
      if (s.closeTime().isBefore(stubbornAt)) {
        problems.add("the scope closed before stubborn ended");
      }
      return problems;
    };
  }
}
