package com.ionutbanu.scopetracer.analyzer;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  private static final String SCOPE_OPENED = "com.ionutbanu.scopetracer.ScopeOpened";
  private static final String TASK_FORKED = "com.ionutbanu.scopetracer.TaskForked";
  private static final String TASK_SUCCEEDED = "com.ionutbanu.scopetracer.TaskSucceeded";
  private static final String TASK_FAILED = "com.ionutbanu.scopetracer.TaskFailed";
  private static final String TASK_CANCELLED = "com.ionutbanu.scopetracer.TaskCancelled";
  private static final String SCOPE_CLOSED = "com.ionutbanu.scopetracer.ScopeClosed";

  private JfrParser() {}

  /**
   * Parses all {@code com.ionutbanu.scopetracer.*} events from the given recording and reconstructs
   * the scope/task lifecycle model, including parent-child relationships between nested scopes.
   *
   * <p>Every event carries a {@code scopeId} long that uniquely identifies the scope instance
   * across the recording. All internal maps are keyed by {@code scopeId}, which means two
   * concurrently-open scopes that share the same name (common when no {@code withName()} is
   * configured on the agent) can never interfere with each other.
   *
   * <p>JFR flushes per-thread buffers independently, so task-completion events (emitted on virtual
   * task threads) can appear in the file before the main thread's {@code ScopeOpened} event for the
   * same scope. Similarly, {@code ScopeClosed} can precede {@code ScopeOpened} for the same scope
   * due to JFR chunk-rotation ordering (empirically observed even when both events are on the same
   * thread). Both cases are handled by pending buffers keyed by {@code scopeId}: events that arrive
   * early are held and flushed when {@code ScopeOpened} is eventually processed.
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
    // All maps are keyed by the scopeId long stamped on every JFR event. This uniquely identifies
    // each scope instance, eliminating any name-based collision between concurrent scopes.
    //
    // pendingCompletions buffers task-completion events whose SCOPE_OPENED event has not yet been
    // seen. JFR flushes virtual-thread buffers independently; a TASK_SUCCEEDED emitted on a task
    // thread can appear in the file before the main thread's SCOPE_OPENED event.
    //
    // pendingCloses buffers SCOPE_CLOSED events that arrive before SCOPE_OPENED. JFR chunk
    // rotation can place a thread's close event in an earlier file position than its open event —
    // empirically observed even when both events are on the same thread — because local thread
    // buffers are flushed in chunk-rotation order, not emission-time order.
    var pendingCompletions = new HashMap<Long, Map<Long, CompletionData>>();
    var pendingCloses = new HashMap<Long, Instant>();

    // LinkedHashMap preserves insertion (= open-time) order for deterministic iteration.
    var scopeOpens = new LinkedHashMap<Long, Instant>();
    var scopeNames = new HashMap<Long, String>();
    var scopeOwners = new HashMap<Long, String>();
    var scopeOwnerThreadIds = new HashMap<Long, Long>();
    var scopeCloses = new HashMap<Long, Instant>();
    var forks = new HashMap<Long, Map<Long, ForkData>>();
    var completions = new HashMap<Long, Map<Long, CompletionData>>();

    try (var file = new RecordingFile(jfrFile)) {
      while (file.hasMoreEvents()) {
        var event = file.readEvent();
        var type = event.getEventType().getName();
        if (!type.startsWith("com.ionutbanu.scopetracer.")) continue;

        long scopeId = event.getLong("scopeId");
        var scopeName = event.getString("scopeName");
        var taskId = event.getLong("taskId");
        var threadName = event.getString("threadName");
        var time = event.getStartTime();
        var thread = event.getThread();
        long javaThreadId = thread != null ? thread.getJavaThreadId() : -1L;

        switch (type) {
          case SCOPE_OPENED -> {
            scopeOpens.put(scopeId, time);
            scopeNames.put(scopeId, scopeName);
            scopeOwners.put(scopeId, threadName);
            scopeOwnerThreadIds.put(scopeId, javaThreadId);
            // Flush any close/completion events that arrived before this SCOPE_OPENED.
            var pendingClose = pendingCloses.remove(scopeId);
            if (pendingClose != null) scopeCloses.put(scopeId, pendingClose);
            var pending = pendingCompletions.remove(scopeId);
            if (pending != null) completions.put(scopeId, pending);
          }
          case TASK_FORKED -> {
            if (scopeOpens.containsKey(scopeId)) {
              // taskName was added later — older recordings won't have it. hasField is required.
              String taskName = event.hasField("taskName") ? event.getString("taskName") : null;
              forks
                  .computeIfAbsent(scopeId, k -> new HashMap<>())
                  .put(taskId, new ForkData(time, threadName, taskName));
            }
            // else: fork before open should not occur in practice; silently drop.
          }
          case TASK_SUCCEEDED ->
              storeCompletion(
                  scopeOpens,
                  pendingCompletions,
                  completions,
                  scopeId,
                  taskId,
                  new CompletionData(time, new TaskOutcome.Success(), threadName, javaThreadId));
          case TASK_FAILED -> {
            var exType = event.getString("exceptionType");
            var exMessage = event.getString("exceptionMessage");
            var stackTrace =
                event.hasField("exceptionStackTrace")
                    ? event.getString("exceptionStackTrace")
                    : null;
            storeCompletion(
                scopeOpens,
                pendingCompletions,
                completions,
                scopeId,
                taskId,
                new CompletionData(
                    time,
                    new TaskOutcome.Failed(exType, exMessage, stackTrace),
                    threadName,
                    javaThreadId));
          }
          case TASK_CANCELLED ->
              storeCompletion(
                  scopeOpens,
                  pendingCompletions,
                  completions,
                  scopeId,
                  taskId,
                  new CompletionData(time, new TaskOutcome.Cancelled(), threadName, javaThreadId));
          case SCOPE_CLOSED -> {
            if (scopeOpens.containsKey(scopeId)) {
              scopeCloses.put(scopeId, time);
            } else {
              // SCOPE_OPENED not yet seen (JFR chunk rotation placed this close event in an
              // earlier file position than the open event). Only one close per scope, so a plain
              // put is correct; the first close wins if duplicates somehow appear.
              pendingCloses.put(scopeId, time);
            }
          }
          default -> {
            // ignore unrecognised com.ionutbanu.scopetracer.* events for forward compatibility
          }
        }
      }
    }

    var parentRefs =
        detectNesting(scopeOpens, scopeCloses, scopeOwnerThreadIds, forks, completions, scopeNames);

    var scopes = new ArrayList<ScopeRecord>();
    for (var entry : scopeOpens.entrySet()) {
      long scopeId = entry.getKey();
      var scopeForks = forks.getOrDefault(scopeId, Map.of());
      var scopeCompletions = completions.getOrDefault(scopeId, Map.of());

      var tasks = new ArrayList<TaskRecord>();
      for (var forkEntry : scopeForks.entrySet()) {
        var id = forkEntry.getKey();
        var fork = forkEntry.getValue();
        var completion = scopeCompletions.get(id);
        tasks.add(
            new TaskRecord(
                id,
                fork.taskName(),
                completion != null ? completion.threadName() : fork.threadName(),
                completion != null ? completion.executingThreadId() : -1L,
                fork.forkTime(),
                completion != null ? completion.completionTime() : null,
                completion != null ? completion.outcome() : null));
      }
      tasks.sort(Comparator.comparing(TaskRecord::forkTime));

      scopes.add(
          new ScopeRecord(
              scopeId,
              scopeNames.get(scopeId),
              scopeOwners.get(scopeId),
              scopeOwnerThreadIds.getOrDefault(scopeId, -1L),
              entry.getValue(),
              scopeCloses.get(scopeId),
              List.copyOf(tasks),
              parentRefs.get(scopeId)));
    }
    scopes.sort(Comparator.comparing(ScopeRecord::openTime));

    return new TraceModel(List.copyOf(scopes));
  }

  /**
   * Stores a task-completion record under the scope identified by {@code scopeId}. If the scope's
   * {@code SCOPE_OPENED} event has not yet been processed, the record is buffered in {@code
   * pendingCompletions} and applied when {@code SCOPE_OPENED} is eventually seen.
   */
  private static void storeCompletion(
      Map<Long, Instant> scopeOpens,
      Map<Long, Map<Long, CompletionData>> pendingCompletions,
      Map<Long, Map<Long, CompletionData>> completions,
      long scopeId,
      long taskId,
      CompletionData data) {
    if (scopeOpens.containsKey(scopeId)) {
      completions.computeIfAbsent(scopeId, k -> new HashMap<>()).put(taskId, data);
    } else {
      // Scope not yet opened — buffer until SCOPE_OPENED arrives.
      pendingCompletions.computeIfAbsent(scopeId, k -> new HashMap<>()).put(taskId, data);
    }
  }

  /**
   * Detects parent-child relationships between scopes. Scope B is a child of task T in scope A
   * when: (1) B's owner thread ID matches the executing thread ID of T's completion event, and (2)
   * B's {@code [openTime, closeTime]} interval is contained within T's {@code [forkTime,
   * completionTime]}.
   */
  private static Map<Long, ScopeRecord.ParentRef> detectNesting(
      Map<Long, Instant> scopeOpens,
      Map<Long, Instant> scopeCloses,
      Map<Long, Long> scopeOwnerThreadIds,
      Map<Long, Map<Long, ForkData>> forks,
      Map<Long, Map<Long, CompletionData>> completions,
      Map<Long, String> scopeNames) {

    var parentRefs = new HashMap<Long, ScopeRecord.ParentRef>();

    for (var scopeBId : scopeOpens.keySet()) {
      long ownerThreadId = scopeOwnerThreadIds.getOrDefault(scopeBId, -1L);
      if (ownerThreadId == -1L) continue;

      Instant bOpen = scopeOpens.get(scopeBId);
      Instant bClose = scopeCloses.get(scopeBId);

      outer:
      for (var scopeAId : completions.keySet()) {
        if (scopeAId.equals(scopeBId)) continue;
        var aForks = forks.getOrDefault(scopeAId, Map.of());
        for (var entry : completions.get(scopeAId).entrySet()) {
          long taskId = entry.getKey();
          var completion = entry.getValue();
          if (completion.executingThreadId() != ownerThreadId) continue;

          var forkData = aForks.get(taskId);
          if (forkData == null) continue;

          Instant tFork = forkData.forkTime();
          Instant tDone = completion.completionTime();

          boolean openedAfterFork = bOpen != null && bOpen.compareTo(tFork) >= 0;
          boolean closedBeforeCompletion =
              bClose == null || tDone == null || bClose.compareTo(tDone) <= 0;

          if (openedAfterFork && closedBeforeCompletion) {
            String parentName = scopeNames.getOrDefault(scopeAId, "<unknown>");
            parentRefs.put(scopeBId, new ScopeRecord.ParentRef(scopeAId, parentName, taskId));
            break outer;
          }
        }
      }
    }

    return parentRefs;
  }

  private record ForkData(Instant forkTime, String threadName, String taskName) {}

  private record CompletionData(
      Instant completionTime, TaskOutcome outcome, String threadName, long executingThreadId) {}
}
