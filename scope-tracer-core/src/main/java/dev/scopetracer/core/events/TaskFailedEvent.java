package dev.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Emitted when a forked subtask terminates by throwing. {@code exceptionType} is the fully
 * qualified class name of the throwable that escaped the subtask.
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
}
