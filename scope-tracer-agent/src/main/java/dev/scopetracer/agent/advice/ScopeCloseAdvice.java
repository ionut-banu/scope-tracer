package dev.scopetracer.agent.advice;

import dev.scopetracer.agent.AgentState;
import dev.scopetracer.core.events.ScopeClosedEvent;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to {@code StructuredTaskScope.close()} instance method. Runs
 * after the instrumented method body and:
 *
 * <ol>
 *   <li>Emits a {@code ScopeClosedEvent} JFR event.
 *   <li>Removes the scope's {@link dev.scopetracer.agent.ScopeState} from {@link
 *       AgentState#SCOPE_STATES}.
 * </ol>
 *
 * <p>If the scope is not tracked the handler is a no-op.
 */
public final class ScopeCloseAdvice {

  private ScopeCloseAdvice() {}

  /**
   * Advice exit handler — runs after the instrumented {@code close()} method body.
   *
   * @param scope the {@code StructuredTaskScope} instance ({@code this}).
   */
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onClose(@Advice.This Object scope) {
    var state = AgentState.SCOPE_STATES.remove(scope);
    if (state == null) return;

    ScopeClosedEvent event = new ScopeClosedEvent();
    event.scopeName = state.name();
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }
}
