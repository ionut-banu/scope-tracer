package dev.scopetracer.analyzer.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents one {@code TracedScope} lifetime as reconstructed from JFR events.
 *
 * @param name the scope name supplied at construction.
 * @param ownerThreadName thread that opened and closed the scope.
 * @param ownerThreadId JFR Java thread ID of the owner thread; {@code -1} if unknown.
 * @param openTime when {@code ScopeOpenedEvent} was emitted.
 * @param closeTime when {@code ScopeClosedEvent} was emitted; {@code null} for truncated
 *     recordings.
 * @param tasks subtasks in fork order; unmodifiable.
 * @param parent reference to the parent scope and task; {@code null} for root scopes.
 */
public record ScopeRecord(
    String name,
    String ownerThreadName,
    long ownerThreadId,
    Instant openTime,
    Instant closeTime,
    List<TaskRecord> tasks,
    ParentRef parent) {

  /**
   * Identifies the task inside a parent scope that directly contains this scope.
   *
   * @param scopeName name of the parent {@code TracedScope}.
   * @param taskId task ID within the parent scope that opened this scope.
   */
  public record ParentRef(String scopeName, long taskId) {}
}
