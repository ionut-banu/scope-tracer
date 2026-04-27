package dev.scopetracer.analyzer.model;

import java.time.Instant;

/**
 * Represents one forked subtask within a {@link ScopeRecord}.
 *
 * @param taskId per-scope monotonic id, starting at 1.
 * @param threadName name of the virtual/platform thread that ran this task.
 * @param forkTime when the task was submitted via {@code TracedScope.fork()}.
 * @param completionTime when the task terminated; {@code null} for truncated recordings.
 * @param outcome terminal outcome; {@code null} for truncated recordings.
 */
public record TaskRecord(
    long taskId,
    String threadName,
    Instant forkTime,
    Instant completionTime,
    TaskOutcome outcome) {}
