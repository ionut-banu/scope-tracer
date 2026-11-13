package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.core.events.ScopeClosedEvent;
import com.ionutbanu.scopetracer.core.events.ScopeOpenedEvent;
import com.ionutbanu.scopetracer.core.events.TaskCancelledEvent;
import com.ionutbanu.scopetracer.core.events.TaskFailedEvent;
import com.ionutbanu.scopetracer.core.events.TaskForkedEvent;
import com.ionutbanu.scopetracer.core.events.TaskSucceededEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JfrParser} must not depend on the position of an event in the file (spec §2, "Ordering").
 * Each test commits the core events from plain threads in a chosen <em>time</em> order and arranges
 * for a different <em>file</em> order: events of a thread that exits early reach the file before
 * the events of a thread that exits later. The file order the JDK produced is read back and
 * asserted, so a JDK that flushes differently fails the test instead of silently testing nothing.
 */
class JfrParserEventOrderTest {

  @TempDir Path tempDir;

  // --- the events, in the order the file must hold them ---

  @Test
  void forkBeforeOpenKeepsSucceededFailedAndCancelledTasks() throws Exception {
    var rec =
        record(
            "fork-first",
            order(
                "TaskForked:1:1",
                "TaskForked:1:2",
                "TaskForked:1:3",
                "TaskSucceeded:1:1",
                "TaskFailed:1:2",
                "TaskCancelled:1:3",
                "ScopeOpened:1:0",
                "ScopeClosed:1:0"),
            step(LATE, () -> opened(1, "s")),
            step("w", () -> forked(1, "s", 1)),
            step("w", () -> forked(1, "s", 2)),
            step("w", () -> forked(1, "s", 3)),
            step("w", () -> succeeded(1, "s", 1)),
            step("w", () -> failed(1, "s", 2)),
            step("w", () -> cancelled(1, "s", 3)),
            step(LATE, () -> closed(1, "s")));

    var scope = onlyScope(rec.model());
    assertThat(scope.closeTime()).isNotNull();
    assertThat(scope.tasks()).extracting(TaskRecord::taskId).containsExactly(1L, 2L, 3L);
    assertThat(scope.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
    assertThat(scope.tasks().get(1).outcome()).isInstanceOf(TaskOutcome.Failed.class);
    assertThat(scope.tasks().get(2).outcome()).isInstanceOf(TaskOutcome.Cancelled.class);
  }

  @Test
  void succeededBeforeOpenKeepsTheTask() throws Exception {
    var rec =
        record(
            "succeeded-first",
            startsWith("TaskSucceeded:1:1"),
            step(LATE, () -> opened(1, "s")),
            step(LATE, () -> forked(1, "s", 1)),
            step("w", () -> succeeded(1, "s", 1)),
            step(LATE, () -> closed(1, "s")));

    var task = onlyScope(rec.model()).tasks().get(0);
    assertThat(task.outcome()).isInstanceOf(TaskOutcome.Success.class);
  }

  @Test
  void failedBeforeOpenKeepsTheTask() throws Exception {
    var rec =
        record(
            "failed-first",
            startsWith("TaskFailed:1:1"),
            step(LATE, () -> opened(1, "s")),
            step(LATE, () -> forked(1, "s", 1)),
            step("w", () -> failed(1, "s", 1)),
            step(LATE, () -> closed(1, "s")));

    var task = onlyScope(rec.model()).tasks().get(0);
    assertThat(task.outcome()).isInstanceOf(TaskOutcome.Failed.class);
  }

  @Test
  void cancelledBeforeOpenKeepsTheTask() throws Exception {
    var rec =
        record(
            "cancelled-first",
            startsWith("TaskCancelled:1:1"),
            step(LATE, () -> opened(1, "s")),
            step(LATE, () -> forked(1, "s", 1)),
            step("w", () -> cancelled(1, "s", 1)),
            step(LATE, () -> closed(1, "s")));

    var task = onlyScope(rec.model()).tasks().get(0);
    assertThat(task.outcome()).isInstanceOf(TaskOutcome.Cancelled.class);
  }

  @Test
  void closedBeforeOpenGivesTheScopeItsCloseTime() throws Exception {
    var rec =
        record(
            "closed-first",
            startsWith("ScopeClosed:1:0"),
            step(LATE, () -> opened(1, "s")),
            step(LATE, () -> forked(1, "s", 1)),
            step(LATE, () -> succeeded(1, "s", 1)),
            step("w", () -> closed(1, "s")));

    var scope = onlyScope(rec.model());
    assertThat(scope.closeTime()).isNotNull();
    assertThat(scope.tasks()).hasSize(1);
  }

  @Test
  void sameNamedScopesKeepTheirOwnTasks() throws Exception {
    var rec =
        record(
            "same-name",
            startsWith("TaskForked:1:1", "TaskForked:2:1", "TaskForked:2:2"),
            step(LATE, () -> opened(1, "twin")),
            step(LATE, () -> opened(2, "twin")),
            step("w", () -> forked(1, "twin", 1)),
            step("w", () -> forked(2, "twin", 1)),
            step("w", () -> forked(2, "twin", 2)),
            step("w", () -> succeeded(1, "twin", 1)),
            step("w", () -> succeeded(2, "twin", 1)),
            step("w", () -> succeeded(2, "twin", 2)),
            step(LATE, () -> closed(1, "twin")),
            step(LATE, () -> closed(2, "twin")));

    var byId = new HashMap<Long, ScopeRecord>();
    rec.model().scopes().forEach(s -> byId.put(s.scopeId(), s));
    assertThat(byId.get(1L).tasks()).extracting(TaskRecord::taskId).containsExactly(1L);
    assertThat(byId.get(2L).tasks()).extracting(TaskRecord::taskId).containsExactly(1L, 2L);
    assertThat(byId.get(1L).parent()).isNull();
    assertThat(byId.get(2L).parent()).isNull();
  }

  /**
   * A task forked in A runs on thread T1, which opens B; a task forked in B runs on T2, which opens
   * C. Every fork and completion reaches the file before the owner's {@code ScopeOpened}, so C and
   * B find their parent only if the forks of B and A were kept.
   */
  @Test
  void scopeNestedTwoDeepKeepsItsParents() throws Exception {
    var rec =
        record(
            "nested-two-deep",
            order ->
                order.indexOf("TaskForked:1:1") < order.indexOf("ScopeOpened:1:0")
                    && order.indexOf("TaskForked:2:1") < order.indexOf("ScopeOpened:2:0")
                    && order.indexOf("TaskSucceeded:1:1") < order.indexOf("ScopeOpened:1:0"),
            step(LATE, () -> opened(1, "A")),
            step("wa", () -> forked(1, "A", 1)),
            step("t1", () -> opened(2, "B")),
            step("wb", () -> forked(2, "B", 1)),
            step("t2", () -> opened(3, "C")),
            step("t2", () -> closed(3, "C")),
            step("t2", () -> succeeded(2, "B", 1)),
            step("t1", () -> closed(2, "B")),
            step("t1", () -> succeeded(1, "A", 1)),
            step(LATE, () -> closed(1, "A")));

    var byId = new HashMap<Long, ScopeRecord>();
    rec.model().scopes().forEach(s -> byId.put(s.scopeId(), s));
    assertThat(byId.get(1L).tasks()).hasSize(1);
    assertThat(byId.get(2L).tasks()).hasSize(1);
    assertThat(byId.get(1L).parent()).isNull();
    assertThat(byId.get(2L).parent()).isNotNull();
    assertThat(byId.get(2L).parent().parentScopeId()).isEqualTo(1L);
    assertThat(byId.get(2L).parent().taskId()).isEqualTo(1L);
    assertThat(byId.get(3L).parent()).isNotNull();
    assertThat(byId.get(3L).parent().parentScopeId()).isEqualTo(2L);
    assertThat(byId.get(3L).parent().taskId()).isEqualTo(1L);
  }

  @Test
  void scopeWithoutOpenedEventIsLeftOut() throws Exception {
    var rec =
        record(
            "no-open",
            order -> true,
            step("w", () -> forked(1, "orphan", 1)),
            step("w", () -> succeeded(1, "orphan", 1)),
            step("w", () -> closed(1, "orphan")),
            step("w", () -> opened(2, "whole")),
            step("w", () -> forked(2, "whole", 1)),
            step("w", () -> succeeded(2, "whole", 1)),
            step("w", () -> closed(2, "whole")));

    assertThat(rec.model().scopes()).extracting(ScopeRecord::scopeId).containsExactly(2L);
    assertThat(rec.model().scopes().get(0).tasks()).hasSize(1);
  }

  // --- a recording made by a released version ---

  /**
   * {@code recordings/v0.2.0-nested-and-failed.jfr} was made by tag v0.2.0 ({@code TracedScope}
   * under a {@code Recording}). Its {@code TaskForked} events have no {@code callSite*} fields.
   */
  @Test
  void recordingMadeBy020Parses() throws Exception {
    var model = JfrParser.parse(resource("recordings/v0.2.0-nested-and-failed.jfr"));

    assertThat(model.scopes())
        .extracting(ScopeRecord::name)
        .containsExactly("legacy-outer", "legacy-inner", "legacy-failing");
    var outer = model.scopes().get(0);
    var inner = model.scopes().get(1);
    var failing = model.scopes().get(2);

    assertThat(outer.parent()).isNull();
    assertThat(outer.closeTime()).isNotNull();
    assertThat(outer.tasks()).hasSize(1);
    assertThat(outer.tasks().get(0).taskName()).isEqualTo("inner-runner");
    assertThat(outer.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
    assertThat(outer.tasks().get(0).callSite()).isNull();

    assertThat(inner.parent()).isNotNull();
    assertThat(inner.parent().parentScopeId()).isEqualTo(outer.scopeId());
    assertThat(inner.parent().taskId()).isEqualTo(1L);
    assertThat(inner.tasks()).hasSize(1);
    assertThat(inner.tasks().get(0).taskName()).isEqualTo("ok");
    assertThat(inner.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);
    assertThat(inner.tasks().get(0).callSite()).isNull();

    assertThat(failing.parent()).isNull();
    assertThat(failing.tasks()).hasSize(1);
    assertThat(failing.tasks().get(0).taskName()).isEqualTo("boom");
    var failed = failing.tasks().get(0).outcome();
    assertThat(failed).isInstanceOf(TaskOutcome.Failed.class);
    assertThat(((TaskOutcome.Failed) failed).exceptionType())
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(failing.tasks().get(0).callSite()).isNull();
  }

  /**
   * {@code recordings/v0.1.0-nested-failed-cancelled.jfr} was made by tag v0.1.0, built and run on
   * JDK 26 ({@code TracedScope} under a {@code Recording}): a nested pair of scopes, and a scope
   * whose failing task cancels its blocked sibling. 0.1.0 had no {@code callSite*} fields.
   */
  @Test
  void recordingMadeBy010Parses() throws Exception {
    var model = JfrParser.parse(resource("recordings/v0.1.0-nested-failed-cancelled.jfr"));

    assertThat(model.scopes())
        .extracting(ScopeRecord::name)
        .containsExactly("v010-outer", "v010-inner", "v010-failing");
    var outer = model.scopes().get(0);
    var inner = model.scopes().get(1);
    var failing = model.scopes().get(2);

    assertThat(model.scopes()).allSatisfy(s -> assertThat(s.closeTime()).isNotNull());
    assertThat(model.scopes().stream().flatMap(s -> s.tasks().stream()))
        .hasSize(4)
        .allSatisfy(t -> assertThat(t.callSite()).isNull());

    assertThat(outer.parent()).isNull();
    assertThat(outer.tasks()).extracting(t -> t.taskName()).containsExactly("inner-runner");
    assertThat(outer.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);

    assertThat(inner.parent()).isNotNull();
    assertThat(inner.parent().parentScopeId()).isEqualTo(outer.scopeId());
    assertThat(inner.parent().taskId()).isEqualTo(1L);
    assertThat(inner.tasks()).extracting(t -> t.taskName()).containsExactly("ok");
    assertThat(inner.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Success.class);

    assertThat(failing.parent()).isNull();
    assertThat(failing.tasks()).extracting(t -> t.taskName()).containsExactly("slow", "boom");
    assertThat(failing.tasks().get(0).outcome()).isInstanceOf(TaskOutcome.Cancelled.class);
    var failed = failing.tasks().get(1).outcome();
    assertThat(failed).isInstanceOf(TaskOutcome.Failed.class);
    assertThat(((TaskOutcome.Failed) failed).exceptionType())
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(((TaskOutcome.Failed) failed).exceptionMessage()).isEqualTo("v0.1.0 failure");
  }

  // --- harness ---

  private static final String LATE = "late";

  /**
   * JFR decides the file order, and about one recording in a hundred comes out differently from the
   * one a test sets up. The set-up is repeated until the order is the one the test is about; what
   * the parser then does with that order is asserted once.
   */
  private static final int ATTEMPTS = 20;

  private static Predicate<List<String>> startsWith(String... events) {
    return order ->
        order.size() >= events.length && order.subList(0, events.length).equals(List.of(events));
  }

  private static Predicate<List<String>> order(String... events) {
    return order -> order.equals(List.of(events));
  }

  private record Step(String thread, Runnable action) {}

  private record Recorded(TraceModel model, List<String> fileOrder) {}

  private static Step step(String thread, Runnable action) {
    return new Step(thread, action);
  }

  /**
   * Runs the steps one after the other in the given (time) order, each on the thread named in the
   * step. A thread exits right after its last step, which flushes its events to the recording; the
   * {@link #LATE} thread stays alive until every other step has run and exits last, so its events
   * reach the file after those of the threads that exited before it.
   */
  private Recorded record(String label, Predicate<List<String>> arranged, Step... steps)
      throws Exception {
    Recorded recorded = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      recorded = recordOnce(label + "-" + attempt, steps);
      if (arranged.test(recorded.fileOrder())) return recorded;
    }
    throw new AssertionError(
        "the JDK did not write the events in the file order this test needs in "
            + ATTEMPTS
            + " attempts; the last file order was "
            + recorded.fileOrder());
  }

  private Recorded recordOnce(String label, Step[] steps) throws Exception {
    var lastStep = new HashMap<String, Integer>();
    for (int i = 0; i < steps.length; i++) lastStep.put(steps[i].thread(), i);

    var threads = new HashMap<String, ExecutorService>();
    var jfr = tempDir.resolve(label + ".jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      try {
        for (int i = 0; i < steps.length; i++) {
          var name = steps[i].thread();
          var executor =
              threads.computeIfAbsent(
                  name, n -> Executors.newSingleThreadExecutor(r -> new Thread(r, n)));
          executor.submit(steps[i].action()).get();
          if (!name.equals(LATE) && lastStep.get(name) == i) exit(executor);
        }
        var late = threads.get(LATE);
        if (late != null) exit(late);
      } finally {
        recording.stop();
        recording.dump(jfr);
        threads.values().forEach(ExecutorService::shutdownNow);
      }
    }
    return new Recorded(JfrParser.parse(jfr), fileOrder(jfr));
  }

