package dev.scopetracer.analyzer;

import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TaskRecord;
import dev.scopetracer.analyzer.model.TraceModel;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link TraceModel} as a self-contained HTML report with an inline SVG Gantt timeline.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * String html = HtmlRenderer.render(model);
 * Files.writeString(Path.of("report.html"), html);
 * }</pre>
 */
public final class HtmlRenderer {

  // SVG coordinate width for per-scope timelines; task bars fill this space.
  private static final double TIMELINE_W = 800.0;

  private static final String COLOR_SUCCESS = "#4caf50";
  private static final String COLOR_CRITICAL_STROKE = "#d97706";
  private static final String COLOR_FAILED = "#e53935";
  private static final String COLOR_CANCELLED = "#fb8c00";
  private static final String COLOR_INCOMPLETE = "#9e9e9e";
  private static final String COLOR_SCOPE_BAR = "#1565c0";

  private static final DateTimeFormatter TIMESTAMP_FMT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private HtmlRenderer() {}

  /**
   * Renders the trace model as a self-contained HTML document. The document contains inline CSS,
   * SVG, and JavaScript; no external resources are referenced.
   *
   * @param model the trace to render; must not be {@code null}.
   * @return the full HTML document as a string.
   */
  public static String render(TraceModel model) {
    var sb = new StringBuilder();
    sb.append(
        """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="UTF-8"/>
        <title>scope-tracer report</title>
        <style>
        body { font-family: monospace; background: #fafafa; color: #212121; margin: 2rem; max-width: 1200px; }
        h1   { font-size: 1.2rem; margin-bottom: 0.25rem; }
        .summary { color: #616161; font-size: 0.85rem; margin-bottom: 1rem; }
        section  { margin-bottom: 2rem; }
        h2   { font-size: 1rem; margin-bottom: 0.5rem; border-bottom: 1px solid #e0e0e0; padding-bottom: 0.25rem; }
        .scope-meta { color: #616161; font-size: 0.8rem; margin-bottom: 0.75rem; }
        table { border-collapse: collapse; font-size: 0.82rem; margin-bottom: 0.75rem; width: 100%; }
        th,td { text-align: left; padding: 3px 8px; border-bottom: 1px solid #e0e0e0; }
        th { background: #f5f5f5; }
        .outcome-success   { color: #2e7d32; font-weight: bold; }
        .outcome-failed    { color: #c62828; font-weight: bold; }
        .outcome-cancelled { color: #e65100; font-weight: bold; }
        .outcome-unknown   { color: #757575; }
        .timeline-wrap { overflow-x: auto; margin-bottom: 0.5rem; }
        svg.timeline { display: block; width: 100%; height: auto; }
        .child-scope { margin-left: 2rem; border-left: 3px solid #1565c0; padding-left: 1rem; }
        .parent-ref  { font-size: 0.75rem; color: #616161; font-weight: normal; margin-left: 0.5rem; }
        .nested-scope-ref { font-size: 0.75rem; color: #1565c0; margin-left: 0.5rem; }
        .critical-path-note { color: #d97706; font-size: 0.75rem; margin-left: 0.3rem; }
        .legend { display: flex; gap: 1.2rem; font-size: 0.8rem; margin-bottom: 1rem; color: #616161; flex-wrap: wrap; }
        .legend-item { display: flex; align-items: center; gap: 0.3rem; }
        .legend-swatch { display: inline-block; width: 12px; height: 12px; border-radius: 2px; flex-shrink: 0; }
        .filter-bar { display: flex; gap: 1rem; align-items: center; margin-bottom: 1.5rem; flex-wrap: wrap; font-size: 0.85rem; color: #616161; }
        .filter-bar input[type="text"] { font-family: monospace; font-size: 0.85rem; padding: 0.3rem 0.6rem; border: 1px solid #bdbdbd; border-radius: 3px; width: 200px; background: #fff; }
        .filter-bar label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; }
        .filter-sep { color: #bdbdbd; }
        .overview-label { font-size: 0.75rem; color: #9e9e9e; margin-bottom: 0.2rem; }
        details.scope-group { margin-bottom: 1.5rem; }
        details.scope-group > summary { cursor: pointer; font-size: 0.9rem; font-weight: bold; padding: 0.4rem 0; list-style: none; border-bottom: 2px solid #e0e0e0; margin-bottom: 1rem; color: #424242; }
        details.scope-group > summary::marker, details.scope-group > summary::-webkit-details-marker { display: none; }
        details.scope-group > summary::before { content: "▶ "; font-size: 0.65rem; color: #9e9e9e; }
        details.scope-group[open] > summary::before { content: "▼ "; }
        .group-stats { font-size: 0.75rem; font-weight: normal; color: #757575; margin-left: 0.5rem; }
        </style>
        </head>
        <body>
        <h1>scope-tracer report</h1>
        <div class="legend">
        <span class="legend-item"><span class="legend-swatch" style="background:#1565c0"></span>scope lifetime</span>
        <span class="legend-item"><span class="legend-swatch" style="background:#4caf50"></span>success</span>
        <span class="legend-item"><span class="legend-swatch" style="background:#e53935"></span>failed</span>
        <span class="legend-item"><span class="legend-swatch" style="background:#fb8c00"></span>cancelled</span>
        <span class="legend-item"><span class="legend-swatch" style="background:#9e9e9e"></span>incomplete</span>
        <span class="legend-item"><span class="legend-swatch" style="background:#4caf50;box-shadow:0 0 0 2px #d97706"></span>critical path</span>
        </div>
        """);

    if (model.scopes().isEmpty()) {
      sb.append("<p class=\"summary\">No scopes found in recording.</p>\n</body>\n</html>\n");
      return sb.toString();
    }

    var globalMin =
        model.scopes().stream().map(ScopeRecord::openTime).min(Instant::compareTo).get();
    var globalMax =
        model.scopes().stream()
            .map(s -> s.closeTime() != null ? s.closeTime() : s.openTime())
            .max(Instant::compareTo)
            .get();
    var totalNs = Math.max(Duration.between(globalMin, globalMax).toNanos(), 1L);

    var rootScopes = model.scopes().stream().filter(s -> s.parent() == null).toList();

    // Build children / nested-scope lookup maps
    var children = new HashMap<String, List<ScopeRecord>>();
    var nestedScopes = new HashMap<String, Map<Long, String>>();
    for (var scope : model.scopes()) {
      if (scope.parent() != null) {
        children.computeIfAbsent(scope.parent().scopeName(), k -> new ArrayList<>()).add(scope);
        nestedScopes
            .computeIfAbsent(scope.parent().scopeName(), k -> new HashMap<>())
            .put(scope.parent().taskId(), scope.name());
      }
    }

    // ── Summary stats ──────────────────────────────────────────────────────────
    renderSummaryStats(sb, rootScopes, totalNs);

    // ── Filter / search bar ────────────────────────────────────────────────────
    sb.append(
        """
        <div class="filter-bar">
        <input type="text" id="search" placeholder="filter by name…" autocomplete="off"/>
        <span class="filter-sep">|</span>
        <label><input type="checkbox" id="show-success" checked> success</label>
        <label><input type="checkbox" id="show-failed" checked> failed</label>
        <label><input type="checkbox" id="show-cancelled" checked> cancelled</label>
        </div>
        """);

    // ── Global wall-clock overview ─────────────────────────────────────────────
    renderGlobalOverview(sb, rootScopes, globalMin, totalNs);

    // ── Scope sections, grouped by base name ──────────────────────────────────
    var groups = groupScopes(rootScopes);
    int sectionIndex = 0;
    for (var entry : groups.entrySet()) {
      var group = entry.getValue();
      boolean isGroup = group.size() > 1;
      if (isGroup) {
        sb.append("<details class=\"scope-group\" open>\n<summary>")
            .append(escape(entry.getKey()))
            .append(
                " <span class=\"group-stats\">("
                    + group.size()
                    + " scopes &nbsp;·&nbsp; "
                    + computeGroupStats(group)
                    + ")</span></summary>\n");
      }
      for (var scope : group) {
        renderScope(sb, scope, "scope-" + sectionIndex, 0, children, nestedScopes);
        sectionIndex++;
      }
      if (isGroup) {
        sb.append("</details>\n");
      }
    }

    // ── Inline filter JS ───────────────────────────────────────────────────────
    sb.append(
        """
        <script>
        (function(){
          var search   = document.getElementById('search');
          var okSuccess  = document.getElementById('show-success');
          var okFailed   = document.getElementById('show-failed');
          var okCancelled = document.getElementById('show-cancelled');
          function update() {
            var q = search.value.trim().toLowerCase();
            document.querySelectorAll('section[data-scope]').forEach(function(s) {
              var nm = s.dataset.scope.toLowerCase();
              var oc = s.dataset.outcome;
              var nameOk = !q || nm.indexOf(q) >= 0;
              var outcomeOk =
                (oc === 'success'    && okSuccess.checked)   ||
                (oc === 'failed'     && okFailed.checked)    ||
                (oc === 'cancelled'  && okCancelled.checked) ||
                (oc === 'incomplete');
              s.style.display = (nameOk && outcomeOk) ? '' : 'none';
            });
            document.querySelectorAll('details.scope-group').forEach(function(d) {
              var any = Array.from(d.querySelectorAll('section[data-scope]'))
                .some(function(s){ return s.style.display !== 'none'; });
              d.style.display = any ? '' : 'none';
            });
          }
          search.addEventListener('input', update);
          okSuccess.addEventListener('change', update);
          okFailed.addEventListener('change', update);
          okCancelled.addEventListener('change', update);
        })();
        </script>
        """);

    sb.append("</body>\n</html>\n");
    return sb.toString();
  }

