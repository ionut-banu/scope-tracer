package com.ionutbanu.scopetracer.analyzer.model;

import java.time.Instant;

/**
 * Represents one forked subtask within a {@link ScopeRecord}.
 *
 * @param taskId per-scope monotonic id, starting at 1.
 * @param taskName human-readable label for this task (e.g. {@code "findUser"} or {@code
 *     "OrderService#checkout"}); {@code null} when no label was emitted (older recordings or when
 *     auto-derivation failed).
 * @param threadName name of the virtual/platform thread that ran this task; blank for unnamed
 *     virtual threads.
 * @param threadId JFR Java thread ID of the thread that executed this task; {@code -1} if unknown.
 * @param forkTime when the task was submitted via {@code TracedScope.fork()}.
 * @param completionTime when the task terminated; {@code null} for truncated recordings.
 * @param outcome terminal outcome; {@code null} for truncated recordings.
 */
public record TaskRecord(
    long taskId,
    String taskName,
    String threadName,
    long threadId,
    Instant forkTime,
    Instant completionTime,
    TaskOutcome outcome) {}
