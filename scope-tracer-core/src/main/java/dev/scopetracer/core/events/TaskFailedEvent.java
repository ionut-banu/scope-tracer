package dev.scopetracer.core.events;

import java.io.PrintWriter;
import java.io.StringWriter;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Emitted when a forked subtask terminates by throwing. {@code exceptionType} is the fully
 * qualified class name of the throwable; {@code exceptionMessage} is the value of {@link
 * Throwable#getMessage()}, which may be {@code null} when the exception carries no message.
 */
@Name("dev.scopetracer.TaskFailed")
@Label("Task failed")
@Category({"scope-tracer"})
public final class TaskFailedEvent extends Event implements TracedScopeEvent {

  @Label("Scope ID")
  public long scopeId;

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;

  @Label("Exception type")
  public String exceptionType;

  @Label("Exception message")
  public String exceptionMessage;

  @Label("Stack trace")
  public String exceptionStackTrace;

  /**
   * Formats {@code e}'s stack trace as a string, capped at 4 096 characters. Returns {@code null}
   * on any internal error so that JFR emission is never interrupted by formatting failures.
   */
  public static String formatStackTrace(Exception e) {
    try {
      var sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      var trace = sw.toString();
      return trace.length() <= 4096 ? trace : trace.substring(0, 4096) + "\n... (truncated)";
    } catch (Exception ignored) {
      return null;
    }
  }
}
