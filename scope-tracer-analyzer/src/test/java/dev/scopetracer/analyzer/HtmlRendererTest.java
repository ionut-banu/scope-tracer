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
    var scope = new ScopeRecord("multi", "main", T0, T4, tasks, null);
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
    var tasks = List.of(new TaskRecord(1, "", T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord("s", "main", T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("&lt;virtual&gt;");
  }

  @Test
  void nullThreadNameRendersAsVirtual() {
    var tasks = List.of(new TaskRecord(1, null, T1, T2, new TaskOutcome.Success()));
    var scope = new ScopeRecord("s", "main", T0, T3, tasks, null);
    var html = HtmlRenderer.render(new TraceModel(List.of(scope)));
    assertThat(html).contains("&lt;virtual&gt;");
  }

  // --- nesting ---

  @Test
  void childScopeRendersWithBreadcrumb() {
    var parentScope =
        new ScopeRecord(
            "order-processing",
            "main",
            T0,
            T4,
            List.of(task(1, T1, T3, new TaskOutcome.Success())),
            null);
    var childScope =
        new ScopeRecord(
            "payment-steps",
            "worker-1",
            T1,
            T3,
            List.of(task(2, T1, T2, new TaskOutcome.Success())),
            new ScopeRecord.ParentRef("order-processing", 1));
    var html = HtmlRenderer.render(new TraceModel(List.of(parentScope, childScope)));
    assertThat(html).contains("payment-steps");
    assertThat(html).contains("↳ task 1 of order-processing");
    assertThat(html).contains("child-scope");
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
    var scope = new ScopeRecord(scopeName, "main", T0, T3, tasks, null);
    return new TraceModel(List.of(scope));
  }

  private static TaskRecord task(long id, Instant fork, Instant completion, TaskOutcome outcome) {
    return new TaskRecord(id, "worker-" + id, fork, completion, outcome);
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
