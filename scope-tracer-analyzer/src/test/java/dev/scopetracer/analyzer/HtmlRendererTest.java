package dev.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TaskRecord;
import dev.scopetracer.analyzer.model.TraceModel;
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
            modelWithSingleTask(new TaskOutcome.Failed("java.lang.RuntimeException")));
    assertThat(html).contains("#e53935");
  }

  @Test
  void cancelledTaskRendersAmberColor() {
    var html = HtmlRenderer.render(modelWithSingleTask(new TaskOutcome.Cancelled()));
    assertThat(html).contains("#fb8c00");
  }

  // --- exception type in failed tooltip ---

  @Test
  void failedTaskExceptionTypeAppearsInOutput() {
    var html =
        HtmlRenderer.render(
            modelWithSingleTask(new TaskOutcome.Failed("java.lang.IllegalStateException")));
    assertThat(html).contains("java.lang.IllegalStateException");
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
    var tasks = List.of(new TaskRecord(1, "", -1L, T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord(1L, "s", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("&lt;virtual&gt;");
  }

  @Test
  void nullThreadNameRendersAsVirtual() {
    var tasks = List.of(new TaskRecord(1, null, -1L, T1, T2, new TaskOutcome.Success()));
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
            task(2, T1, T3, new TaskOutcome.Failed("java.lang.RuntimeException")));
    var scope = new ScopeRecord(1L, "fail-scope", "main", -1L, T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    // No gold stroke on any task bar (the CSS class definition is always present, but not the attr)
    assertThat(html).doesNotContain("stroke=\"#d97706\"");
    // No critical path annotation text in the table
    assertThat(html).doesNotContain("← critical path");
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
    return new TaskRecord(id, "worker-" + id, -1L, fork, completion, outcome);
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
}
