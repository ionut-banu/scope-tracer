package dev.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** Emitted when a subtask is forked into a {@link dev.scopetracer.core.TracedScope}. */
@Name("dev.scopetracer.TaskForked")
@Label("Task forked")
@Category({"scope-tracer"})
public final class TaskForkedEvent extends Event implements TracedScopeEvent {

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;
}
