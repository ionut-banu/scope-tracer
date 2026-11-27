package com.ionutbanu.scopetracer.plugin.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A click on a task in the embedded Timeline (#190): the {@code data-scope-id} and {@code
 * data-task-id} the report writes on each task bar and row, as the page sends them back, {@code
 * "<scopeId>:<taskId>"}. Plain Java, so it is tested without a browser.
 *
 * <p>The page is the report itself ({@link ReportNavigation} refuses every other navigation), but
 * what it sends is still treated as untrusted: only two non-negative {@code long}s in ASCII digits
 * are accepted, and the message is never evaluated, opened or used as a path.
 *
 * <pre>{@code
 * cefBrowser.executeJavaScript(TaskClick.script(query.inject("msg")), url, 0);
 * query.addHandler(message -> {
 *   TaskClick.parse(message).ifPresent(click -> ...);
 *   return null;
 * });
 * }</pre>
 *
 * @param scopeId the task's scope, {@code PluginScopeRecord.scopeId}
 * @param taskId the task, {@code PluginTaskRecord.taskId}
 */
public record TaskClick(long scopeId, long taskId) {

  /** Two numbers of at most 19 digits each, the most a non-negative {@code long} has. */
  private static final int MAX_LENGTH = 19 + 1 + 19;

  private static final Pattern MESSAGE = Pattern.compile("([0-9]{1,19}):([0-9]{1,19})");

  /** The ids in {@code message}, or empty for anything but {@code "<scopeId>:<taskId>"}. */
  public static Optional<TaskClick> parse(String message) {
    if (message == null || message.length() > MAX_LENGTH) {
      return Optional.empty();
    }
    var matcher = MESSAGE.matcher(message);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new TaskClick(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))));
    } catch (NumberFormatException e) {
      return Optional.empty(); // beyond Long.MAX_VALUE
    }
  }

  /**
   * The script that makes a single click on a task bar or row call {@code bridgeCall}, a statement
   * that sends the JavaScript string variable {@code msg} to the IDE (a {@code JBCefJSQuery}'s
   * {@code inject("msg")}). It is run on the loaded page, never written into the report file, and
   * is installed once per page however often it runs. A click inside a link, a button, a form
   * control, a group's summary or a failed task's stack trace, a click that ends a text selection,
   * and a click on anything without both ids (the scope bar, the axis, the overview) are left to
   * the page.
   */
  public static String script(String bridgeCall) {
    return """
    (function() {
      if (window.__scopeTracerTaskClick) return;
      window.__scopeTracerTaskClick = true;
      document.addEventListener('click', function(e) {
        if (e.button !== 0 || !(e.target instanceof Element)) return;
        var own = 'a, button, input, label, select, textarea, summary, details.stack-trace';
        if (e.target.closest(own)) return;
        var selection = window.getSelection();
        if (selection && !selection.isCollapsed) return;
        var el = e.target.closest('[data-scope-id][data-task-id]');
        if (!el) return;
        var msg = el.getAttribute('data-scope-id') + ':' + el.getAttribute('data-task-id');
        %s
      });
    })();
    """
        .formatted(bridgeCall);
  }
}
