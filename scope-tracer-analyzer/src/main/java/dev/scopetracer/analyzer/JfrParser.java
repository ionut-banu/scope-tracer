package dev.scopetracer.analyzer;

import dev.scopetracer.analyzer.model.ScopeRecord;
import dev.scopetracer.analyzer.model.TaskOutcome;
import dev.scopetracer.analyzer.model.TaskRecord;
import dev.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

  private static final String SCOPE_OPENED = "dev.scopetracer.ScopeOpened";
  private static final String TASK_FORKED = "dev.scopetracer.TaskForked";
  private static final String TASK_SUCCEEDED = "dev.scopetracer.TaskSucceeded";
  private static final String TASK_FAILED = "dev.scopetracer.TaskFailed";
  private static final String TASK_CANCELLED = "dev.scopetracer.TaskCancelled";
  private static final String SCOPE_CLOSED = "dev.scopetracer.ScopeClosed";

  private JfrParser() {}

  /**
   * Parses all {@code dev.scopetracer.*} events from the given recording and reconstructs the
   * scope/task lifecycle model, including parent-child relationships between nested scopes.
   *
   * <p>Multiple scopes with the same name (e.g. repeated invocations of the same method) are
   * tracked independently using a composite {@code (name, openTime)} key, so no scope instance is
   * silently overwritten by a later one with the same name.
   *
   * <p>JFR flushes per-thread buffers independently, so task-completion events (emitted on virtual
   * task threads) can appear in the file before the main thread's {@code ScopeOpened} event for the
   * same scope. Completions that arrive early are held in a pending buffer and applied to the scope
   * key as soon as its {@code ScopeOpened} event is processed.
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
    // Composite key: (name, openTime) uniquely identifies a scope instance even when many scopes
    // share the same name (e.g. one scope per HTTP request, all named "checkout").
    //
    // activeScopes tracks the LIFO stack of currently-open scope keys per name so that task events
    // arriving before SCOPE_CLOSED are routed to the right instance. lastKeyByName is a fallback
    // for completion events that arrive slightly after SCOPE_CLOSED due to JFR thread-flush order.
    //
    // pendingCompletions buffers task-completion events whose SCOPE_OPENED event has not yet
    // been seen. JFR flushes virtual-thread buffers independently; a TASK_SUCCEEDED emitted on a
    // task thread can appear in the file before the main thread's SCOPE_OPENED event.
    //
    // pendingCloses buffers SCOPE_CLOSED events that arrive before SCOPE_OPENED. JFR chunk
    // rotation can place a thread's close event in an earlier file chunk than its open event —
    // empirically observed even when both events are on the same thread — because local thread
    // buffers are flushed in chunk-rotation order, not emission-time order.
    var activeScopes = new HashMap<String, Deque<ScopeKey>>();
    var lastKeyByName = new HashMap<String, ScopeKey>();
    var pendingCompletions = new HashMap<String, Map<Long, CompletionData>>();
    var pendingCloses = new HashMap<String, Deque<Instant>>();

    // LinkedHashMap preserves insertion (= open-time) order for deterministic iteration.
    var scopeOpens = new LinkedHashMap<ScopeKey, Instant>();
    var scopeOwners = new HashMap<ScopeKey, String>();
    var scopeOwnerThreadIds = new HashMap<ScopeKey, Long>();
    var scopeCloses = new HashMap<ScopeKey, Instant>();
    var forks = new HashMap<ScopeKey, Map<Long, ForkData>>();
    var completions = new HashMap<ScopeKey, Map<Long, CompletionData>>();

    try (var file = new RecordingFile(jfrFile)) {
      while (file.hasMoreEvents()) {
        var event = file.readEvent();
        var type = event.getEventType().getName();
        if (!type.startsWith("dev.scopetracer.")) continue;

        var scopeName = event.getString("scopeName");
        var taskId = event.getLong("taskId");
        var threadName = event.getString("threadName");
        var time = event.getStartTime();
        var thread = event.getThread();
        long javaThreadId = thread != null ? thread.getJavaThreadId() : -1L;

        switch (type) {
          case SCOPE_OPENED -> {
            var key = new ScopeKey(scopeName, time);
            scopeOpens.put(key, time);
            scopeOwners.put(key, threadName);
            scopeOwnerThreadIds.put(key, javaThreadId);
            activeScopes.computeIfAbsent(scopeName, k -> new ArrayDeque<>()).push(key);
            lastKeyByName.put(scopeName, key);
            // Flush any close/completion events that arrived before this SCOPE_OPENED.
            var pendingClose = pendingCloses.getOrDefault(scopeName, new ArrayDeque<>()).poll();
            if (pendingClose != null) {
              scopeCloses.put(key, pendingClose);
            }
            var pending = pendingCompletions.remove(scopeName);
            if (pending != null) {
              completions.put(key, new HashMap<>(pending));
            }
          }
          case TASK_FORKED -> {
            var key = resolveKey(activeScopes, lastKeyByName, scopeName);
            if (key != null) {
              forks
                  .computeIfAbsent(key, k -> new HashMap<>())
                  .put(taskId, new ForkData(time, threadName));
            }
          }
          case TASK_SUCCEEDED -> {
            storeCompletion(
                activeScopes,
                lastKeyByName,
                pendingCompletions,
                completions,
                scopeName,
                taskId,
                new CompletionData(time, new TaskOutcome.Success(), threadName, javaThreadId));
          }
          case TASK_FAILED -> {
            var exType = event.getString("exceptionType");
            storeCompletion(
                activeScopes,
                lastKeyByName,
                pendingCompletions,
                completions,
                scopeName,
                taskId,
                new CompletionData(time, new TaskOutcome.Failed(exType), threadName, javaThreadId));
          }
          case TASK_CANCELLED -> {
            storeCompletion(
                activeScopes,
                lastKeyByName,
                pendingCompletions,
                completions,
                scopeName,
                taskId,
                new CompletionData(time, new TaskOutcome.Cancelled(), threadName, javaThreadId));
          }
          case SCOPE_CLOSED -> {
            var stack = activeScopes.get(scopeName);
            if (stack != null && !stack.isEmpty()) {
              scopeCloses.put(stack.pop(), time);
            } else {
              // SCOPE_OPENED not yet seen (JFR chunk rotation placed this close event in an
              // earlier file position than the open event). Buffer in FIFO order so the first
              // pending close is matched to the first pending open for this name.
              pendingCloses.computeIfAbsent(scopeName, k -> new ArrayDeque<>()).add(time);
            }
          }
          default -> {
            // ignore unrecognised dev.scopetracer.* events for forward compatibility
          }
        }
      }
    }

    var parentRefs =
        detectNesting(scopeOpens, scopeCloses, scopeOwnerThreadIds, forks, completions);

    var scopes = new ArrayList<ScopeRecord>();
    for (var key : scopeOpens.keySet()) {
      var scopeForks = forks.getOrDefault(key, Map.of());
      var scopeCompletions = completions.getOrDefault(key, Map.of());

      var tasks = new ArrayList<TaskRecord>();
      for (var entry : scopeForks.entrySet()) {
        var id = entry.getKey();
        var fork = entry.getValue();
        var completion = scopeCompletions.get(id);
        tasks.add(
            new TaskRecord(
                id,
                completion != null ? completion.threadName() : fork.threadName(),
                completion != null ? completion.executingThreadId() : -1L,
                fork.forkTime(),
                completion != null ? completion.completionTime() : null,
                completion != null ? completion.outcome() : null));
      }
      tasks.sort(Comparator.comparing(TaskRecord::forkTime));

      scopes.add(
          new ScopeRecord(
              key.name(),
              scopeOwners.get(key),
              scopeOwnerThreadIds.getOrDefault(key, -1L),
              scopeOpens.get(key),
              scopeCloses.get(key),
              List.copyOf(tasks),
              parentRefs.get(key)));
    }
    scopes.sort(Comparator.comparing(ScopeRecord::openTime));

    return new TraceModel(List.copyOf(scopes));
  }

  /**
   * Stores a task-completion record under the currently active scope key. If no scope with the
   * given name has been opened yet (JFR flushed this virtual-thread event before the main thread's
   * {@code ScopeOpened} event), the record is placed in {@code pendingCompletions} and will be
   * applied when {@code SCOPE_OPENED} is eventually processed.
   */
  private static void storeCompletion(
      Map<String, Deque<ScopeKey>> activeScopes,
      Map<String, ScopeKey> lastKeyByName,
      Map<String, Map<Long, CompletionData>> pendingCompletions,
      Map<ScopeKey, Map<Long, CompletionData>> completions,
      String scopeName,
      long taskId,
      CompletionData data) {
    var key = resolveKey(activeScopes, lastKeyByName, scopeName);
    if (key != null) {
      completions.computeIfAbsent(key, k -> new HashMap<>()).put(taskId, data);
    } else {
      // Scope not yet opened — buffer until SCOPE_OPENED arrives.
      pendingCompletions.computeIfAbsent(scopeName, k -> new HashMap<>()).put(taskId, data);
    }
  }

  /**
   * Returns the scope key currently active for {@code scopeName}. Falls back to the most recently
   * opened key when the active stack is empty (handles task-completion events that arrive after
   * {@code SCOPE_CLOSED} due to JFR's per-thread flush ordering).
   */
  private static ScopeKey resolveKey(
      Map<String, Deque<ScopeKey>> activeScopes,
      Map<String, ScopeKey> lastKeyByName,
      String scopeName) {
    var stack = activeScopes.get(scopeName);
    if (stack != null && !stack.isEmpty()) return stack.peek();
    return lastKeyByName.get(scopeName);
  }

  private static Map<ScopeKey, ScopeRecord.ParentRef> detectNesting(
      Map<ScopeKey, Instant> scopeOpens,
      Map<ScopeKey, Instant> scopeCloses,
      Map<ScopeKey, Long> scopeOwnerThreadIds,
      Map<ScopeKey, Map<Long, ForkData>> forks,
      Map<ScopeKey, Map<Long, CompletionData>> completions) {

    var parentRefs = new HashMap<ScopeKey, ScopeRecord.ParentRef>();

    for (var scopeB : scopeOpens.keySet()) {
      long ownerThreadId = scopeOwnerThreadIds.getOrDefault(scopeB, -1L);
      if (ownerThreadId == -1L) continue;

      Instant bOpen = scopeOpens.get(scopeB);
      Instant bClose = scopeCloses.get(scopeB);

      outer:
      for (var scopeA : completions.keySet()) {
        if (scopeA.equals(scopeB)) continue;
        var aForks = forks.getOrDefault(scopeA, Map.of());
        for (var entry : completions.get(scopeA).entrySet()) {
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
            parentRefs.put(scopeB, new ScopeRecord.ParentRef(scopeA.name(), taskId));
            break outer;
          }
        }
      }
    }

    return parentRefs;
  }

  /** Composite key that uniquely identifies a scope instance even when names repeat. */
  private record ScopeKey(String name, Instant openTime) {}

  private record ForkData(Instant forkTime, String threadName) {}

  private record CompletionData(
      Instant completionTime, TaskOutcome outcome, String threadName, long executingThreadId) {}
}
