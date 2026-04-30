package dev.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** Emitted when a forked subtask returns successfully. */
@Name("dev.scopetracer.TaskSucceeded")
@Label("Task succeeded")
@Category({"scope-tracer"})
public final class TaskSucceededEvent extends Event implements TracedScopeEvent {

  @Label("Scope ID")
  public long scopeId;

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;
}
