package com.ionutbanu.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Emitted once when a {@link com.ionutbanu.scopetracer.core.TracedScope} is closed and all of its
 * subtasks have terminated. Scope-level event: {@code taskId} is always {@code 0}.
 */
@Name("com.ionutbanu.scopetracer.ScopeClosed")
@Label("Scope closed")
@Category({"scope-tracer"})
public final class ScopeClosedEvent extends Event implements TracedScopeEvent {

  @Label("Scope ID")
  public long scopeId;

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;
}