  private static void exit(ExecutorService executor) throws InterruptedException {
    executor.shutdown();
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }

  private static List<String> fileOrder(Path jfr) throws IOException {
    var order = new ArrayList<String>();
    try (var file = new RecordingFile(jfr)) {
      while (file.hasMoreEvents()) {
        var event = file.readEvent();
        var type = event.getEventType().getName();
        if (!type.startsWith("com.ionutbanu.scopetracer.")) continue;
        order.add(
            type.substring(type.lastIndexOf('.') + 1)
                + ":"
                + event.getLong("scopeId")
                + ":"
                + event.getLong("taskId"));
      }
    }
    return order;
  }

  private static ScopeRecord onlyScope(TraceModel model) {
    assertThat(model.scopes()).hasSize(1);
    return model.scopes().get(0);
  }

  private static Path resource(String name) throws URISyntaxException {
    return Path.of(JfrParserEventOrderTest.class.getClassLoader().getResource(name).toURI());
  }

  private static void opened(long scopeId, String name) {
    var e = new ScopeOpenedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = 0;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  private static void closed(long scopeId, String name) {
    var e = new ScopeClosedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = 0;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  private static void forked(long scopeId, String name, long taskId) {
    var e = new TaskForkedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  private static void succeeded(long scopeId, String name, long taskId) {
    var e = new TaskSucceededEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }

  private static void failed(long scopeId, String name, long taskId) {
    var e = new TaskFailedEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.threadName = Thread.currentThread().getName();
    e.exceptionType = "java.lang.IllegalStateException";
    e.exceptionMessage = "boom";
    e.exceptionStackTrace = "trace";
    e.commit();
  }

  private static void cancelled(long scopeId, String name, long taskId) {
    var e = new TaskCancelledEvent();
    e.scopeId = scopeId;
    e.scopeName = name;
    e.taskId = taskId;
    e.threadName = Thread.currentThread().getName();
    e.commit();
  }
}
