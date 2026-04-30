package dev.scopetracer.agent.advice;

import dev.scopetracer.agent.AgentState;
import dev.scopetracer.agent.ScopeState;
import dev.scopetracer.agent.TracingCallable;
import dev.scopetracer.core.events.TaskForkedEvent;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to {@code StructuredTaskScope.fork(Callable)} instance method.
 * Runs before the instrumented method body and:
 *
 * <ol>
 *   <li>Assigns the next task ID from the scope's {@link
 *       dev.scopetracer.agent.ScopeState#taskCounter()}.
 *   <li>Emits a {@code TaskForkedEvent} JFR event on the calling (owner) thread.
 *   <li>Replaces the user-supplied {@link Callable} argument with a {@link TracingCallable} that
 *       will emit the task-completion event ({@code TaskSucceeded}, {@code TaskFailed}, or {@code
 *       TaskCancelled}) when the task finishes on its virtual thread.
 * </ol>
 *
 * <p>If the scope is not tracked (e.g. the scope was created before the agent was loaded), the
 * original callable is left unchanged and no events are emitted.
 */
public final class ForkAdvice {

  private ForkAdvice() {}

  /**
   * Advice enter handler — runs before the instrumented {@code fork()} method body. Replaces the
   * {@code task} argument with a {@link TracingCallable} wrapper when the scope is tracked.
   *
   * @param scope the {@code StructuredTaskScope} instance ({@code this}).
   * @param task the user-supplied callable; reassigned to a {@link TracingCallable} if the scope is
   *     tracked.
   */
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onFork(
      @Advice.This Object scope, @Advice.Argument(value = 0, readOnly = false) Callable task) {
    ScopeState state = AgentState.SCOPE_STATES.get(scope);
    if (state == null) return;

    long taskId = state.nextTaskId();

    TaskForkedEvent event = new TaskForkedEvent();
    event.scopeId = state.scopeId();
    event.scopeName = state.name();
    event.taskId = taskId;
    event.threadName = Thread.currentThread().getName();
    event.commit();

    task = new TracingCallable<>(task, state.name(), state.scopeId(), taskId);
  }
}
