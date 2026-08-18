package com.ionutbanu.scopetracer.core.events;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** Emitted when a subtask is forked into a {@link com.ionutbanu.scopetracer.core.TracedScope}. */
@Name("com.ionutbanu.scopetracer.TaskForked")
@Label("Task forked")
@Category({"scope-tracer"})
public final class TaskForkedEvent extends Event implements TracedScopeEvent {

  @Label("Scope ID")
  public long scopeId;

  @Label("Scope name")
  public String scopeName;

  @Label("Task ID")
  public long taskId;

  @Label("Thread name")
  public String threadName;

  /**
   * Human-readable label for the forked task (e.g. {@code "findUser"} or {@code
   * "OrderService#checkout"}).
   *
   * <p>Nullable: callers that cannot derive a label (or recordings produced before this field was
   * added) leave this {@code null}. Parsers must use {@code event.hasField("taskName")} before
   * reading.
   */
  @Label("Task name")
  public String taskName;

  /**
   * Simple class name of the fork call site: the enclosing class for a lambda/method reference, or
   * the {@link java.util.concurrent.Callable}'s own class when it's a named user class. Always
   * derived independently of {@link #taskName} — populated even when the caller supplied an
   * explicit label via {@code TracedScope.fork(String, Callable)}.
   *
   * <p>Nullable: absent when derivation fails, or in recordings produced before this field was
   * added. Parsers must use {@code event.hasField("callSiteClassName")} before reading.
   */
  @Label("Call site class")
  public String callSiteClassName;

  /**
   * Enclosing method name of the fork call site. Present only when the label was derived from a
   * caller stack frame (lambda/method reference); {@code null} for a named {@link
   * java.util.concurrent.Callable} class, which has no single enclosing method.
   *
   * <p>Nullable; absent in recordings produced before this field was added — use {@code
   * event.hasField("callSiteMethodName")} before reading.
   */
  @Label("Call site method")
  public String callSiteMethodName;

  /**
   * Source line of the fork call site. {@code 0} when unknown (e.g. the named-{@code Callable}-
   * class tier, which has no single line) or in recordings produced before this field was added —
   * use {@code event.hasField("callSiteLine")} before reading.
   */
  @Label("Call site line")
  public int callSiteLine;
}
