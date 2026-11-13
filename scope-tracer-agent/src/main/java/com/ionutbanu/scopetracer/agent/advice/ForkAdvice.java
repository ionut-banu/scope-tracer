package com.ionutbanu.scopetracer.agent.advice;

import com.ionutbanu.scopetracer.agent.AgentState;
import com.ionutbanu.scopetracer.agent.ScopeState;
import com.ionutbanu.scopetracer.agent.TracingCallable;
import com.ionutbanu.scopetracer.agent.util.TaskNameDeriver;
import com.ionutbanu.scopetracer.core.events.TaskForkedEvent;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to {@code StructuredTaskScope.fork(Callable)} instance method.
 * Runs before the instrumented method body and:
 *
 * <ol>
 *   <li>Assigns the next task ID from the scope's {@link
 *       com.ionutbanu.scopetracer.agent.ScopeState#taskCounter()}.
 *   <li>Emits a {@code TaskForkedEvent} JFR event on the calling (owner) thread, with the task
 *       label and the {@code callSite*} fields derived by {@link TaskNameDeriver} from one stack
 *       walk, by the same rule {@code TracedScope} uses. When no call site can be derived the label
 *       and the three fields stay {@code null} / {@code 0} and the event is still emitted.
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
    // One stack walk gives both the label and the call site, as in TracedScope.
    TaskNameDeriver.CallSite callSite = TaskNameDeriver.deriveCallSite(task);

    TaskForkedEvent event = new TaskForkedEvent();
    event.scopeId = state.scopeId();
    event.scopeName = state.name();
    event.taskId = taskId;
    event.taskName = TaskNameDeriver.format(callSite);
    event.threadName = Thread.currentThread().getName();
    if (callSite != null) {
      event.callSiteClassName = callSite.className();
      event.callSiteMethodName = callSite.methodName();
      event.callSiteLine = callSite.line();
    }
    event.commit();

    task = new TracingCallable<>(task, state.name(), state.scopeId(), taskId);
  }
}