  // ── Summary stats ────────────────────────────────────────────────────────────

  private static void renderSummaryStats(
      StringBuilder sb, List<ScopeRecord> rootScopes, long totalNs) {
    long succeeded = rootScopes.stream().filter(s -> "success".equals(scopeOutcome(s))).count();
    long failed = rootScopes.stream().filter(s -> "failed".equals(scopeOutcome(s))).count();
    long cancelled = rootScopes.stream().filter(s -> "cancelled".equals(scopeOutcome(s))).count();

    var durations =
        rootScopes.stream()
            .filter(s -> s.closeTime() != null)
            .map(s -> Duration.between(s.openTime(), s.closeTime()).toNanos())
            .sorted()
            .toList();

    sb.append("<p class=\"summary\">")
        .append(rootScopes.size())
        .append(" scope(s) &nbsp;|&nbsp;")
        .append(" <span style=\"color:#2e7d32\">")
        .append(succeeded)
        .append(" success</span>");
    if (failed > 0)
      sb.append(" &nbsp;·&nbsp; <span style=\"color:#c62828\">")
          .append(failed)
          .append(" failed</span>");
    if (cancelled > 0)
      sb.append(" &nbsp;·&nbsp; <span style=\"color:#e65100\">")
          .append(cancelled)
          .append(" cancelled</span>");
    if (!durations.isEmpty()) {
      long median = durations.get(durations.size() / 2);
      sb.append(" &nbsp;|&nbsp; median: ").append(formatDuration(Duration.ofNanos(median)));
      if (durations.size() >= 5) {
        int p95idx = Math.min((int) Math.ceil(durations.size() * 0.95) - 1, durations.size() - 1);
        sb.append(" &nbsp;|&nbsp; p95: ")
            .append(formatDuration(Duration.ofNanos(durations.get(p95idx))));
      }
    }
    sb.append(" &nbsp;|&nbsp; wall: ").append(formatDuration(Duration.ofNanos(totalNs)));
    sb.append("</p>\n");
  }

