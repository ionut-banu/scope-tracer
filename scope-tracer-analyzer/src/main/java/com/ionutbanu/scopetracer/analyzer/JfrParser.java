package com.ionutbanu.scopetracer.analyzer;

import com.ionutbanu.scopetracer.analyzer.model.CallSite;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(JfrParser.class);

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
   * <p>Nothing here depends on the position of an event in the file. JFR flushes per-thread buffers
   * independently, so the events of one scope, even those of one thread, reach the file in no fixed
   * order: a {@code TaskForked}, a completion or a {@code ScopeClosed} can precede the scope's
   * {@code ScopeOpened}. Every event is therefore recorded under its {@code scopeId} as it is read,
   * and the model is assembled once the whole file has been read. Events of a scope whose {@code
   * ScopeOpened} is absent cannot be placed and are left out, as is a completion without a {@code
   * TaskForked}.
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
    // Events are stored as they are read, whatever their position in the file; only the scopes in
    // scopeOpens end up in the model.
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
          }
          case TASK_FORKED -> {
            // taskName was added later — older recordings won't have it. hasField is required.
            String taskName = event.hasField("taskName") ? event.getString("taskName") : null;
            CallSite callSite = readCallSite(event);
            forks
                .computeIfAbsent(scopeId, k -> new HashMap<>())
                .put(taskId, new ForkData(time, threadName, taskName, callSite));
          }
          case TASK_SUCCEEDED ->
              completions
                  .computeIfAbsent(scopeId, k -> new HashMap<>())
                  .put(
                      taskId,
                      new CompletionData(
                          time, new TaskOutcome.Success(), threadName, javaThreadId));
          case TASK_FAILED -> {
            var exType = event.getString("exceptionType");
            var exMessage = event.getString("exceptionMessage");
            var stackTrace =
                event.hasField("exceptionStackTrace")
                    ? event.getString("exceptionStackTrace")
                    : null;
            completions
                .computeIfAbsent(scopeId, k -> new HashMap<>())
                .put(
                    taskId,
                    new CompletionData(
                        time,
                        new TaskOutcome.Failed(exType, exMessage, stackTrace),
                        threadName,
                        javaThreadId));
          }
          case TASK_CANCELLED ->
              completions
                  .computeIfAbsent(scopeId, k -> new HashMap<>())
                  .put(
                      taskId,
                      new CompletionData(
                          time, new TaskOutcome.Cancelled(), threadName, javaThreadId));
          case SCOPE_CLOSED -> scopeCloses.put(scopeId, time);
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
                completion != null ? completion.outcome() : null,
                fork.callSite()));
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

    long truncatedScopes = scopes.stream().filter(s -> s.closeTime() == null).count();
    long truncatedTasks =
        scopes.stream()
            .flatMap(s -> s.tasks().stream())
            .filter(t -> t.completionTime() == null || t.outcome() == null)
            .count();
    if (truncatedScopes > 0 || truncatedTasks > 0) {
      log.warn(
          "recording {} contains {} truncated scope(s) and {} truncated task(s) — "
              + "the .jfr file may have been cut short",
          jfrFile,
          truncatedScopes,
          truncatedTasks);
    }

    return new TraceModel(List.copyOf(scopes));
  }

  /**
   * Reads the {@code callSite*} fields from a {@code TASK_FORKED} event into a {@link CallSite}, or
   * {@code null} when no class name was derived at emission time or the recording predates these
   * fields.
   */
  private static CallSite readCallSite(jdk.jfr.consumer.RecordedEvent event) {
    if (!event.hasField("callSiteClassName")) return null;
    String className = event.getString("callSiteClassName");
    if (className == null) return null;
    String methodName =
        event.hasField("callSiteMethodName") ? event.getString("callSiteMethodName") : null;
    Integer line = null;
    if (event.hasField("callSiteLine")) {
      int rawLine = event.getInt("callSiteLine");
      if (rawLine > 0) line = rawLine;
    }
    return new CallSite(className, methodName, line);
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
        if (scopeAId.equals(scopeBId) || !scopeOpens.containsKey(scopeAId)) continue;
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

  private record ForkData(
      Instant forkTime, String threadName, String taskName, CallSite callSite) {}

  private record CompletionData(
      Instant completionTime, TaskOutcome outcome, String threadName, long executingThreadId) {}
}
