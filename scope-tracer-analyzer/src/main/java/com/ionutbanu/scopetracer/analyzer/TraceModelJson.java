package com.ionutbanu.scopetracer.analyzer;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.time.Instant;

/**
 * Serialises a {@link TraceModel} to a JSON string. Hand-rolled to avoid pulling in a JSON library;
 * the schema is small and stable enough that the cost is justified.
 *
 * <p>The output is consumed by the in-browser "Export JSON" button embedded in the HTML report (see
 * {@link HtmlRenderer}). External tools can also read this directly when produced by the CLI via a
 * future {@code --format=json} option.
 *
 * <h2>Schema</h2>
 *
 * <pre>{@code
 * {
 *   "scopes": [
 *     {
 *       "scopeId": 1, "name": "...", "ownerThreadName": "...", "ownerThreadId": 42,
 *       "openTime": "2026-01-01T00:00:00Z", "closeTime": "...",
 *       "parent": { "parentScopeId": 0, "scopeName": "...", "taskId": 7 } | null,
 *       "tasks": [
 *         {
 *           "taskId": 1, "taskName": "...", "threadName": "...", "threadId": 17,
 *           "forkTime": "...", "completionTime": "..." | null,
 *           "outcome": { "type": "success" | "failed" | "cancelled" | null,
 *                         "exceptionType": "...", "exceptionMessage": "...",
 *                         "stackTrace": "..." }
 *         }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class TraceModelJson {

  private TraceModelJson() {}

  /**
   * Serialises {@code model} to a JSON string. The result is safe to embed in a {@code <script>}.
   */
  public static String toJson(TraceModel model) {
    var sb = new StringBuilder();
    sb.append("{\"scopes\":[");
    boolean firstScope = true;
    for (var scope : model.scopes()) {
      if (!firstScope) sb.append(',');
      firstScope = false;
      writeScope(sb, scope);
    }
    sb.append("]}");
    return sb.toString();
  }

  private static void writeScope(StringBuilder sb, ScopeRecord scope) {
    sb.append('{');
    field(sb, "scopeId", scope.scopeId());
    sb.append(',');
    field(sb, "name", scope.name());
    sb.append(',');
    field(sb, "ownerThreadName", scope.ownerThreadName());
    sb.append(',');
    field(sb, "ownerThreadId", scope.ownerThreadId());
    sb.append(',');
    fieldInstant(sb, "openTime", scope.openTime());
    sb.append(',');
    fieldInstant(sb, "closeTime", scope.closeTime());
    sb.append(',');
    sb.append("\"parent\":");
    if (scope.parent() == null) {
      sb.append("null");
    } else {
      sb.append('{');
      field(sb, "parentScopeId", scope.parent().parentScopeId());
      sb.append(',');
      field(sb, "scopeName", scope.parent().scopeName());
      sb.append(',');
      field(sb, "taskId", scope.parent().taskId());
      sb.append('}');
    }
    sb.append(",\"tasks\":[");
    boolean firstTask = true;
    for (var task : scope.tasks()) {
      if (!firstTask) sb.append(',');
      firstTask = false;
      writeTask(sb, task);
    }
    sb.append("]}");
  }

  private static void writeTask(StringBuilder sb, TaskRecord task) {
    sb.append('{');
    field(sb, "taskId", task.taskId());
    sb.append(',');
    field(sb, "taskName", task.taskName());
    sb.append(',');
    field(sb, "threadName", task.threadName());
    sb.append(',');
    field(sb, "threadId", task.threadId());
    sb.append(',');
    fieldInstant(sb, "forkTime", task.forkTime());
    sb.append(',');
    fieldInstant(sb, "completionTime", task.completionTime());
    sb.append(',');
    sb.append("\"outcome\":");
    writeOutcome(sb, task.outcome());
    sb.append('}');
  }

  private static void writeOutcome(StringBuilder sb, TaskOutcome outcome) {
    if (outcome == null) {
      sb.append("null");
      return;
    }
    switch (outcome) {
      case TaskOutcome.Success s -> sb.append("{\"type\":\"success\"}");
      case TaskOutcome.Failed f -> {
        sb.append("{\"type\":\"failed\",");
        field(sb, "exceptionType", f.exceptionType());
        sb.append(',');
        field(sb, "exceptionMessage", f.exceptionMessage());
        sb.append(',');
        field(sb, "stackTrace", f.stackTrace());
        sb.append('}');
      }
      case TaskOutcome.Cancelled c -> sb.append("{\"type\":\"cancelled\"}");
    }
  }

  private static void field(StringBuilder sb, String key, String value) {
    sb.append('"').append(key).append("\":");
    if (value == null) {
      sb.append("null");
    } else {
      sb.append('"').append(escape(value)).append('"');
    }
  }

  private static void field(StringBuilder sb, String key, long value) {
    sb.append('"').append(key).append("\":").append(value);
  }

  private static void fieldInstant(StringBuilder sb, String key, Instant value) {
    sb.append('"').append(key).append("\":");
    if (value == null) sb.append("null");
    else sb.append('"').append(value.toString()).append('"');
  }

  private static String escape(String s) {
    var out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        // Escape forward slash to keep "</script>" from terminating an embedding script tag.
        case '/' -> out.append("\\/");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
