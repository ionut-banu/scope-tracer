package com.ionutbanu.scopetracer.agent.advice;

import com.ionutbanu.scopetracer.agent.AgentState;
import com.ionutbanu.scopetracer.agent.CaptureFilter;
import com.ionutbanu.scopetracer.agent.ScopeState;
import com.ionutbanu.scopetracer.agent.util.ScopeNameDeriver;
import com.ionutbanu.scopetracer.core.events.ScopeOpenedEvent;
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
 *   <li>Consults {@link AgentState#FILTER}. If the scope is rejected by include/exclude rules or
 *       skipped by the sampler, the advice returns immediately — no {@link ScopeState} is
 *       registered, so all downstream advice (fork, close, completion) become no-ops via their
 *       existing null-state short-circuits. <b>Zero</b> events are emitted for filtered scopes.
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

    String pending = AgentState.PENDING_SCOPE_NAME.get();
    AgentState.PENDING_SCOPE_NAME.remove();

    CaptureFilter filter = AgentState.FILTER;
    String name;
    String callerPackage;
    // Walk the stack when we need to derive the name OR when package filters are active.
    // For the default (PASSTHROUGH) configuration with a Config-supplied name this stays on
    // the existing fast path: pending != null && !hasPackageRules() ⇒ no stack walk.
    boolean needFrame = pending == null || pending.isBlank() || filter.hasPackageRules();
    if (needFrame) {
      var frame = ScopeNameDeriver.deriveFrame();
      name = (pending != null && !pending.isBlank()) ? pending : frame.displayName();
      callerPackage = frame.callerPackage();
    } else {
      name = pending;
      callerPackage = "";
    }

    if (!filter.shouldCapture(name, callerPackage)) return;

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
