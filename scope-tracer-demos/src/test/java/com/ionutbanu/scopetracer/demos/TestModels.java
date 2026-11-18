package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Hand-built models for the tests of the gate, the skeleton and the command (no recording). */
final class TestModels {

  private TestModels() {}

  static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

  /** A task that ended {@code endMs} after {@code T0 + shiftMs}; null outcome means incomplete. */
  static TaskRecord task(
      long id, String name, long threadId, long shiftMs, long endMs, TaskOutcome outcome) {
    return new TaskRecord(
        id,
        name,
        "virtual-" + threadId,
        threadId,
        T0.plusMillis(shiftMs + 1),
        outcome == null ? null : T0.plusMillis(shiftMs + endMs),
        outcome);
  }

  static TaskOutcome ok() {
    return new TaskOutcome.Success();
  }

  static TaskOutcome failure(String message) {
    return new TaskOutcome.Failed("java.lang.IllegalStateException", message, "stack");
  }

  static TaskOutcome cancelled() {
    return new TaskOutcome.Cancelled();
  }

  static ScopeRecord scope(
      long id,
      String name,
      long shiftMs,
      long closeMs,
      ScopeRecord.ParentRef parent,
      TaskRecord... tasks) {
    return new ScopeRecord(
        id,
        name,
        "main",
        1,
        T0.plusMillis(shiftMs),
        closeMs < 0 ? null : T0.plusMillis(shiftMs + closeMs),
        List.of(tasks),
        parent);
  }

  static TraceModel model(ScopeRecord... scopes) {
    return new TraceModel(new ArrayList<>(List.of(scopes)));
  }

  /** The model of a scope "s" with a succeeded task "a" and a failed task "b". */
  static TraceModel simple(long shiftMs, long threadBase, long durationMs) {
    return model(
        scope(
            1,
            "s",
            shiftMs,
            durationMs,
            null,
            task(1, "a", threadBase, shiftMs, durationMs / 2, ok()),
            task(2, "b", threadBase + 1, shiftMs, durationMs, failure("boom"))));
  }
}
