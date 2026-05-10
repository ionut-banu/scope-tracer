package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TracingCallable}. Verifies that the correct JFR event is emitted for each
 * task-completion path (success, failure, cancellation via {@link InterruptedException}) and that
 * the event fields carry the values supplied at construction time.
 *
 * <p>These tests do not require the agent to be attached — {@link TracingCallable} is a plain
 * {@link java.util.concurrent.Callable} wrapper that emits JFR events directly.
 */
class TracingCallableTest {

  @TempDir Path tempDir;

  private static final long SCOPE_ID = 42L;
  private static final String SCOPE_NAME = "test-scope";
  private static final long TASK_ID = 7L;

  // --- success path ---

  /**
   * When the delegate returns normally, {@code TracingCallable} must emit exactly one {@code
   * TaskSucceededEvent} with the correct {@code scopeId}, {@code scopeName}, and {@code taskId}.
   */
  @Test
  void successEmitsTaskSucceededEventWithCorrectFields() throws Exception {
    var jfr = tempDir.resolve("success.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      var callable = new TracingCallable<>(() -> "result", SCOPE_NAME, SCOPE_ID, TASK_ID);
      var result = callable.call();
      assertThat(result).isEqualTo("result");

      recording.stop();
      recording.dump(jfr);
    }

    var events = readEvents(jfr);
    var succeeded = eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded");
    assertThat(succeeded).hasSize(1);
    assertThat(succeeded.get(0).getLong("scopeId")).isEqualTo(SCOPE_ID);
    assertThat(succeeded.get(0).getString("scopeName")).isEqualTo(SCOPE_NAME);
    assertThat(succeeded.get(0).getLong("taskId")).isEqualTo(TASK_ID);
    // No failure or cancellation events must be present.
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed")).isEmpty();
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).isEmpty();
  }

  // --- failure path ---

  /**
   * When the delegate throws a non-{@link InterruptedException} exception, {@code TracingCallable}
   * must emit exactly one {@code TaskFailedEvent} with {@code exceptionType} set to the FQN and
   * {@code exceptionMessage} set to {@link Throwable#getMessage()}.
   */
  @Test
  void failureEmitsTaskFailedEventWithTypeAndMessage() throws Exception {
    var jfr = tempDir.resolve("failed.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      var callable =
          new TracingCallable<Object>(
              () -> {
                throw new IllegalStateException("bad state");
              },
              SCOPE_NAME,
              SCOPE_ID,
              TASK_ID);
      assertThatThrownBy(callable::call).isInstanceOf(IllegalStateException.class);

      recording.stop();
      recording.dump(jfr);
    }

    var events = readEvents(jfr);
    var failed = eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed");
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).getLong("scopeId")).isEqualTo(SCOPE_ID);
    assertThat(failed.get(0).getString("scopeName")).isEqualTo(SCOPE_NAME);
    assertThat(failed.get(0).getLong("taskId")).isEqualTo(TASK_ID);
    assertThat(failed.get(0).getString("exceptionType"))
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(failed.get(0).getString("exceptionMessage")).isEqualTo("bad state");
    assertThat(failed.get(0).getString("exceptionStackTrace")).contains("IllegalStateException");
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded")).isEmpty();
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled")).isEmpty();
  }

  /**
   * Stack traces longer than 4 096 characters must be truncated: the field must end with {@code
   * "... (truncated)"} and its total length must not exceed 4 096 + the suffix length.
   */
  @Test
  void failureStackTraceIsTruncatedAtCharacterLimit() throws Exception {
    var jfr = tempDir.resolve("truncated-stack.jfr");

    // Build an exception with 500 synthetic frames — each "at a.b.C.m(C.java:1)" adds ~20 chars.
    var ex = new IllegalStateException("big-stack");
    var frames = new StackTraceElement[500];
    for (int i = 0; i < frames.length; i++) {
      frames[i] = new StackTraceElement("com.example.Cls" + i, "method", "Cls.java", i + 1);
    }
    ex.setStackTrace(frames);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      var callable =
          new TracingCallable<Object>(
              () -> {
                throw ex;
              },
              SCOPE_NAME,
              SCOPE_ID,
              TASK_ID);
      assertThatThrownBy(callable::call).isInstanceOf(IllegalStateException.class);

      recording.stop();
      recording.dump(jfr);
    }

    var failed = eventsOfType(readEvents(jfr), "com.ionutbanu.scopetracer.TaskFailed");
    assertThat(failed).hasSize(1);
    var stackTrace = failed.get(0).getString("exceptionStackTrace");
    assertThat(stackTrace).contains("(truncated)");
    // 4096 cap + length of "\n... (truncated)" (16 chars)
    assertThat(stackTrace.length()).isLessThanOrEqualTo(4096 + 16);
  }

  /**
   * When the delegate throws with a {@code null} message, {@code TaskFailedEvent.exceptionMessage}
   * must be {@code null} — not an empty string or a default value.
   */
  @Test
  void failureWithNullMessageEmitsNullExceptionMessage() throws Exception {
    var jfr = tempDir.resolve("failed-null-message.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      var callable =
          new TracingCallable<Object>(
              () -> {
                throw new RuntimeException(); // getMessage() returns null
              },
              SCOPE_NAME,
              SCOPE_ID,
              TASK_ID);
      assertThatThrownBy(callable::call).isInstanceOf(RuntimeException.class);

      recording.stop();
      recording.dump(jfr);
    }

    var failed = eventsOfType(readEvents(jfr), "com.ionutbanu.scopetracer.TaskFailed");
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).getString("exceptionMessage")).isNull();
  }

  // --- cancellation path ---

  /**
   * When the delegate throws {@link InterruptedException}, {@code TracingCallable} must:
   *
   * <ol>
   *   <li>Emit exactly one {@code TaskCancelledEvent}.
   *   <li>Re-set the interrupt flag on the current thread before re-throwing.
   * </ol>
   */
  @Test
  void cancellationEmitsTaskCancelledAndRestoresInterruptFlag() throws Exception {
    var jfr = tempDir.resolve("cancelled.jfr");
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      var callable =
          new TracingCallable<Object>(
              () -> {
                throw new InterruptedException("scope shutdown");
              },
              SCOPE_NAME,
              SCOPE_ID,
              TASK_ID);
      assertThatThrownBy(callable::call).isInstanceOf(InterruptedException.class);
      // TracingCallable must restore the interrupt flag before re-throwing.
      assertThat(Thread.interrupted()).isTrue(); // reads and clears the flag

      recording.stop();
      recording.dump(jfr);
    }

    var events = readEvents(jfr);
    var cancelled = eventsOfType(events, "com.ionutbanu.scopetracer.TaskCancelled");
    assertThat(cancelled).hasSize(1);
    assertThat(cancelled.get(0).getLong("scopeId")).isEqualTo(SCOPE_ID);
    assertThat(cancelled.get(0).getString("scopeName")).isEqualTo(SCOPE_NAME);
    assertThat(cancelled.get(0).getLong("taskId")).isEqualTo(TASK_ID);
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskSucceeded")).isEmpty();
    assertThat(eventsOfType(events, "com.ionutbanu.scopetracer.TaskFailed")).isEmpty();
  }

  // --- helpers ---

  private static List<RecordedEvent> readEvents(Path path) throws IOException {
    var events = new ArrayList<RecordedEvent>();
    try (var file = new RecordingFile(path)) {
      while (file.hasMoreEvents()) {
        events.add(file.readEvent());
      }
    }
    return events;
  }

  private static List<RecordedEvent> eventsOfType(List<RecordedEvent> events, String typeName) {
    return events.stream().filter(e -> e.getEventType().getName().equals(typeName)).toList();
  }
}
