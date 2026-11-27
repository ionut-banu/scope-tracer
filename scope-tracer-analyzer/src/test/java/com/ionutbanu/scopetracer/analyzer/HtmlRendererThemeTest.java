package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The report's light and dark palettes (#191): structure, parity, no stray colours, contrast. */
class HtmlRendererThemeTest {

  private static final Instant T0 = Instant.parse("2024-01-01T10:00:00.000Z");
  private static final String SUCCESS = "#4caf50";
  private static final String FAILED = "#e53935";
  private static final String CANCELLED = "#fb8c00";
  private static final String INCOMPLETE = "#9e9e9e";
  private static final String SCOPE_BAR = "#1565c0";
  private static final String CRITICAL = "#d97706";
  private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");

  private static TaskRecord task(long id, int forkMs, Integer endMs, TaskOutcome outcome) {
    return new TaskRecord(
        id,
        null,
        "worker-" + id,
        -1L,
        T0.plusMillis(forkMs),
        endMs == null ? null : T0.plusMillis(endMs),
        outcome);
  }

  private static TraceModel mixedModel() {
    var tasks =
        List.of(
            task(1, 1, 10, new TaskOutcome.Success()),
            task(2, 1, 20, new TaskOutcome.Failed("java.lang.RuntimeException", "x", "trace")),
            task(3, 1, 25, new TaskOutcome.Cancelled()),
            task(4, 1, null, null));
    var critical =
        List.of(
            task(1, 1, 10, new TaskOutcome.Success()), task(2, 1, 30, new TaskOutcome.Success()));
    return new TraceModel(
        List.of(
            new ScopeRecord(1L, "mixed", "main", -1L, T0, T0.plusMillis(40), tasks, null),
            new ScopeRecord(2L, "critical", "main", -1L, T0, T0.plusMillis(40), critical, null)));
  }

  private static String html() {
    return HtmlRenderer.render(mixedModel());
  }

  private static String block(String css, String opener) {
    int start = css.indexOf(opener);
    assertThat(start).as(opener).isGreaterThanOrEqualTo(0);
    int open = css.indexOf('{', start);
    int close = css.indexOf('}', open);
    return css.substring(open + 1, close);
  }

  private static Map<String, String> vars(String body) {
    var m = new LinkedHashMap<String, String>();
    var p = Pattern.compile("(--[a-z-]+):\\s*([^;]+);");
    var mm = p.matcher(body);
    while (mm.find()) m.put(mm.group(1), mm.group(2).trim());
    return m;
  }

  private static String style(String html) {
    return html.substring(html.indexOf("<style>"), html.indexOf("</style>"));
  }

  private static Map<String, String> light() {
    return vars(block(style(html()), ":root {"));
  }

  private static Map<String, String> dark() {
    var css = style(html());
    return vars(block(css, ":root[data-theme=\"dark\"]"));
  }

  @Test
  void stylesheetFollowsOsAndExplicitAttribute() {
    var css = style(html());
    assertThat(css)
        .contains("@media (prefers-color-scheme: dark)")
        .contains(":root[data-theme=\"dark\"]")
        .contains(":root:not([data-theme=\"light\"])")
        .contains("color-scheme");
  }

  @Test
  void bothPalettesDefineTheSameProperties() {
    var css = style(html());
    var media = vars(block(css, ":root:not([data-theme=\"light\"])"));
    assertThat(light().keySet()).isNotEmpty();
    assertThat(dark().keySet()).isEqualTo(light().keySet());
    assertThat(media).isEqualTo(dark());
  }

  @Test
  void noLiteralColourOutsideThePalettes() {
    var h = html();
    var css = style(h);
    // drop the palette blocks, the trace JSON and the script
    var stripped = css;
    for (var opener :
        new String[] {
          ":root {", ":root:not([data-theme=\"light\"])", ":root[data-theme=\"dark\"]"
        }) {
      int start = stripped.indexOf(opener);
      int open = stripped.indexOf('{', start);
      int close = stripped.indexOf('}', open);
      stripped = stripped.substring(0, open) + stripped.substring(close);
    }
    assertThat(COLOUR.matcher(stripped).find()).as("stylesheet outside palettes").isFalse();
    var body = h.substring(h.indexOf("</style>"));
    body = body.replaceAll("(?s)<script.*?</script>", "");
    var allowed = List.of(SUCCESS, FAILED, CANCELLED, INCOMPLETE, SCOPE_BAR, CRITICAL);
    var m = COLOUR.matcher(body);
    while (m.find()) assertThat(allowed).as("literal colour " + m.group()).contains(m.group());
    assertThat(body).doesNotContain("fill=\"white\"").doesNotContain("stroke=\"#bdbdbd\"");
  }

