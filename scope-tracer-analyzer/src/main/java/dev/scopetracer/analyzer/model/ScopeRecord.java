package dev.scopetracer.analyzer.model;

import java.time.Instant;
import java.util.List;

/**
 * Represents one {@code TracedScope} lifetime as reconstructed from JFR events.
 *
 * @param name the scope name supplied at construction.
 * @param ownerThreadName thread that opened and closed the scope.
 * @param openTime when {@code ScopeOpenedEvent} was emitted.
 * @param closeTime when {@code ScopeClosedEvent} was emitted; {@code null} for truncated
 *     recordings.
 * @param tasks subtasks in fork order; unmodifiable.
 */
public record ScopeRecord(
    String name,
    String ownerThreadName,
    Instant openTime,
    Instant closeTime,
    List<TaskRecord> tasks) {}
