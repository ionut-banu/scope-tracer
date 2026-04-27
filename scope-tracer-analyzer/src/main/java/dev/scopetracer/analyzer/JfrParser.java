package dev.scopetracer.analyzer;

import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TaskRecord;
import dev.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordingFile;

/**
 * Parses a {@code .jfr} recording produced by {@code scope-tracer-core} into a {@link TraceModel}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * TraceModel model = JfrParser.parse(Path.of("recording.jfr"));
 * }</pre>
 */
public final class JfrParser {

  private static final String SCOPE_OPENED = "dev.scopetracer.ScopeOpened";
  private static final String TASK_FORKED = "dev.scopetracer.TaskForked";
  private static final String TASK_SUCCEEDED = "dev.scopetracer.TaskSucceeded";
  private static final String TASK_FAILED = "dev.scopetracer.TaskFailed";
  private static final String TASK_CANCELLED = "dev.scopetracer.TaskCancelled";
  private static final String SCOPE_CLOSED = "dev.scopetracer.ScopeClosed";

  private JfrParser() {}

  /**
   * Parses all {@code dev.scopetracer.*} events from the given recording and reconstructs the
   * scope/task lifecycle model.
   *
   * <p>Tasks whose completion event is absent (truncated recordings) are included with {@code null}
   * {@code completionTime} and {@code outcome}. Scopes whose {@code ScopeClosed} event is absent
   * get a {@code null} {@code closeTime}.
   *
   * @param jfrFile path to the {@code .jfr} file; must be readable.
   * @return the complete trace model; scopes sorted by open time, tasks sorted by fork time.
   * @throws IOException if the file cannot be read or parsed.
   */
  public static TraceModel parse(Path jfrFile) throws IOException {
    var scopeOpens = new HashMap<String, Instant>();
    var scopeOwners = new HashMap<String, String>();
    var scopeCloses = new HashMap<String, Instant>();
    var forks = new HashMap<String, Map<Long, ForkData>>();
    var completions = new HashMap<String, Map<Long, CompletionData>>();

    try (var file = new RecordingFile(jfrFile)) {
      while (file.hasMoreEvents()) {
        var event = file.readEvent();
        var type = event.getEventType().getName();
        if (!type.startsWith("dev.scopetracer.")) continue;

        var scopeName = event.getString("scopeName");
        var taskId = event.getLong("taskId");
        var threadName = event.getString("threadName");
        var time = event.getStartTime();

        switch (type) {
          case SCOPE_OPENED -> {
            scopeOpens.put(scopeName, time);
            scopeOwners.put(scopeName, threadName);
          }
          case TASK_FORKED ->
              forks
                  .computeIfAbsent(scopeName, k -> new HashMap<>())
                  .put(taskId, new ForkData(time, threadName));
          case TASK_SUCCEEDED ->
              completions
                  .computeIfAbsent(scopeName, k -> new HashMap<>())
                  .put(taskId, new CompletionData(time, new TaskOutcome.Success()));
          case TASK_FAILED -> {
            var exType = event.getString("exceptionType");
            completions
                .computeIfAbsent(scopeName, k -> new HashMap<>())
                .put(taskId, new CompletionData(time, new TaskOutcome.Failed(exType)));
          }
          case TASK_CANCELLED ->
              completions
                  .computeIfAbsent(scopeName, k -> new HashMap<>())
                  .put(taskId, new CompletionData(time, new TaskOutcome.Cancelled()));
          case SCOPE_CLOSED -> scopeCloses.put(scopeName, time);
          default -> {
            // ignore unrecognised dev.scopetracer.* events for forward compatibility
          }
        }
      }
    }

    var scopes = new ArrayList<ScopeRecord>();
    for (var scopeName : scopeOpens.keySet()) {
      var scopeForks = forks.getOrDefault(scopeName, Map.of());
      var scopeCompletions = completions.getOrDefault(scopeName, Map.of());

      var tasks = new ArrayList<TaskRecord>();
      for (var entry : scopeForks.entrySet()) {
        var id = entry.getKey();
        var fork = entry.getValue();
        var completion = scopeCompletions.get(id);
        tasks.add(
            new TaskRecord(
                id,
                fork.threadName(),
                fork.forkTime(),
                completion != null ? completion.completionTime() : null,
                completion != null ? completion.outcome() : null));
      }
      tasks.sort(Comparator.comparing(TaskRecord::forkTime));

      scopes.add(
          new ScopeRecord(
              scopeName,
              scopeOwners.get(scopeName),
              scopeOpens.get(scopeName),
              scopeCloses.get(scopeName),
              List.copyOf(tasks)));
    }
    scopes.sort(Comparator.comparing(ScopeRecord::openTime));

    return new TraceModel(List.copyOf(scopes));
  }

  private record ForkData(Instant forkTime, String threadName) {}

  private record CompletionData(Instant completionTime, TaskOutcome outcome) {}
}
