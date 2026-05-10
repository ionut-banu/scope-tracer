package com.ionutbanu.scopetracer.agent.advice;

import com.ionutbanu.scopetracer.agent.AgentState;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to the private {@code StructuredTaskScopeImpl(Joiner,
 * ThreadFactory, String)} constructor. Runs before the constructor body and captures the
 * user-configured scope name (the third argument) into {@link AgentState#PENDING_SCOPE_NAME}.
 *
 * <p>The name is {@code null} when the user did not call {@code Config.withName()} — in that case
 * {@link ScopeOpenAdvice} falls back to the call-site stack-walker name.
 *
 * <p>Thread-safety: {@link AgentState#PENDING_SCOPE_NAME} is a {@link ThreadLocal}, so concurrent
 * scope creations on different threads do not interfere. Nested scope creations on the same thread
 * are fine because each {@code open()} call is sequential — the constructor fires and is consumed
 * by {@link ScopeOpenAdvice} before the next {@code open()} begins.
 */
public final class ScopeConstructorAdvice {

  private ScopeConstructorAdvice() {}

  /**
   * Advice enter handler — fires before the {@code StructuredTaskScopeImpl} constructor body.
   *
   * @param name the scope name argument (index 2); {@code null} when no name was configured.
   */
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onConstruct(@Advice.Argument(2) String name) {
    AgentState.PENDING_SCOPE_NAME.set(name);
  }
}