  // ── Global overview SVG ──────────────────────────────────────────────────────

  /**
   * Renders a horizontal wall-clock bar spanning the full recording window. Each root scope is a
   * coloured rectangle; clicking it scrolls to that scope's section. Uses {@code style="fill:..."}
   * (not bare {@code fill="..."} SVG attributes) so count-based tests on per-scope bars are
   * unaffected.
   */
  private static void renderGlobalOverview(
      StringBuilder sb, List<ScopeRecord> rootScopes, Instant globalMin, long totalNs) {
    sb.append("<p class=\"overview-label\">recording timeline — click a scope to jump</p>\n");
    sb.append("<div class=\"timeline-wrap\" style=\"margin-bottom:1.5rem\">\n");
    sb.append(
        "<svg class=\"timeline\" viewBox=\"0 0 800 44\" xmlns=\"http://www.w3.org/2000/svg\">\n");
    // background track
    sb.append("<rect x=\"0\" y=\"8\" width=\"800\" height=\"18\" fill=\"#f0f0f0\" rx=\"2\"/>\n");

    int i = 0;
    for (var scope : rootScopes) {
      long startNs = Duration.between(globalMin, scope.openTime()).toNanos();
      Instant endInstant =
          scope.closeTime() != null ? scope.closeTime() : scope.openTime().plusMillis(1);
      long endNs = Duration.between(globalMin, endInstant).toNanos();
      double x = (double) startNs / totalNs * TIMELINE_W;
      double w = Math.max((double) (endNs - startNs) / totalNs * TIMELINE_W, 3.0);
      String color = scopeOutcomeColor(scope);
      // Use style="fill:..." to keep inline fill="..." attributes exclusive to per-scope bars.
      sb.append("<a href=\"#scope-")
          .append(i)
          .append("\" style=\"text-decoration:none\">\n")
          .append("<rect x=\"")
          .append(f(x))
          .append("\" y=\"8\" width=\"")
          .append(f(w))
          .append("\" height=\"18\" rx=\"1\" style=\"fill:")
          .append(color)
          .append(";opacity:0.85\">\n<title>")
          .append(escape(scope.name()))
          .append(" | ")
          .append(formatDuration(Duration.between(scope.openTime(), endInstant)))
          .append("</title>\n</rect>\n</a>\n");
      i++;
    }

    // Start / end timestamp labels
    sb.append("<text x=\"0\" y=\"40\" font-size=\"8\" fill=\"#9e9e9e\" font-family=\"monospace\">")
        .append(escape(TIMESTAMP_FMT.format(globalMin)))
        .append("</text>\n");
    sb.append(
            "<text x=\"800\" y=\"40\" font-size=\"8\" fill=\"#9e9e9e\""
                + " font-family=\"monospace\" text-anchor=\"end\">")
        .append(escape(TIMESTAMP_FMT.format(globalMin.plusNanos(totalNs))))
        .append("</text>\n");

    sb.append("</svg>\n</div>\n");
  }

