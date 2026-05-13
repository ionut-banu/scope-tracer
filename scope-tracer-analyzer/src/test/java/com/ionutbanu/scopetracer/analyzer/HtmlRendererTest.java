package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlRendererTest {

  private static final Instant T0 = Instant.parse("2024-01-01T10:00:00.000Z");
  private static final Instant T1 = T0.plusMillis(10);
  private static final Instant T2 = T0.plusMillis(20);
  private static final Instant T3 = T0.plusMillis(30);
  private static final Instant T4 = T0.plusMillis(40);

  // --- empty model ---

  @Test
  void emptyModelRendersWithoutThrowing() {
    assertThatNoException().isThrownBy(() -> HtmlRenderer.render(new TraceModel(List.of())));
  }

  @Test
  void emptyModelContainsNoScopesMessage() {
    var html = HtmlRenderer.render(new TraceModel(List.of()));
    assertThat(html).contains("No scopes found");
  }

  // --- scope name appears in output ---

  @Test
  void scopeNameAppearsInOutput() {
    var model = modelWithSingleSuccessTask("my-checkout-flow");
    var html = HtmlRenderer.render(model);
    assertThat(html).contains("my-checkout-flow");
  }

  // --- task count ---

  @Test
  void eachTaskProducesATableRow() {
    var tasks =
        List.of(
            task(1, T1, T2, new TaskOutcome.Success()),
            task(2, T2, T3, new TaskOutcome.Success()),
            task(3, T3, T4, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "multi", "main", -1L, T0, T4, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    // one <tr> per task plus the header row
    var rowCount = countOccurrences(html, "<tr>");
    assertThat(rowCount).isGreaterThanOrEqualTo(3);
  }

  // --- outcome colors ---

  @Test
  void successTaskRendersGreenColor() {
    var html = HtmlRenderer.render(modelWithSingleTask(new TaskOutcome.Success()));
    assertThat(html).contains("#4caf50");
  }

  @Test
  void failedTaskRendersRedColor() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(new TaskOutcome.Failed("java.lang.RuntimeException", null, null)));
    assertThat(html).contains("#e53935");
  }

  @Test
  void cancelledTaskRendersAmberColor() {
    var html = HtmlRenderer.render(modelWithSingleTask(new TaskOutcome.Cancelled()));
    assertThat(html).contains("#fb8c00");
  }

  // --- exception type and message in failed output ---

  @Test
  void failedTaskExceptionTypeAppearsInOutput() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed("java.lang.IllegalStateException", null, null)));
    assertThat(html).contains("java.lang.IllegalStateException");
  }

  @Test
  void failedTaskExceptionMessageAppearsInOutput() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed(
                    "java.lang.IllegalStateException", "userId must not be null", null)));
    assertThat(html).contains("java.lang.IllegalStateException");
    assertThat(html).contains("userId must not be null");
  }

  @Test
  void failedTaskNullMessageShowsOnlyType() {
    var withMessage =
        HtmlRenderer.render(
            modelWithSingleTask(new TaskOutcome.Failed("java.lang.RuntimeException", null, null)));
    var withoutMessage =
        HtmlRenderer.render(
            modelWithSingleTask(new TaskOutcome.Failed("java.lang.RuntimeException", "", null)));
    // Neither null nor blank message should add a colon separator after the type
    assertThat(withMessage).doesNotContain("RuntimeException:");
    assertThat(withoutMessage).doesNotContain("RuntimeException:");
  }

  // --- HTML structure ---

  @Test
  void outputIsValidHtmlShell() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    assertThat(html).startsWith("<!DOCTYPE html>");
    assertThat(html).contains("<html");
    assertThat(html).contains("</html>");
  }

  @Test
  void outputContainsSvgTimeline() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    assertThat(html).contains("<svg");
    assertThat(html).contains("</svg>");
  }

  // --- locale safety ---

  @Test
  void svgCoordinatesUsePeriodsNotCommasAsSeparator() {
    // SVG attribute values must use '.' as decimal separator regardless of JVM locale.
    // A comma causes browsers to silently ignore the attribute and render nothing.
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    var svgBlock = html.substring(html.indexOf("<svg"), html.indexOf("</svg>") + 6);
    assertThat(svgBlock).doesNotContainPattern("x=\"[^\"]*,[^\"]*\"");
    assertThat(svgBlock).doesNotContainPattern("width=\"[^\"]*,[^\"]*\"");
  }

  // --- blank thread name fallback ---

  @Test
  void blankThreadNameRendersAsVirtual() {
    var tasks = List.of(new TaskRecord(1, null, "", -1L, T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("&lt;virtual&gt;");
  }

  @Test
  void nullThreadNameRendersAsVirtual() {
    var tasks = List.of(new TaskRecord(1, null, null, -1L, T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("&lt;virtual&gt;");
  }

  // --- nesting ---

  @Test
  void childScopeRendersWithBreadcrumb() {
    var parentScope =
        new ScopeRecord(
            1L,
            "order-processing",
            "main",
            -1L,
            T0,
            T4,
            List.of(task(1, T1, T3, new TaskOutcome.Success())),
            null);
    var childScope =
        new ScopeRecord(
            2L,
            "payment-steps",
            "worker-1",
            -1L,
            T1,
            T3,
            List.of(task(2, T1, T2, new TaskOutcome.Success())),
            new ScopeRecord.ParentRef(1L, "order-processing", 1));
    var html = HtmlRenderer.render(new TraceModel(List.of(parentScope, childScope)));
    assertThat(html).contains("payment-steps");
    assertThat(html).contains("↳ task 1 of order-processing");
    assertThat(html).contains("child-scope");
  }

  /**
   * Regression test for the same-named scope collision bug. Two root scopes share the same name
   * ("payment") but have different scopeIds. Each has its own distinct child. The renderer must not
   * fuse their children — each parent should show only its own child, not both.
   */
  @Test
  void sameNamedScopesGetDistinctChildren() {
    // Parent A (scopeId=1) has child C (scopeId=3, name "child-a")
    // Parent B (scopeId=2) has child D (scopeId=4, name "child-b")
    // Both parents share the name "payment".
    var parentA =
        new ScopeRecord(
            1L,
            "payment",
            "main",
            -1L,
            T0,
            T4,
            List.of(task(1, T1, T3, new TaskOutcome.Success())),
            null);
    var parentB =
        new ScopeRecord(
            2L,
            "payment",
            "main",
            -1L,
            T0,
            T4,
            List.of(task(1, T1, T3, new TaskOutcome.Success())),
            null);
    var childOfA =
        new ScopeRecord(
            3L,
            "child-a",
            "worker-1",
            -1L,
            T1,
            T3,
            List.of(task(1, T1, T2, new TaskOutcome.Success())),
            new ScopeRecord.ParentRef(1L, "payment", 1));
    var childOfB =
        new ScopeRecord(
            4L,
            "child-b",
            "worker-2",
            -1L,
            T1,
            T3,
            List.of(task(1, T1, T2, new TaskOutcome.Success())),
            new ScopeRecord.ParentRef(2L, "payment", 1));

    var html = HtmlRenderer.render(new TraceModel(List.of(parentA, parentB, childOfA, childOfB)));

    // Both child sections must appear somewhere in the output.
    assertThat(html).contains("child-a");
    assertThat(html).contains("child-b");

    // The "↳ child-X" annotation in a task table row must appear exactly once per child:
    // parentA's task row gets "↳ child-a", parentB's task row gets "↳ child-b".
    // Before the fix (name-keyed maps) the wrong child would bleed into both parents, making
    // each annotation appear twice.
    assertThat(countOccurrences(html, "↳ child-a")).isEqualTo(1);
    assertThat(countOccurrences(html, "↳ child-b")).isEqualTo(1);
  }

  // --- critical path highlighting ---

  @Test
  void criticalPathTaskRendersGoldOutline() {
    // Task 2 completes last (T1→T4 = 30 ms) and is the critical path
    var tasks =
        List.of(
            task(1, T1, T2, new TaskOutcome.Success()), // 10 ms
            task(2, T1, T4, new TaskOutcome.Success()), // 30 ms — latest
            task(3, T1, T3, new TaskOutcome.Success())); // 20 ms
    var scope = new ScopeRecord(1L, "cp-scope", "main", -1L, T0, T4, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    // The critical task bar has a gold stroke outline
    assertThat(html).contains("stroke=\"#d97706\"");
  }

  @Test
  void onlyCriticalTaskHasGoldOutline() {
    var tasks =
        List.of(
            task(1, T1, T2, new TaskOutcome.Success()), // 10 ms
            task(2, T1, T4, new TaskOutcome.Success()), // 30 ms — latest
            task(3, T1, T3, new TaskOutcome.Success())); // 20 ms
    var scope = new ScopeRecord(1L, "cp-scope", "main", -1L, T0, T4, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    // Gold stroke appears exactly once (task 2); all three bars use the green fill
    assertThat(countOccurrences(html, "stroke=\"#d97706\"")).isEqualTo(1);
    assertThat(countOccurrences(html, "fill=\"#4caf50\"")).isEqualTo(3);
  }

  @Test
  void criticalPathAnnotationAppearsInTable() {
    var tasks =
        List.of(
            task(1, T1, T2, new TaskOutcome.Success()),
            task(2, T1, T4, new TaskOutcome.Success()),
            task(3, T1, T3, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "cp-scope", "main", -1L, T0, T4, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("critical path");
  }

  @Test
  void failedScopeHasNoCriticalPathHighlight() {
    // One success, one failed — critical path undefined for non-all-success scopes
    var tasks =
        List.of(
            task(1, T1, T2, new TaskOutcome.Success()),
            task(2, T1, T3, new TaskOutcome.Failed("java.lang.RuntimeException", null, null)));
    var scope = new ScopeRecord(1L, "fail-scope", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    // No gold stroke on any task bar (the CSS class definition is always present, but not the attr)
    assertThat(html).doesNotContain("stroke=\"#d97706\"");
    // No critical path annotation text in the table
    assertThat(html).doesNotContain("← critical path");
  }

  // --- chronological rendering order ---

  /**
   * When scopes of different names interleave in time, the rendered DOM must follow strict
   * open-time order. The old group-all-same-name approach broke this by rendering every scope of
   * group A before any scope of group B, even when B's scopes interleave with A's in time.
   *
   * <p>Setup: {@code checkout} and {@code payment} alternate — checkout(T0), payment(T1),
   * checkout(T2), payment(T3). Under the old approach these would form two groups of 2 → {@code
   * <details>} wrappers appear and checkout(T2) is rendered before payment(T1). Under the
   * consecutive-run approach each scope is its own run of 1 → zero {@code <details>} wrappers, and
   * the DOM order is strictly T0, T1, T2, T3.
   */
  @Test
  void interleavedScopeNamesRenderInChronologicalOrder() {
    List<ScopeRecord> scopes =
        List.of(
            scopeWithSuccess(1L, "checkout", T0, T1),
            new ScopeRecord(
                2L,
                "payment",
                "main",
                -1L,
                T1,
                T2,
                List.of(task(1, T1, T2, new TaskOutcome.Failed("E", "fail", null))),
                null),
            scopeWithSuccess(3L, "checkout", T2, T3),
            new ScopeRecord(
                4L,
                "payment",
                "main",
                -1L,
                T3,
                T4,
                List.of(task(1, T3, T4, new TaskOutcome.Failed("E", "fail", null))),
                null));
    var html = HtmlRenderer.render(new TraceModel(scopes));

    // Four interleaved scopes → four runs of 1 each → no <details> grouping at all.
    assertThat(countOccurrences(html, "<details")).isEqualTo(0);

    // Strict DOM order: checkout(T0) before payment(T1) before checkout(T2) before payment(T3).
    // We verify relative positions via indexOf on the unique scope-N anchor IDs.
    assertThat(html.indexOf("scope-0")).isLessThan(html.indexOf("scope-1"));
    assertThat(html.indexOf("scope-1")).isLessThan(html.indexOf("scope-2"));
    assertThat(html.indexOf("scope-2")).isLessThan(html.indexOf("scope-3"));
  }

  // --- groupScopes correlated-suffix path ---

  /**
   * When a numeric suffix (e.g. {@code "001"}) appears across ≥2 distinct base names (e.g. {@code
   * "payment"} and {@code "dispatch"}), all scopes sharing that suffix are placed in one group
   * keyed by the suffix. The rendered HTML must contain a single {@code <details>} block for the
   * suffix rather than separate per-name groups.
   *
   * <p>Setup: four scopes — {@code payment-001}, {@code dispatch-001}, {@code payment-002}, {@code
   * dispatch-002}. The suffix {@code "001"} and {@code "002"} each appear across two base names, so
   * both are correlated. The output should contain exactly two group headers (one per suffix) and
   * all four scope names inside them.
   */
  @Test
  void correlatedSuffixScopesAreGroupedBySuffix() {
    var t5 = T0.plusMillis(50);
    List<ScopeRecord> scopes =
        List.of(
            scopeWithSuccess(1L, "payment-001", T0, T2),
            scopeWithSuccess(2L, "dispatch-001", T0, T2),
            scopeWithSuccess(3L, "payment-002", T2, T4),
            scopeWithSuccess(4L, "dispatch-002", T2, t5));
    var html = HtmlRenderer.render(new TraceModel(scopes));

    // All four scope names must appear in the output.
    assertThat(html).contains("payment-001");
    assertThat(html).contains("dispatch-001");
    assertThat(html).contains("payment-002");
    assertThat(html).contains("dispatch-002");

    // The correlated suffixes become group keys — two <details> blocks, one per suffix.
    // Each suffix group contains two scopes, so the summary stat shows "2/" counts.
    assertThat(countOccurrences(html, "<details")).isEqualTo(2);
  }

  // --- scopeOutcome "incomplete" data-outcome attribute ---

  /**
   * A {@link ScopeRecord} whose tasks have {@code null} outcome (e.g. truncated recording) must
   * produce {@code data-outcome="incomplete"} on its {@code <section>} element. This drives the JS
   * filter and must not fall through to {@code "success"} or {@code "failed"}.
   */
  @Test
  void scopeWithNullOutcomeTaskHasIncompleteDataOutcomeAttribute() {
    // TaskRecord with null outcome simulates a truncated recording (no completion event).
    var tasks = List.of(new TaskRecord(1, null, "worker-1", -1L, T1, null, null));
    var scope = new ScopeRecord(1L, "incomplete-scope", "main", -1L, T0, null, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).contains("data-outcome=\"incomplete\"");
    assertThat(html).doesNotContain("data-outcome=\"success\"");
    assertThat(html).doesNotContain("data-outcome=\"failed\"");
  }

  // --- stack trace rendering ---

  @Test
  void stackTraceIsRenderedWhenNonNull() {
    var trace = "java.lang.IllegalStateException: boom\n\tat com.example.Foo.bar(Foo.java:42)\n";
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed("java.lang.IllegalStateException", "boom", trace)));
    // The <pre> element with class "stack-trace-body" must be present in the body, not just in CSS.
    assertThat(html).contains("<pre class=\"stack-trace-body\">");
    assertThat(html).contains("Foo.java:42");
  }

  @Test
  void stackTraceIsNotRenderedWhenNull() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed("java.lang.RuntimeException", "oops", null)));
    assertThat(html).doesNotContain("<pre class=\"stack-trace-body\">");
  }

  @Test
  void stackTraceIsNotRenderedWhenBlank() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed("java.lang.RuntimeException", "oops", "   ")));
    assertThat(html).doesNotContain("<pre class=\"stack-trace-body\">");
  }

  @Test
  void stackTraceContentIsHtmlEscaped() {
    var trace = "<script>alert(1)</script>";
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(
                new TaskOutcome.Failed("java.lang.RuntimeException", "oops", trace)));
    // The injected payload must not appear verbatim — it must be escaped.
    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }

  // --- XSS safety ---

  @Test
  void scopeNameWithSpecialCharsIsEscaped() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("<script>alert(1)</script>"));
    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).contains("&lt;script&gt;");
  }

  // --- helpers ---

  private static TraceModel modelWithSingleSuccessTask(String scopeName) {
    return modelWithSingleTask(scopeName, new TaskOutcome.Success());
  }

  private static TraceModel modelWithSingleTask(TaskOutcome outcome) {
    return modelWithSingleTask("test-scope", outcome);
  }

  private static TraceModel modelWithSingleTask(String scopeName, TaskOutcome outcome) {
    var tasks = List.of(task(1, T1, T2, outcome));
    var scope = new ScopeRecord(1L, scopeName, "main", -1L, T0, T3, tasks, null);
    return new TraceModel(List.of(scope));
  }

  private static TaskRecord task(long id, Instant fork, Instant completion, TaskOutcome outcome) {
    return new TaskRecord(id, null, "worker-" + id, -1L, fork, completion, outcome);
  }

  // --- task name rendering ---

  /** A non-null taskName appears in the table cell, and the SVG bar label includes it. */
  @Test
  void namedTaskAppearsInTableAndBarLabel() {
    var tasks =
        List.of(new TaskRecord(1, "findUser", "worker-1", -1L, T1, T2, new TaskOutcome.Success()));
    // Long enough scope window so the bar gets ≥ 80px and the inline SVG label includes the name.
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).contains("findUser");
    // Both the table cell ("findUser") and the SVG label ("#1 findUser") should be present.
    assertThat(countOccurrences(html, "findUser")).isGreaterThanOrEqualTo(2);
  }

  /** A null taskName falls back to a placeholder em-dash in the table; the bar shows just "#N". */
  @Test
  void nullTaskNameFallsBackToPlaceholder() {
    var tasks =
        List.of(new TaskRecord(1, null, "worker-1", -1L, T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    // The bar label must still render the index.
    assertThat(html).contains(">#1<");
  }

  /** Task name with HTML metacharacters is escaped, never injected raw into the document. */
  @Test
  void taskNameIsHtmlEscaped() {
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "<script>alert(1)</script>",
                "worker-1",
                -1L,
                T1,
                T2,
                new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).doesNotContain("<script>alert(1)</script>");
    assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
  }

  // --- bar-label width-aware fitting ---
  //
  // Bar width = (task duration / scope duration) * 800px (TIMELINE_W). The bar-label fit
  // logic uses a 7px/char monospace estimate with 8px of padding, so available chars =
  // (barWidth - 8) / 7. The four tests below pin each of the four tiers.

  /**
   * Tier 2: when the full {@code Class#method:line} label doesn't fit but the class-dropped form
   * ({@code method:line}) does, the bar text drops the class prefix.
   */
  @Test
  void barLabelDropsClassPrefixWhenFullNameDoesNotFit() {
    // scope = 100ms, task = 25ms → bar = 200px → ~27 chars budget.
    // Full label "#1 LiveOrderProcessingDemo#runFulfillment:160" = 45 chars → no fit.
    // Shortened "#1 runFulfillment:160" = 21 chars → fits.
    var open = T0;
    var close = T0.plusMillis(100);
    var fork = T0.plusMillis(10);
    var complete = fork.plusMillis(25);
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "LiveOrderProcessingDemo#runFulfillment:160",
                "vt-95",
                -1L,
                fork,
                complete,
                new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, open, close, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    // Bar text shows the class-dropped form.
    assertThat(html).contains(">#1 runFulfillment:160</text>");
    // The full class-prefixed name is NOT in the bar text — but still appears in the
    // table cell and the tooltip <title>.
    assertThat(html).doesNotContain(">#1 LiveOrderProcessingDemo#runFulfillment:160</text>");
    assertThat(html).contains("LiveOrderProcessingDemo#runFulfillment:160");
  }

  /**
   * Tier 3: when even the class-dropped form is too wide, truncate with an ellipsis. The truncation
   * point is deterministic given the 7px/char budget.
   */
  @Test
  void barLabelTruncatesWithEllipsisWhenEvenShortenedFormDoesNotFit() {
    // scope = 200ms, task = 25ms → bar = 100px → 13 chars budget.
    // Shortened "#1 runFulfillment:160" (21 chars) does not fit.
    // Truncated: substring(0,12) + "…" = "#1 runFulfil…" (13 chars).
    var open = T0;
    var close = T0.plusMillis(200);
    var fork = T0.plusMillis(10);
    var complete = fork.plusMillis(25);
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "LiveOrderProcessingDemo#runFulfillment:160",
                "vt-95",
                -1L,
                fork,
                complete,
                new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, open, close, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).contains(">#1 runFulfil…</text>");
    // The mid-word hard-clip artefact (a bare letter-tail with no ellipsis) must not appear.
    assertThat(html).doesNotContain(">#1 runFulfil</text>");
  }

  /**
   * Tier 4: when the bar is so narrow that not even {@code "#N x…"} fits, the bar shows the index
   * alone — never a fragment of the name.
   */
  @Test
  void barLabelFallsBackToIndexWhenNoNameCharsFit() {
    // scope = 320ms, task = 25ms → bar = 62.5px → 7 chars budget.
    // Tier 3 minimum is "#N " + 4 chars + "…" = 8 chars → 7 < 8, so falls through to tier 4.
    var open = T0;
    var close = T0.plusMillis(320);
    var fork = T0.plusMillis(10);
    var complete = fork.plusMillis(25);
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "LiveOrderProcessingDemo#runFulfillment:160",
                "vt-95",
                -1L,
                fork,
                complete,
                new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, open, close, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).contains(">#1</text>");
    // No name fragment leaks into the bar text.
    assertThat(html).doesNotContain(">#1 run");
    assertThat(html).doesNotContain(">#1 L");
  }

  /**
   * Tier 1: a wide bar shows the complete {@code Class#method:line} label inline — no truncation,
   * no class-prefix drop.
   */
  @Test
  void barLabelKeepsFullNameWhenBarIsWide() {
    // scope = 20ms, task = 18ms → bar = 720px → 101 chars budget.
    // Full "#1 LiveOrderProcessingDemo#runFulfillment:160" (45 chars) fits comfortably.
    var open = T0;
    var close = T0.plusMillis(20);
    var fork = T0.plusMillis(1);
    var complete = T0.plusMillis(19);
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "LiveOrderProcessingDemo#runFulfillment:160",
                "vt-95",
                -1L,
                fork,
                complete,
                new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, open, close, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));

    assertThat(html).contains(">#1 LiveOrderProcessingDemo#runFulfillment:160</text>");
  }

  private static ScopeRecord scopeWithSuccess(
      long scopeId, String name, Instant open, Instant close) {
    var tasks =
        List.of(task(1, open.plusMillis(1), close.minusMillis(1), new TaskOutcome.Success()));
    return new ScopeRecord(scopeId, name, "main", -1L, open, close, tasks, null);
  }

  private static int countOccurrences(String text, String sub) {
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(sub, idx)) != -1) {
      count++;
      idx += sub.length();
    }
    return count;
  }

  // --- JSON export embed ---

  @Test
  void traceDataScriptTagIsEmbedded() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("checkout-flow"));
    assertThat(html).contains("<script type=\"application/json\" id=\"trace-data\">");
  }

  @Test
  void embeddedJsonContainsScopeNames() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("my-unique-scope"));
    // The escaped name appears both inside the JSON and inside the rendered DOM, so just
    // verifying the substring appears in the JSON script body is enough.
    int scriptStart = html.indexOf("id=\"trace-data\">");
    int scriptEnd = html.indexOf("</script>", scriptStart);
    var jsonBody = html.substring(scriptStart, scriptEnd);
    assertThat(jsonBody).contains("\"name\":\"my-unique-scope\"");
  }

  @Test
  void exportJsonButtonIsRendered() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    assertThat(html).contains("id=\"export-json\"");
  }

  // --- errors-only filter button ---

  @Test
  void errorsOnlyButtonIsRendered() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    assertThat(html).contains("id=\"errors-only\"");
  }

  // --- deep-link anchor ---

  @Test
  void rootScopeRendersDeepLinkAnchor() {
    var html = HtmlRenderer.render(modelWithSingleSuccessTask("scope"));
    // Each root scope gets an <a class="anchor-link" href="#scope-N">.
    assertThat(html).contains("class=\"anchor-link\" href=\"#scope-0\"");
  }

  @Test
  void anchorLinkOmittedForChildScope() {
    // Build a parent scope and a child scope. The child has parent != null and should
    // NOT receive an anchor link (only root scopes do).
    var parentTasks = List.of(task(1, T1, T2, new TaskOutcome.Success()));
    var parent = new ScopeRecord(1L, "parent", "main", -1L, T0, T3, parentTasks, null);
    var childParent = new ScopeRecord.ParentRef(1L, "parent", 1L);
    var child =
        new ScopeRecord(
            2L, "child", "vt-9", 9L, T1.plusMillis(1), T2.minusMillis(1), List.of(), childParent);
    var html = HtmlRenderer.render(new TraceModel(List.of(parent, child)));
    // anchor only for the root scope (scope-0); no anchor for child.
    assertThat(html).contains("class=\"anchor-link\" href=\"#scope-0\"");
    // The child renders inside the parent's section; its own <h2> must not include an anchor.
    int childIdx = html.indexOf("<h2>child");
    assertThat(childIdx).isPositive();
    int childH2End = html.indexOf("</h2>", childIdx);
    assertThat(html.substring(childIdx, childH2End)).doesNotContain("anchor-link");
  }
}
