package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.demos.Shape.ObservedScope;
import com.ionutbanu.scopetracer.demos.Shape.ObservedTask;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the scopes and task rows out of a generated HTML report, so that a report on disk can be
 * compared with the {@link Shape} its scenario declares without running anything. It parses what
 * {@code HtmlRenderer} writes (a {@code <section>} per scope with an {@code <h2>} and a table), and
 * nothing more; it is not an HTML parser.
 */
final class ReportRows {

  private ReportRows() {}

  private static final Pattern SECTION =
      Pattern.compile("<section[^>]*>\\s*<h2>(.*?)</h2>(.*?)</section>", Pattern.DOTALL);
  private static final Pattern PARENT_REF =
      Pattern.compile("<span class=\"parent-ref\">↳ task \\d+ of (.*?)</span>", Pattern.DOTALL);
  private static final Pattern ROW =
      Pattern.compile(
          "<tr[^>]*><td>\\d+</td><td>(.*?)</td><td>.*?</td><td>\\+.*?</td><td>.*?</td><td>(.*?)</td></tr>",
          Pattern.DOTALL);
  private static final Pattern OUTCOME =
      Pattern.compile("<span class=\"outcome-(success|failed|cancelled|unknown)\">(.*?)</span>");
  private static final Pattern NESTED_REF =
      Pattern.compile("<span class=\"nested-scope-ref\">↳ (.*?)</span>");

  /**
   * The scopes of a report, in document order, with the parent task found from the parent's rows.
   */
  static List<ObservedScope> observe(String html) {
    record Raw(String name, String parent, List<ObservedTask> tasks, List<String[]> nestedRefs) {}
    var raw = new ArrayList<Raw>();
    var matcher = SECTION.matcher(html);
    while (matcher.find()) {
      var heading = matcher.group(1);
      var name = unescape(heading.replaceAll("(?s)<.*", "").strip());
      var parentMatch = PARENT_REF.matcher(heading);
      var parent = parentMatch.find() ? unescape(parentMatch.group(1)) : null;
      var tasks = new ArrayList<ObservedTask>();
      var nestedRefs = new ArrayList<String[]>();
      var rows = ROW.matcher(matcher.group(2));
      while (rows.find()) {
        var taskName = unescape(rows.group(1));
        var cell = rows.group(2);
        var outcome = OUTCOME.matcher(cell);
        String kind = "incomplete";
        String detail = null;
        if (outcome.find()) {
          kind = outcome.group(1).equals("unknown") ? "incomplete" : outcome.group(1);
          if (kind.equals("failed")) {
            detail = unescape(outcome.group(2).replaceFirst("^failed: ", ""));
          }
        }
        var nested = NESTED_REF.matcher(cell);
        if (nested.find()) {
          nestedRefs.add(new String[] {unescape(nested.group(1)), taskName});
        }
        tasks.add(new ObservedTask(taskName, kind, detail));
      }
      raw.add(new Raw(name, parent, tasks, nestedRefs));
    }
    var observed = new ArrayList<ObservedScope>();
    for (var scope : raw) {
      String parentTask = null;
      if (scope.parent() != null) {
        for (var candidate : raw) {
          if (!candidate.name().equals(scope.parent())) continue;
          for (var ref : candidate.nestedRefs()) {
            if (ref[0].equals(scope.name())) parentTask = ref[1];
          }
        }
      }
      observed.add(new ObservedScope(scope.name(), scope.parent(), parentTask, scope.tasks()));
    }
    return observed;
  }

  private static String unescape(String s) {
    return s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&");
  }
}