  // ── Per-scope section ────────────────────────────────────────────────────────

  private static void renderScope(
      StringBuilder sb,
      ScopeRecord scope,
      String sectionId,
      int depth,
      Map<String, List<ScopeRecord>> children,
      Map<String, Map<Long, String>> nestedScopes) {

    var openTime = scope.openTime();
    var closeTime = scope.closeTime() != null ? scope.closeTime() : openTime;
    var scopeDurationNs = Math.max(Duration.between(openTime, closeTime).toNanos(), 1L);

    if (depth > 0) sb.append("<div class=\"child-scope\">\n");

    // <section> with anchor id and filter data attributes (root scopes only)
    sb.append("<section");
    if (sectionId != null) {
      sb.append(" id=\"").append(sectionId).append("\"");
      sb.append(" data-scope=\"").append(escape(scope.name())).append("\"");
      sb.append(" data-outcome=\"").append(scopeOutcome(scope)).append("\"");
    }
    sb.append(">\n<h2>").append(escape(scope.name()));
    if (scope.parent() != null) {
      sb.append(" <span class=\"parent-ref\">↳ task ")
          .append(scope.parent().taskId())
          .append(" of ")
          .append(escape(scope.parent().scopeName()))
          .append("</span>");
    }
    sb.append("</h2>\n");

    sb.append("<div class=\"scope-meta\">")
        .append("thread: ")
        .append(escape(displayThread(scope.ownerThreadName(), scope.ownerThreadId())))
        .append(" &nbsp;|&nbsp; opened: ")
        .append(TIMESTAMP_FMT.format(openTime))
        .append(" &nbsp;|&nbsp; duration: ")
        .append(formatDuration(Duration.between(openTime, closeTime)))
        .append(" &nbsp;|&nbsp; tasks: ")
        .append(scope.tasks().size())
        .append("</div>\n");

    // Earliest failed task — trigger for cancellations
    long triggerId =
        scope.tasks().stream()
            .filter(t -> t.outcome() instanceof TaskOutcome.Failed)
            .min(
                Comparator.comparing(
                    t -> t.completionTime() != null ? t.completionTime() : Instant.MAX))
            .map(TaskRecord::taskId)
            .orElse(-1L);

    // Critical path task (all-success scopes only)
    boolean allSucceeded =
        !scope.tasks().isEmpty()
            && scope.tasks().stream()
                .allMatch(
                    t -> t.outcome() instanceof TaskOutcome.Success && t.completionTime() != null);
    long criticalTaskId =
        allSucceeded
            ? scope.tasks().stream()
                .max(Comparator.comparing(TaskRecord::completionTime))
                .map(TaskRecord::taskId)
                .orElse(-1L)
            : -1L;

    // ── Task table ─────────────────────────────────────────────────────────────
    sb.append(
        "<table>\n<tr><th>#</th><th>thread</th><th>fork offset</th><th>duration</th><th>outcome</th></tr>\n");
    var scopeNestedScopes = nestedScopes.getOrDefault(scope.name(), Map.of());
    for (var task : scope.tasks()) {
      var forkOffset = Duration.between(openTime, task.forkTime());
      var taskDuration =
          task.completionTime() != null
              ? Duration.between(task.forkTime(), task.completionTime())
              : null;
      var nestedScopeName = scopeNestedScopes.get(task.taskId());
      boolean isCritical = task.taskId() == criticalTaskId;
      String criticalDelta =
          isCritical && task.completionTime() != null
              ? formatDuration(Duration.between(openTime, task.completionTime()))
              : null;
      sb.append("<tr>")
          .append("<td>")
          .append(task.taskId())
          .append("</td>")
          .append("<td>")
          .append(escape(displayThread(task.threadName(), task.threadId())))
          .append("</td>")
          .append("<td>+")
          .append(formatDuration(forkOffset))
          .append("</td>")
          .append("<td>")
          .append(taskDuration != null ? formatDuration(taskDuration) : "—")
          .append("</td>")
          .append("<td>")
          .append(
              outcomeCell(task.outcome(), nestedScopeName, triggerId, isCritical, criticalDelta))
          .append("</td>")
          .append("</tr>\n");
    }
    sb.append("</table>\n");

    // ── SVG timeline ───────────────────────────────────────────────────────────
    // The scope bar always fills TIMELINE_W units. Task bars are scaled relative to
    // scopeDurationNs, so they fill the same space regardless of recording length.
    // This prevents thin/invisible bars when scope duration << recording duration.
    int svgHeight = 32 + scope.tasks().size() * 26 + 28;
    sb.append("<div class=\"timeline-wrap\">\n");
    sb.append("<svg class=\"timeline\" viewBox=\"0 0 ")
        .append((int) TIMELINE_W)
        .append(" ")
        .append(svgHeight)
        .append("\" xmlns=\"http://www.w3.org/2000/svg\">\n");

    // Scope lifetime bar — always full width
    sb.append("<rect x=\"0\" y=\"2\" width=\"")
        .append((int) TIMELINE_W)
        .append("\" height=\"18\" fill=\"")
        .append(COLOR_SCOPE_BAR)
        .append("\" rx=\"2\">\n<title>")
        .append(escape(scope.name()))
        .append(" | ")
        .append(formatDuration(Duration.between(openTime, closeTime)))
        .append("</title>\n</rect>\n");

    // Task bars — x and width relative to scope duration
    int row = 0;
    for (var task : scope.tasks()) {
      int y = 32 + row * 26;
      long taskForkNs = Duration.between(openTime, task.forkTime()).toNanos();
      double taskX = (double) taskForkNs / scopeDurationNs * TIMELINE_W;
      double taskW;
      if (task.completionTime() != null) {
        long taskDurNs = Duration.between(task.forkTime(), task.completionTime()).toNanos();
        taskW = Math.max((double) taskDurNs / scopeDurationNs * TIMELINE_W, 4.0);
      } else {
        taskW = 4.0;
      }
      boolean isCriticalBar = task.taskId() == criticalTaskId;
      var color = outcomeColor(task.outcome());
      String criticalBarDelta =
          isCriticalBar && task.completionTime() != null
              ? formatDuration(Duration.between(openTime, task.completionTime()))
              : null;

      sb.append("<svg x=\"")
          .append(f(taskX))
          .append("\" y=\"")
          .append(y)
          .append("\" width=\"")
          .append(f(taskW))
          .append("\" height=\"18\" overflow=\"hidden\">\n")
          .append("<rect x=\"0\" y=\"0\" width=\"")
          .append(f(taskW))
          .append("\" height=\"18\" fill=\"")
          .append(color)
          .append("\" rx=\"2\">\n<title>task ")
          .append(task.taskId())
          .append(" | ")
          .append(escape(displayThread(task.threadName(), task.threadId())))
          .append(" | ")
          .append(outcomeTooltip(task.outcome(), triggerId, isCriticalBar, criticalBarDelta))
          .append("</title>\n</rect>\n");

      if (isCriticalBar) {
        // Gold outline — visually distinct from cancelled orange
        sb.append("<rect x=\"0\" y=\"0\" width=\"")
            .append(f(taskW))
            .append("\" height=\"18\" fill=\"none\" stroke=\"")
            .append(COLOR_CRITICAL_STROKE)
            .append("\" stroke-width=\"2\" rx=\"2\"/>\n");
      }

      sb.append("<text x=\"4\" y=\"13\" fill=\"white\" font-size=\"11\" ")
          .append("font-family=\"monospace\" pointer-events=\"none\">")
          .append("#")
          .append(task.taskId())
          .append("</text>\n</svg>\n");
      row++;
    }

    // Time axis — ticks capped at 8 to prevent label overlap
    int axisY = 32 + scope.tasks().size() * 26 + 6;
    int labelY = axisY + 12;
    sb.append("<line x1=\"0\" y1=\"")
        .append(axisY)
        .append("\" x2=\"")
        .append((int) TIMELINE_W)
        .append("\" y2=\"")
        .append(axisY)
        .append("\" stroke=\"#bdbdbd\" stroke-width=\"1\"/>\n");
    long tickIntervalNs = niceTickIntervalNs(scopeDurationNs, 8);
    for (long tickNs = 0; tickNs <= scopeDurationNs; tickNs += tickIntervalNs) {
      double tickX = (double) tickNs / scopeDurationNs * TIMELINE_W;
      sb.append("<line x1=\"")
          .append(f(tickX))
          .append("\" y1=\"")
          .append(axisY - 3)
          .append("\" x2=\"")
          .append(f(tickX))
          .append("\" y2=\"")
          .append(axisY + 3)
          .append("\" stroke=\"#bdbdbd\" stroke-width=\"1\"/>\n");
      String label = tickNs == 0 ? "0" : "+" + formatAxisLabel(tickNs);
      sb.append("<text x=\"")
          .append(f(tickX + 2))
          .append("\" y=\"")
          .append(labelY)
          .append("\" font-size=\"8\" fill=\"#9e9e9e\" font-family=\"monospace\">")
          .append(escape(label))
          .append("</text>\n");
    }

    sb.append("</svg>\n</div>\n</section>\n");

    // Child scopes (rendered inside the parent section, below the timeline)
    for (var child : children.getOrDefault(scope.name(), List.of())) {
      renderScope(sb, child, null, depth + 1, children, nestedScopes);
    }
    if (depth > 0) sb.append("</div>\n");
  }

