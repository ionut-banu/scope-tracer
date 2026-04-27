package dev.scopetracer.analyzer;

import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TraceModel;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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

  private static final String COLOR_SUCCESS = "#4caf50";
  private static final String COLOR_FAILED = "#e53935";
  private static final String COLOR_CANCELLED = "#fb8c00";
  private static final String COLOR_INCOMPLETE = "#9e9e9e";
  private static final String COLOR_SCOPE_BAR = "#1565c0";

  private static final DateTimeFormatter TIMESTAMP_FMT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private HtmlRenderer() {}

  /**
   * Renders the trace model as a self-contained HTML document. The document contains inline CSS and
   * SVG; no external resources are referenced.
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
        body { font-family: monospace; background: #fafafa; color: #212121; margin: 2rem; }
        h1   { font-size: 1.2rem; margin-bottom: 0.25rem; }
        .summary { color: #616161; font-size: 0.85rem; margin-bottom: 2rem; }
        section  { margin-bottom: 2.5rem; }
        h2   { font-size: 1rem; margin-bottom: 0.5rem; border-bottom: 1px solid #e0e0e0; padding-bottom: 0.25rem; }
        .scope-meta { color: #616161; font-size: 0.8rem; margin-bottom: 0.75rem; }
        table { border-collapse: collapse; font-size: 0.82rem; margin-bottom: 1rem; width: 100%; }
        th,td { text-align: left; padding: 3px 8px; border-bottom: 1px solid #e0e0e0; }
        th { background: #f5f5f5; }
        .outcome-success   { color: #2e7d32; font-weight: bold; }
        .outcome-failed    { color: #c62828; font-weight: bold; }
        .outcome-cancelled { color: #e65100; font-weight: bold; }
        .outcome-unknown   { color: #757575; }
        .timeline-wrap { overflow-x: auto; }
        svg.timeline { display: block; width: 100%; height: auto; }
        </style>
        </head>
        <body>
        <h1>scope-tracer report</h1>
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

    sb.append("<p class=\"summary\">")
        .append(model.scopes().size())
        .append(" scope(s) &nbsp;|&nbsp; wall time: ")
        .append(formatDuration(Duration.between(globalMin, globalMax)))
        .append("</p>\n");

    for (var scope : model.scopes()) {
      renderScope(sb, scope, globalMin, totalNs);
    }

    sb.append("</body>\n</html>\n");
    return sb.toString();
  }

  private static void renderScope(
      StringBuilder sb, ScopeRecord scope, Instant globalMin, long totalNs) {
    var openTime = scope.openTime();
    var closeTime = scope.closeTime() != null ? scope.closeTime() : openTime;
    var scopeDurationNs = Math.max(Duration.between(openTime, closeTime).toNanos(), 1L);

    sb.append("<section>\n<h2>").append(escape(scope.name())).append("</h2>\n");
    sb.append("<div class=\"scope-meta\">")
        .append("thread: ")
        .append(escape(scope.ownerThreadName()))
        .append(" &nbsp;|&nbsp; opened: ")
        .append(TIMESTAMP_FMT.format(openTime))
        .append(" &nbsp;|&nbsp; duration: ")
        .append(formatDuration(Duration.between(openTime, closeTime)))
        .append(" &nbsp;|&nbsp; tasks: ")
        .append(scope.tasks().size())
        .append("</div>\n");

    // task table
    sb.append(
        "<table>\n<tr><th>#</th><th>thread</th><th>fork offset</th><th>duration</th><th>outcome</th></tr>\n");
    for (var task : scope.tasks()) {
      var forkOffset = Duration.between(openTime, task.forkTime());
      var taskDuration =
          task.completionTime() != null
              ? Duration.between(task.forkTime(), task.completionTime())
              : null;
      sb.append("<tr>")
          .append("<td>")
          .append(task.taskId())
          .append("</td>")
          .append("<td>")
          .append(escape(task.threadName()))
          .append("</td>")
          .append("<td>+")
          .append(formatDuration(forkOffset))
          .append("</td>")
          .append("<td>")
          .append(taskDuration != null ? formatDuration(taskDuration) : "—")
          .append("</td>")
          .append("<td>")
          .append(outcomeCell(task.outcome()))
          .append("</td>")
          .append("</tr>\n");
    }
    sb.append("</table>\n");

    // SVG timeline
    int svgHeight = 20 + scope.tasks().size() * 18 + 10;
    sb.append("<div class=\"timeline-wrap\">\n");
    sb.append("<svg class=\"timeline\" viewBox=\"0 0 800 ")
        .append(svgHeight)
        .append("\" xmlns=\"http://www.w3.org/2000/svg\">\n");

    // scope lifetime bar
    long scopeStartNs = Duration.between(globalMin, openTime).toNanos();
    double scopeXPct = (double) scopeStartNs / totalNs * 800;
    double scopeWidthPct = (double) scopeDurationNs / totalNs * 800;
    sb.append("<rect x=\"")
        .append(f(scopeXPct))
        .append("\" y=\"2\" width=\"")
        .append(f(Math.max(scopeWidthPct, 2)))
        .append("\" height=\"14\" fill=\"")
        .append(COLOR_SCOPE_BAR)
        .append("\" rx=\"2\">\n")
        .append("<title>")
        .append(escape(scope.name()))
        .append(" | ")
        .append(formatDuration(Duration.between(openTime, closeTime)))
        .append("</title>\n</rect>\n");

    // task bars
    int row = 0;
    for (var task : scope.tasks()) {
      int y = 20 + row * 18;
      long taskForkNs = Duration.between(openTime, task.forkTime()).toNanos();
      double taskX = scopeXPct + (double) taskForkNs / scopeDurationNs * scopeWidthPct;
      double taskW;
      if (task.completionTime() != null) {
        long taskDurNs = Duration.between(task.forkTime(), task.completionTime()).toNanos();
        taskW = Math.max((double) taskDurNs / scopeDurationNs * scopeWidthPct, 2);
      } else {
        taskW = 2;
      }
      var color = outcomeColor(task.outcome());
      sb.append("<rect x=\"")
          .append(f(taskX))
          .append("\" y=\"")
          .append(y)
          .append("\" width=\"")
          .append(f(taskW))
          .append("\" height=\"14\" fill=\"")
          .append(color)
          .append("\" rx=\"2\">\n")
          .append("<title>task ")
          .append(task.taskId())
          .append(" | ")
          .append(escape(task.threadName()))
          .append(" | ")
          .append(outcomeTooltip(task.outcome()))
          .append("</title>\n</rect>\n");
      row++;
    }

    sb.append("</svg>\n</div>\n</section>\n");
  }

  private static String outcomeCell(TaskOutcome outcome) {
    if (outcome == null) return "<span class=\"outcome-unknown\">—</span>";
    return switch (outcome) {
      case TaskOutcome.Success s -> "<span class=\"outcome-success\">success</span>";
      case TaskOutcome.Failed f ->
          "<span class=\"outcome-failed\">failed: " + escape(f.exceptionType()) + "</span>";
      case TaskOutcome.Cancelled c -> "<span class=\"outcome-cancelled\">cancelled</span>";
    };
  }

  private static String outcomeColor(TaskOutcome outcome) {
    if (outcome == null) return COLOR_INCOMPLETE;
    return switch (outcome) {
      case TaskOutcome.Success s -> COLOR_SUCCESS;
      case TaskOutcome.Failed f -> COLOR_FAILED;
      case TaskOutcome.Cancelled c -> COLOR_CANCELLED;
    };
  }

  private static String outcomeTooltip(TaskOutcome outcome) {
    if (outcome == null) return "incomplete";
    return switch (outcome) {
      case TaskOutcome.Success s -> "success";
      case TaskOutcome.Failed f -> "failed: " + f.exceptionType();
      case TaskOutcome.Cancelled c -> "cancelled";
    };
  }

  private static String formatDuration(Duration d) {
    long ns = d.toNanos();
    if (ns < 1_000L) return ns + "ns";
    if (ns < 1_000_000L) return String.format("%.2fµs", ns / 1_000.0);
    if (ns < 1_000_000_000L) return String.format("%.2fms", ns / 1_000_000.0);
    return String.format("%.3fs", ns / 1_000_000_000.0);
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String f(double v) {
    return String.format("%.2f", v);
  }
}
