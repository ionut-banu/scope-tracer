package com.ionutbanu.scopetracer.demos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A report with everything that depends on time or on the machine taken out, so that two correct
 * recordings of the same scenario give the same text and a changed renderer, scenario or JDK does
 * not.
 *
 * <p>A report holds absolute timestamps, durations, thread names and ids, and an SVG whose tick
 * count depends on how long the scope took, so two correct runs never match byte for byte. What is
 * dropped: every SVG element, the embedded JSON, every digit (so ids, offsets, line numbers and
 * counts of any kind), durations in any unit, thread names, and the {@code java.base} frames of a
 * stack trace (they differ between JDK builds). What is kept: the markup, the CSS, every task name,
 * every outcome and every exception, and the critical-path note (its delta is a placeholder, its
 * row is compared).
 */
final class ReportSkeleton {

  private ReportSkeleton() {}

  private static final Pattern SVG_TAG = Pattern.compile("<svg\\b|</svg>");
  private static final Pattern TRACE_DATA =
      Pattern.compile(
          "<script type=\"application/json\" id=\"trace-data\">.*?</script>", Pattern.DOTALL);
  private static final Pattern CRITICAL_NOTE =
      Pattern.compile(" ?<span class=\"critical-path-note\">.*?</span>", Pattern.DOTALL);
  private static final Pattern ROW_THREAD =
      Pattern.compile("(<tr[^>]*><td>\\d+</td><td>.*?</td><td>).*?(</td><td>\\+)", Pattern.DOTALL);
  private static final Pattern META_THREAD = Pattern.compile("thread: .*?&nbsp;");
  private static final Pattern DURATION = Pattern.compile("\\d+(?:\\.\\d+)?(?:µs|ms|ns|s)\\b");
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  private static final Pattern TABLE = Pattern.compile("<table>.*?</table>", Pattern.DOTALL);
  private static final String NOTE_MARK = "<span class=\"critical-path-note\">NOTE</span>";

  /** The text two reports of the same scenario share, the critical-path note on its row. */
  static String of(String html) {
    return of(html, false);
  }

  /**
   * The text two reports share. The critical-path note is never deleted: its delta is replaced by a
   * placeholder, so a report that loses the note, or shows it on another row, differs. With {@code
   * noteMayMove} the note is instead reduced to one marker per task table, because in a scope where
   * two tasks end within milliseconds of each other either may be the last.
   */
  static String of(String html, boolean noteMayMove) {
    var text = html.replace("\r\n", "\n");
    text = dropSvg(text);
    text = TRACE_DATA.matcher(text).replaceAll("<data/>");
    text = noteMayMove ? noteOncePerTable(text) : CRITICAL_NOTE.matcher(text).replaceAll(NOTE_MARK);
    text = ROW_THREAD.matcher(text).replaceAll("$1THREAD$2");
    text = META_THREAD.matcher(text).replaceAll("thread: THREAD&nbsp;");
    var lines = new ArrayList<String>();
    for (var line : text.split("\n")) {
      if (line.contains("java.base/") || line.contains("jdk.internal.")) continue;
      line = DURATION.matcher(line).replaceAll("T");
      line = DIGITS.matcher(line).replaceAll("#");
      line = line.strip();
      if (!line.isEmpty()) lines.add(line);
    }
    return String.join("\n", sortSiblings(lines)) + "\n";
  }

  private static String noteOncePerTable(String text) {
    var out = new StringBuilder();
    var tables = TABLE.matcher(text);
    int copied = 0;
    while (tables.find()) {
      out.append(text, copied, tables.start());
      var table = tables.group();
      boolean noted = CRITICAL_NOTE.matcher(table).find();
      table = CRITICAL_NOTE.matcher(table).replaceAll("");
      out.append(noted ? table.replace("</table>", "<!-- critical path -->\n</table>") : table);
      copied = tables.end();
    }
    return out.append(text, copied, text.length()).toString();
  }

  /** Replaces every outermost {@code <svg>...</svg>} (svg elements nest) with {@code <svg/>}. */
  private static String dropSvg(String text) {
    var out = new StringBuilder();
    var tags = SVG_TAG.matcher(text);
    int depth = 0;
    int copied = 0;
    while (tags.find()) {
      if (tags.group().equals("</svg>")) {
        depth--;
        if (depth == 0) {
          copied = tags.end();
          out.append("<svg/>");
        }
      } else {
        if (depth == 0) out.append(text, copied, tags.start());
        depth++;
      }
    }
    return out.append(text, copied, text.length()).toString();
  }

  private static final String CHILD_OPEN = "<div class=\"child-scope\">";

  /**
   * Puts sibling child scopes in a fixed order. The renderer lists the scopes a task opened in the
   * order they opened, and two scopes opened by two tasks forked back to back open in either order
   * from run to run; which scope belongs under which parent is what matters, not their order.
   */
  private static List<String> sortSiblings(List<String> lines) {
    var out = new ArrayList<String>();
    var run = new ArrayList<List<String>>();
    int i = 0;
    while (i < lines.size()) {
      if (lines.get(i).equals(CHILD_OPEN)) {
        int depth = 0;
        int j = i;
        do {
          depth += count(lines.get(j), "<div") - count(lines.get(j), "</div>");
          j++;
        } while (depth > 0 && j < lines.size());
        var block = new ArrayList<String>();
        block.add(lines.get(i));
        block.addAll(sortSiblings(lines.subList(i + 1, j - 1)));
        block.add(lines.get(j - 1));
        run.add(block);
        i = j;
      } else {
        flush(run, out);
        out.add(lines.get(i));
        i++;
      }
    }
    flush(run, out);
    return out;
  }

  private static void flush(List<List<String>> run, List<String> out) {
    run.stream()
        .sorted(Comparator.comparing(block -> String.join("\n", block)))
        .forEach(out::addAll);
    run.clear();
  }

  private static int count(String line, String token) {
    int count = 0;
    for (int at = line.indexOf(token); at >= 0; at = line.indexOf(token, at + token.length())) {
      count++;
    }
    return count;
  }

  /** The first line where the skeletons of two reports differ, for a failure message; or null. */
  static String firstDifference(String committed, String regenerated) {
    return firstDifference(committed, regenerated, false);
  }

  static String firstDifference(String committed, String regenerated, boolean noteMayMove) {
    var a = of(committed, noteMayMove).split("\n");
    var b = of(regenerated, noteMayMove).split("\n");
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      if (!a[i].equals(b[i])) {
        return "line "
            + (i + 1)
            + ": committed `"
            + abbreviate(a[i])
            + "`, regenerated `"
            + abbreviate(b[i])
            + "`";
      }
    }
    if (a.length != b.length) {
      return "committed has " + a.length + " lines, regenerated has " + b.length;
    }
    return null;
  }

  private static String abbreviate(String s) {
    return s.length() <= 120 ? s : s.substring(0, 117) + "...";
  }
}
