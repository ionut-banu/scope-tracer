package com.ionutbanu.scopetracer.plugin.model;

import java.util.HashMap;
import java.util.Map;

/**
 * The call sites of one recording's tasks, by {@code (scopeId, taskId)} (#190): the join between a
 * {@link TaskClick} from the embedded Timeline and the plugin's own parsed {@link
 * PluginTraceModel}. A task's call site is {@link CallSiteParser#forTask}'s, the same one the
 * "Tasks" table navigates to. Built per recording shown, so a click is always looked up in the
 * model of the tab it came from. Plain Java, so it is tested without a browser.
 *
 * <pre>{@code
 * switch (TaskCallSites.of(model).find(click)) {
 *   case Lookup.Found found -> TaskSourceNavigator.navigate(project, anchor, found.callSite());
 *   case Lookup.NoCallSite none -> notifyNoCallSite(none.taskId());
 *   case Lookup.Unknown unknown -> {}
 * }
 * }</pre>
 */
public final class TaskCallSites {

  /** No recording: every lookup is {@link Lookup.Unknown}. */
  public static final TaskCallSites EMPTY = new TaskCallSites(Map.of());

  /** What a {@code (scopeId, taskId)} leads to. */
  public sealed interface Lookup {

    /** The task, and where it was forked. */
    record Found(CallSite callSite) implements Lookup {}

    /**
     * The task exists, but its call site is neither recorded nor parseable from its name (an
     * explicit label such as {@code fetch-user}).
     */
    record NoCallSite(long taskId, String taskName) implements Lookup {}

    /** No such task in this recording: an unknown scope, or a task id of another scope. */
    enum Unknown implements Lookup {
      INSTANCE
    }
  }

  private final Map<Long, Map<Long, PluginTaskRecord>> tasksByScope;

  private TaskCallSites(Map<Long, Map<Long, PluginTaskRecord>> tasksByScope) {
    this.tasksByScope = tasksByScope;
  }

  /** The tasks of {@code model}, by scope id and task id. */
  public static TaskCallSites of(PluginTraceModel model) {
    var byScope = new HashMap<Long, Map<Long, PluginTaskRecord>>();
    for (var scope : model.scopes()) {
      var tasks = byScope.computeIfAbsent(scope.scopeId(), id -> new HashMap<>());
      for (var task : scope.tasks()) {
        tasks.putIfAbsent(task.taskId(), task);
      }
    }
    return new TaskCallSites(byScope);
  }

  /** Where {@code click}'s task was forked, if it is a task of this recording. */
  public Lookup find(TaskClick click) {
    var task = tasksByScope.getOrDefault(click.scopeId(), Map.of()).get(click.taskId());
    if (task == null) {
      return Lookup.Unknown.INSTANCE;
    }
    var callSite = CallSiteParser.forTask(task);
    return callSite != null
        ? new Lookup.Found(callSite)
        : new Lookup.NoCallSite(task.taskId(), task.taskName());
  }
}
