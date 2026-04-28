package dev.scopetracer.agent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Singleton holder for agent-wide state. Uses {@link WeakHashMap} so that GC can collect scope and
 * subtask instances once they are no longer reachable by application code.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is shaded into the agent
 * fat-jar which is listed on {@code Boot-Class-Path}).
 */
public final class AgentState {

  private AgentState() {}

  /**
   * Maps a live {@code StructuredTaskScope} instance to its {@link ScopeState}. Entry is removed
   * when the scope's {@code close()} exits.
   */
  public static final Map<Object, ScopeState> SCOPE_STATES =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * Carries the user-configured scope name (from {@code Config.withName()}) from the {@code
   * StructuredTaskScopeImpl} constructor advice to {@code ScopeOpenAdvice}. Set by {@link
   * dev.scopetracer.agent.advice.ScopeConstructorAdvice} and consumed (then cleared) by {@link
   * dev.scopetracer.agent.advice.ScopeOpenAdvice}.
   *
   * <p>{@code null} means no name was configured; the open advice falls back to {@link
   * dev.scopetracer.agent.util.ScopeNameDeriver}.
   */
  public static final ThreadLocal<String> PENDING_SCOPE_NAME = new ThreadLocal<>();
}
