package dev.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Emitted when a forked subtask observes scope shutdown (e.g. a sibling failed) before completing
 * on its own.
 */
@Name("dev.scopetracer.TaskCancelled")
@Label("Task cancelled")
@Category({"scope-tracer"})
public final class TaskCancelledEvent extends Event implements TracedScopeEvent {

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;
}
