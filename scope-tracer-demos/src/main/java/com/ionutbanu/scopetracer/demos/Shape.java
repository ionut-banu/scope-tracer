package com.ionutbanu.scopetracer.demos;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a gallery scenario's report must show: its scopes, their parents, and every task with its
 * outcome. {@link #problems(TraceModel)} is the gate that decides whether a recording is published;
 * {@link #structureProblems(List)} is the same comparison for a report that is already on disk (see
 * {@link ReportRows}).
 *
 * <p>The structure is exact: a scope or a task that is not listed is a problem, and so is a listed
 * one that is missing. {@code timing} holds the checks that need the model's timestamps, such as
 * "the cancelled tasks end within 50 ms of the failed one"; they run on a live recording only.
 */
record Shape(List<ExpectedScope> scopes, List<TimingCheck> timing) {

  /** A scope the report must contain; {@code parent} is null for a root scope. */
  record ExpectedScope(String name, String parent, String parentTask, List<ExpectedTask> tasks) {}

  /**
   * A task the report must contain. {@code outcome} is {@code success}, {@code failed} or {@code
   * cancelled}; {@code detail} is a text the failure must contain (exception type and message), or
   * null.
   */
  record ExpectedTask(String name, String outcome, String detail) {}

  /** A scope as a recording or a report shows it, to be compared with an {@link ExpectedScope}. */
  record ObservedScope(String name, String parent, String parentTask, List<ObservedTask> tasks) {}

  /** A task as a recording or a report shows it; {@code outcome} may be {@code incomplete}. */
  record ObservedTask(String name, String outcome, String detail) {}

  /** A check on the timestamps of a recording: returns what is wrong, empty when nothing is. */
  @FunctionalInterface
  interface TimingCheck {
    List<String> problems(TraceModel model);
  }

  static ExpectedScope scope(String name, ExpectedTask... tasks) {
    return new ExpectedScope(name, null, null, List.of(tasks));
  }

  static ExpectedScope nested(
      String name, String parent, String parentTask, ExpectedTask... tasks) {
    return new ExpectedScope(name, parent, parentTask, List.of(tasks));
  }

  static ExpectedTask succeeded(String name) {
    return new ExpectedTask(name, "success", null);
  }

  static ExpectedTask failed(String name, String detail) {
    return new ExpectedTask(name, "failed", detail);
  }

  static ExpectedTask cancelled(String name) {
    return new ExpectedTask(name, "cancelled", null);
  }

  Shape(List<ExpectedScope> scopes, List<TimingCheck> timing) {
    this.scopes = List.copyOf(scopes);
    this.timing = List.copyOf(timing);
  }

  /** A shape with structure only. */
  static Shape of(ExpectedScope... scopes) {
    return new Shape(List.of(scopes), List.of());
  }

  Shape with(TimingCheck... checks) {
    var all = new ArrayList<>(timing);
    all.addAll(List.of(checks));
    return new Shape(scopes, all);
  }

  /**
   * Everything wrong with a recording: a scope or task that is not closed or completed, a structure
   * that differs from this shape, and every timing check that fails. Empty means the recording
   * shows the behaviour the scenario was written to show.
   */
  List<String> problems(TraceModel model) {
    var problems = new ArrayList<String>();
    for (var scope : model.scopes()) {
      if (scope.closeTime() == null) {
        problems.add("scope " + scope.name() + " has no close event");
      }
      for (var task : scope.tasks()) {
        if (task.completionTime() == null || task.outcome() == null) {
          problems.add(
              "task " + task.taskName() + " of scope " + scope.name() + " has no completion event");
        }
      }
    }
    problems.addAll(structureProblems(observe(model)));
    if (problems.isEmpty()) {
      timing.forEach(check -> problems.addAll(check.problems(model)));
    }
    return problems;
  }

  /** The differences between this shape and what a recording or a report shows. */
  List<String> structureProblems(List<ObservedScope> observed) {
    var problems = new ArrayList<String>();
    var byName = new HashMap<String, ObservedScope>();
    for (var scope : observed) {
      if (byName.put(scope.name(), scope) != null) {
        problems.add("scope " + scope.name() + " appears twice");
      }
    }
    for (var expected : scopes) {
      var actual = byName.remove(expected.name());
      if (actual == null) {
        problems.add("scope " + expected.name() + " is missing");
        continue;
      }
      compare(expected, actual, problems);
    }
    byName.keySet().stream().sorted().forEach(name -> problems.add("unexpected scope " + name));
    return problems;
  }

  private static void compare(ExpectedScope expected, ObservedScope actual, List<String> problems) {
    var where = "scope " + expected.name() + ": ";
    if (!java.util.Objects.equals(expected.parent(), actual.parent())) {
      problems.add(where + "parent is " + actual.parent() + ", expected " + expected.parent());
    } else if (expected.parent() != null
        && !java.util.Objects.equals(expected.parentTask(), actual.parentTask())) {
      problems.add(
          where + "opened by task " + actual.parentTask() + ", expected " + expected.parentTask());
    }
    var tasks = new HashMap<String, ObservedTask>();
    for (var task : actual.tasks()) {
      if (tasks.put(task.name(), task) != null) {
        problems.add(where + "task " + task.name() + " appears twice");
      }
    }
    for (var task : expected.tasks()) {
      var seen = tasks.remove(task.name());
      if (seen == null) {
        problems.add(where + "task " + task.name() + " is missing");
      } else if (!seen.outcome().equals(task.outcome())) {
        problems.add(
            where
                + "task "
                + task.name()
                + " is "
                + seen.outcome()
                + ", expected "
                + task.outcome());
      } else if (task.detail() != null
          && (seen.detail() == null || !seen.detail().contains(task.detail()))) {
        problems.add(
            where
                + "task "
                + task.name()
                + " failed with "
                + seen.detail()
                + ", expected it to contain "
                + task.detail());
      }
    }
    tasks.keySet().stream()
        .sorted()
        .forEach(name -> problems.add(where + "unexpected task " + name));
  }

  /** Reads the structure of a parsed recording. */
  static List<ObservedScope> observe(TraceModel model) {
    var byId = new HashMap<Long, ScopeRecord>();
    model.scopes().forEach(s -> byId.put(s.scopeId(), s));
    var observed = new ArrayList<ObservedScope>();
    for (var scope : model.scopes()) {
      String parent = null;
      String parentTask = null;
      if (scope.parent() != null) {
        parent = scope.parent().scopeName();
        var parentScope = byId.get(scope.parent().parentScopeId());
        if (parentScope != null) {
          parentTask =
              parentScope.tasks().stream()
                  .filter(t -> t.taskId() == scope.parent().taskId())
                  .map(TaskRecord::taskName)
                  .findFirst()
                  .orElse(null);
        }
      }
      var tasks = new ArrayList<ObservedTask>();
      for (var task : scope.tasks()) {
        tasks.add(observe(task));
      }
      observed.add(new ObservedScope(scope.name(), parent, parentTask, tasks));
    }
    return observed;
  }

  private static ObservedTask observe(TaskRecord task) {
    var outcome = task.outcome();
    if (outcome instanceof TaskOutcome.Success) {
      return new ObservedTask(task.taskName(), "success", null);
    }
    if (outcome instanceof TaskOutcome.Failed failed) {
      return new ObservedTask(
          task.taskName(), "failed", failed.exceptionType() + ": " + failed.exceptionMessage());
    }
    if (outcome instanceof TaskOutcome.Cancelled) {
      return new ObservedTask(task.taskName(), "cancelled", null);
    }
    return new ObservedTask(task.taskName(), "incomplete", null);
  }

  /** One line per scope, for the message of a failed gate or check. */
  static String describe(List<ObservedScope> scopes) {
    return scopes.stream()
        .sorted(Comparator.comparing(ObservedScope::name))
        .map(
            s ->
                s.name()
                    + (s.parent() == null ? "" : " (in " + s.parent() + "/" + s.parentTask() + ")")
                    + " ["
                    + s.tasks().stream()
                        .map(t -> t.name() + "=" + t.outcome())
                        .collect(Collectors.joining(", "))
                    + "]")
        .collect(Collectors.joining("; "));
  }

  /** What this shape expects, in the same format as {@link #describe(List)}. */
  String describeExpected() {
    var asObserved = new ArrayList<ObservedScope>();
    for (var scope : scopes) {
      var tasks = new ArrayList<ObservedTask>();
      scope.tasks().forEach(t -> tasks.add(new ObservedTask(t.name(), t.outcome(), t.detail())));
      asObserved.add(new ObservedScope(scope.name(), scope.parent(), scope.parentTask(), tasks));
    }
    return describe(asObserved);
  }

  /** Index of the scopes of a model by name; used by timing checks. */
  static Map<String, ScopeRecord> byName(TraceModel model) {
    var map = new HashMap<String, ScopeRecord>();
    model.scopes().forEach(s -> map.put(s.name(), s));
    return map;
  }
}