  // ── Grouping helpers ─────────────────────────────────────────────────────────

  /**
   * Strips a trailing numeric suffix (e.g. {@code "order-19"} → {@code "order"}) to group
   * repeatedly-named scopes. Names without a numeric suffix are returned unchanged.
   */
  private static String groupKey(String name) {
    int lastDash = name.lastIndexOf('-');
    if (lastDash <= 0) return name;
    String suffix = name.substring(lastDash + 1);
    boolean numericSuffix = !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
    return numericSuffix ? name.substring(0, lastDash) : name;
  }

  /**
   * Extracts the trailing numeric suffix from a scope name (the part after the last {@code -} when
   * it consists entirely of digits), or {@code ""} when the name has no such suffix.
   *
   * <p>Examples: {@code "order-processing-001"} → {@code "001"}, {@code "checkout-flow"} → {@code
   * ""}.
   */
  private static String numericSuffix(String name) {
    int lastDash = name.lastIndexOf('-');
    if (lastDash < 0) return "";
    String suffix = name.substring(lastDash + 1);
    return (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) ? suffix : "";
  }

  /**
   * Groups root scopes for display, using one of two strategies:
   *
   * <ul>
   *   <li><b>Per-instance grouping</b> (option 2): when a numeric suffix (e.g. {@code "001"})
   *       appears across ≥ 2 distinct base names, all scopes sharing that suffix are placed in a
   *       single group keyed by the suffix. This keeps pipeline stages of the same request (e.g.
   *       {@code order-processing-001} + {@code order-dispatch-001}) together in the report.
   *   <li><b>Per-name grouping</b> (fallback): scopes whose suffix is unique to a single base name
   *       — or have no numeric suffix at all — are grouped by their base name prefix, exactly as
   *       before.
   * </ul>
   *
   * <p>Insertion order is preserved so scopes appear in the report sorted by their first open time.
   */
  private static LinkedHashMap<String, List<ScopeRecord>> groupScopes(
      List<ScopeRecord> rootScopes) {
    // Pass 1: discover which numeric suffixes appear across ≥2 distinct base names.
    var suffixToBaseNames = new HashMap<String, Set<String>>();
    for (var scope : rootScopes) {
      String suffix = numericSuffix(scope.name());
      if (!suffix.isEmpty()) {
        suffixToBaseNames.computeIfAbsent(suffix, k -> new HashSet<>()).add(groupKey(scope.name()));
      }
    }
    // Suffixes with ≥2 distinct base names are "correlated" (pipeline stages of the same instance).
    var correlatedSuffixes = new HashSet<String>();
    for (var entry : suffixToBaseNames.entrySet()) {
      if (entry.getValue().size() >= 2) correlatedSuffixes.add(entry.getKey());
    }

    // Pass 2: assign each scope to its group key.
    var groups = new LinkedHashMap<String, List<ScopeRecord>>();
    for (var scope : rootScopes) {
      String suffix = numericSuffix(scope.name());
      String key =
          (!suffix.isEmpty() && correlatedSuffixes.contains(suffix))
              ? suffix
              : groupKey(scope.name());
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(scope);
    }
    return groups;
  }

