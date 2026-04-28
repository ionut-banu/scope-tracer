package dev.scopetracer.agent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-scope state tracked by the agent for a single {@code StructuredTaskScope} instance.
 *
 * @param name derived scope name (from call-site StackWalker or configured name).
 * @param taskCounter monotonically incrementing task ID generator; starts at 0.
 */
public record ScopeState(String name, AtomicLong taskCounter) {

  /** Returns the next task ID (1-based). */
  public long nextTaskId() {
    return taskCounter.incrementAndGet();
  }
}
