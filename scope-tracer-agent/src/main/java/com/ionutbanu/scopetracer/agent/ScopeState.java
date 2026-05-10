package com.ionutbanu.scopetracer.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-scope state tracked by the agent for a single {@code StructuredTaskScope} instance.
 *
 * @param name derived scope name (from call-site StackWalker or configured name).
 * @param scopeId unique monotonic ID assigned when the scope is opened; stamped on every JFR event
 *     emitted by this scope so the parser can key all events by ID rather than by name.
 * @param taskCounter monotonically incrementing task ID generator; starts at 0.
 */
public record ScopeState(String name, long scopeId, AtomicLong taskCounter) {

  /** Returns the next task ID (1-based). */
  public long nextTaskId() {
    return taskCounter.incrementAndGet();
  }
}