  /** Returns a one-line stat summary for use in a group {@code <summary>} element. */
  private static String computeGroupStats(List<ScopeRecord> group) {
    long succeeded = group.stream().filter(s -> "success".equals(scopeOutcome(s))).count();
    long failed = group.stream().filter(s -> "failed".equals(scopeOutcome(s))).count();
    var durations =
        group.stream()
            .filter(s -> s.closeTime() != null)
            .map(s -> Duration.between(s.openTime(), s.closeTime()).toNanos())
            .sorted()
            .toList();
    var out = new StringBuilder();
    out.append(succeeded).append("/").append(group.size()).append(" success");
    if (failed > 0) out.append(", ").append(failed).append(" failed");
    if (!durations.isEmpty()) {
      long median = durations.get(durations.size() / 2);
      out.append(" · median ").append(formatDuration(Duration.ofNanos(median)));
    }
    return out.toString();
  }

  // ── Outcome helpers ──────────────────────────────────────────────────────────

  /** Returns the worst outcome of a scope as a lowercase string for {@code data-outcome}. */
  private static String scopeOutcome(ScopeRecord scope) {
    if (scope.tasks().isEmpty()) return "incomplete";
    if (scope.tasks().stream().anyMatch(t -> t.outcome() instanceof TaskOutcome.Failed))
      return "failed";
    if (scope.tasks().stream().anyMatch(t -> t.outcome() instanceof TaskOutcome.Cancelled))
      return "cancelled";
    if (scope.tasks().stream().allMatch(t -> t.outcome() instanceof TaskOutcome.Success))
      return "success";
    return "incomplete";
  }

