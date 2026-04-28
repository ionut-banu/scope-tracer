package dev.scopetracer.agent.advice;

import dev.scopetracer.agent.AgentState;
import dev.scopetracer.agent.ScopeState;
import dev.scopetracer.agent.util.ScopeNameDeriver;
import dev.scopetracer.core.events.ScopeOpenedEvent;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to {@code StructuredTaskScope.open()} static factory methods.
 * Runs after {@code open()} returns and:
 *
 * <ol>
 *   <li>Derives a scope name from the call-site stack frame.
 *   <li>Records a {@link ScopeState} in {@link AgentState#SCOPE_STATES} keyed by the new scope
 *       instance.
 *   <li>Emits a {@code ScopeOpenedEvent} JFR event.
 * </ol>
 */
public final class ScopeOpenAdvice {

  private ScopeOpenAdvice() {}

  /**
   * Advice exit handler — runs after the instrumented {@code open()} method body completes.
   *
   * @param scope the newly created {@code StructuredTaskScope} instance returned by {@code open()}.
   */
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onOpen(@Advice.Return Object scope) {
    if (scope == null) return;
    String name = ScopeNameDeriver.derive();
    AgentState.SCOPE_STATES.put(scope, new ScopeState(name, new AtomicLong()));

    ScopeOpenedEvent event = new ScopeOpenedEvent();
    event.scopeName = name;
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }
}