  @Test
  void fillsAreTheSameInBothThemes() {
    var h = html();
    for (var c : List.of(SUCCESS, FAILED, CANCELLED, INCOMPLETE, SCOPE_BAR)) {
      assertThat(h).contains(c);
    }
    assertThat(h).contains("stroke=\"" + CRITICAL + "\"");
    // no palette variable redefines an outcome fill
    var all = new java.util.ArrayList<>(light().values());
    all.addAll(dark().values());
    assertThat(all).doesNotContain(SUCCESS, FAILED, CANCELLED, INCOMPLETE, CRITICAL);
  }

  @Test
  void contrastOfTextMeetsAaInBothPalettes() {
    for (var p : List.of(light(), dark())) {
      var bg = p.get("--bg");
      for (var k :
          List.of(
              "--fg",
              "--muted",
              "--subtle",
              "--strong",
              "--link",
              "--success-text",
              "--failed-text",
              "--cancelled-text",
              "--unknown-text",
              "--critical-text")) {
        assertThat(ratio(p.get(k), bg)).as(k + " on --bg").isGreaterThanOrEqualTo(4.5);
      }
      assertThat(ratio(p.get("--fg"), p.get("--th-bg")))
          .as("table header")
          .isGreaterThanOrEqualTo(4.5);
      assertThat(ratio(p.get("--code-fg"), p.get("--code-bg")))
          .as("stack trace")
          .isGreaterThanOrEqualTo(4.5);
      assertThat(ratio(p.get("--strong"), p.get("--input-bg")))
          .as("button")
          .isGreaterThanOrEqualTo(4.5);
      assertThat(ratio(p.get("--fg"), p.get("--input-bg"))).as("input").isGreaterThanOrEqualTo(4.5);
    }
  }

  @Test
  void contrastOfBarsAgainstPageBackground() {
    var d = dark();
    for (var c : List.of(SUCCESS, FAILED, CANCELLED, INCOMPLETE, SCOPE_BAR, CRITICAL)) {
      assertThat(ratio(c, d.get("--bg"))).as(c + " on dark").isGreaterThanOrEqualTo(3.0);
    }
    // The unchanged fills cannot reach 3:1 on the light page (success 2.66, cancelled 2.27,
    // incomplete 2.57); the bars carry their outcome in the table as well. See issue #191.
    var l = light();
    for (var c : List.of(FAILED, SCOPE_BAR, CRITICAL)) {
      assertThat(ratio(c, l.get("--bg"))).as(c + " on light").isGreaterThanOrEqualTo(3.0);
    }
  }

  @Test
  void structureIsThemeIndependent() {
    var h = html();
    assertThat(h)
        .contains(
            "id=\"trace-data\"", "id=\"errors-only\"", "id=\"export-json\"", "id=\"scope-1\"");
    assertThat(h).contains("class=\"track\"", "class=\"tick\"", "class=\"bar-label\"");
  }

  @Test
  void criticalPathStrokeOnlyWhenAllSucceed() {
    var tasks =
        List.of(
            task(1, 1, 10, new TaskOutcome.Success()),
            task(2, 1, 20, new TaskOutcome.Failed("java.lang.RuntimeException", null, null)));
    var h =
        HtmlRenderer.render(
            new TraceModel(
                List.of(
                    new ScopeRecord(1L, "f", "main", -1L, T0, T0.plusMillis(30), tasks, null))));
    assertThat(h).doesNotContain("stroke=\"" + CRITICAL + "\"");
  }

  private static double ratio(String a, String b) {
    double la = lum(a), lb = lum(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double lum(String hex) {
    int v = Integer.parseInt(hex.substring(1), 16);
    double[] c = {(v >> 16 & 255) / 255.0, (v >> 8 & 255) / 255.0, (v & 255) / 255.0};
    for (int i = 0; i < 3; i++) {
      c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }
}