  /** Color for the scope bar in the global overview. */
  private static String scopeOutcomeColor(ScopeRecord scope) {
    return switch (scopeOutcome(scope)) {
      case "failed" -> COLOR_FAILED;
      case "cancelled" -> COLOR_CANCELLED;
      case "success" -> COLOR_SUCCESS;
      default -> COLOR_INCOMPLETE;
    };
  }

  private static String outcomeCell(
      TaskOutcome outcome,
      String nestedScopeName,
      long triggerId,
      boolean isCritical,
      String criticalDelta) {
    var cell =
        outcome == null
            ? "<span class=\"outcome-unknown\">—</span>"
            : switch (outcome) {
              case TaskOutcome.Success s -> "<span class=\"outcome-success\">success</span>";
              case TaskOutcome.Failed f ->
                  "<span class=\"outcome-failed\">failed: " + escape(f.exceptionType()) + "</span>";
              case TaskOutcome.Cancelled c ->
                  triggerId >= 0
                      ? "<span class=\"outcome-cancelled\">cancelled ← #" + triggerId + "</span>"
                      : "<span class=\"outcome-cancelled\">cancelled</span>";
            };
    if (isCritical && criticalDelta != null) {
      cell +=
          " <span class=\"critical-path-note\">← critical path +"
              + escape(criticalDelta)
              + "</span>";
    }
    if (nestedScopeName == null) return cell;
    return cell + " <span class=\"nested-scope-ref\">↳ " + escape(nestedScopeName) + "</span>";
  }

