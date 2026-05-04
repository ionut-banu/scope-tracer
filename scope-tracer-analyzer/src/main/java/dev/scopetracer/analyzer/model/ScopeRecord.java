package dev.scopetracer.analyzer.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents one {@code TracedScope} lifetime as reconstructed from JFR events.
 *
 * @param scopeId globally unique monotonic ID; mirrors the {@code scopeId} stamped on every JFR
 *     event by this scope. Used as the primary key for parent-child lookup — it is unambiguous even
 *     when multiple scopes share the same name.
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
    long scopeId,
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
   * @param parentScopeId {@code scopeId} of the parent {@code TracedScope}. Used as the lookup key
   *     in the renderer; unambiguous even when multiple scopes share the same name.
   * @param scopeName display name of the parent scope (used for breadcrumb rendering only).
   * @param taskId task ID within the parent scope that opened this scope.
   */
  public record ParentRef(long parentScopeId, String scopeName, long taskId) {}
}
