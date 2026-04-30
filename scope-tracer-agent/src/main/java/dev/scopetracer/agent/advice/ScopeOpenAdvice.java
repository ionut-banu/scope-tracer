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
 *   <li>Resolves the scope name: prefers the user-configured name captured by {@link
 *       ScopeConstructorAdvice} via {@link AgentState#PENDING_SCOPE_NAME}; falls back to {@link
 *       ScopeNameDeriver} (call-site stack frame) when no name was configured.
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

    // Prefer the name set via Config.withName(); fall back to the call-site stack frame.
    String pending = AgentState.PENDING_SCOPE_NAME.get();
    AgentState.PENDING_SCOPE_NAME.remove();
    String name = (pending != null && !pending.isBlank()) ? pending : ScopeNameDeriver.derive();

    long scopeId = AgentState.SCOPE_ID_COUNTER.incrementAndGet();
    AgentState.SCOPE_STATES.put(scope, new ScopeState(name, scopeId, new AtomicLong()));

    ScopeOpenedEvent event = new ScopeOpenedEvent();
    event.scopeId = scopeId;
    event.scopeName = name;
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }
}