  private static String outcomeColor(TaskOutcome outcome) {
    if (outcome == null) return COLOR_INCOMPLETE;
    return switch (outcome) {
      case TaskOutcome.Success s -> COLOR_SUCCESS;
      case TaskOutcome.Failed f -> COLOR_FAILED;
      case TaskOutcome.Cancelled c -> COLOR_CANCELLED;
    };
  }

  private static String outcomeTooltip(
      TaskOutcome outcome, long triggerId, boolean isCritical, String criticalDelta) {
    if (outcome == null) return "incomplete";
    String base =
        switch (outcome) {
          case TaskOutcome.Success s -> "success";
          case TaskOutcome.Failed f -> "failed: " + f.exceptionType();
          case TaskOutcome.Cancelled c ->
              triggerId >= 0 ? "cancelled ← #" + triggerId : "cancelled";
        };
    if (isCritical && criticalDelta != null) base += " · critical path +" + criticalDelta;
    return base;
  }

  // ── Tick / formatting helpers ─────────────────────────────────────────────────

  /**
   * Picks a "nice" tick interval so at most {@code maxTicks} ticks appear on the axis. The interval
   * is always a round number of ns/µs/ms/s to keep labels readable.
   */
  private static long niceTickIntervalNs(long durationNs, int maxTicks) {
    long[] candidates = {
      1L,
      2L,
      5L,
      10L,
      20L,
      50L,
      100L,
      200L,
      500L,
      1_000L,
      2_000L,
      5_000L,
      10_000L,
      20_000L,
      50_000L,
      100_000L,
      200_000L,
      500_000L,
      1_000_000L,
      2_000_000L,
      5_000_000L,
      10_000_000L,
      20_000_000L,
      50_000_000L,
      100_000_000L,
      200_000_000L,
      500_000_000L,
      1_000_000_000L,
      2_000_000_000L,
      5_000_000_000L
    };
    long target = Math.max(durationNs / maxTicks, 1L);
    for (long c : candidates) {
      if (c >= target) return c;
    }
    return candidates[candidates.length - 1];
  }

  private static String formatAxisLabel(long ns) {
    if (ns == 0) return "0";
    if (ns < 1_000L) return ns + "ns";
    if (ns < 1_000_000L) {
      long v = ns / 1_000L;
      return (ns % 1_000L == 0) ? v + "µs" : String.format(Locale.ROOT, "%.2fµs", ns / 1_000.0);
    }
    if (ns < 1_000_000_000L) {
      long v = ns / 1_000_000L;
      return (ns % 1_000_000L == 0)
          ? v + "ms"
          : String.format(Locale.ROOT, "%.2fms", ns / 1_000_000.0);
    }
    long v = ns / 1_000_000_000L;
    return (ns % 1_000_000_000L == 0)
        ? v + "s"
        : String.format(Locale.ROOT, "%.3fs", ns / 1_000_000_000.0);
  }

  private static String formatDuration(Duration d) {
    long ns = d.toNanos();
    if (ns < 1_000L) return ns + "ns";
    if (ns < 1_000_000L) return String.format(Locale.ROOT, "%.2fµs", ns / 1_000.0);
    if (ns < 1_000_000_000L) return String.format(Locale.ROOT, "%.2fms", ns / 1_000_000.0);
    return String.format(Locale.ROOT, "%.3fs", ns / 1_000_000_000.0);
  }

  private static String displayThread(String threadName) {
    return displayThread(threadName, -1L);
  }

  private static String displayThread(String threadName, long threadId) {
    if (threadName != null && !threadName.isBlank()) return threadName;
    if (threadId > 0) return "vt-" + threadId;
    return "<virtual>";
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String f(double v) {
    return String.format(Locale.ROOT, "%.2f", v);
  }
}
