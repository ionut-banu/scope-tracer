package dev.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Emitted once when a {@link dev.scopetracer.core.TracedScope} is opened. Scope-level event: {@code
 * taskId} is always {@code 0}.
 */
@Name("dev.scopetracer.ScopeOpened")
@Label("Scope opened")
@Category({"scope-tracer"})
public final class ScopeOpenedEvent extends Event implements TracedScopeEvent {

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;
}
