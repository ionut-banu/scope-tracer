package com.ionutbanu.scopetracer.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On the first {@code StructuredTaskScope.open} in the JVM, checks that the scope it returned is a
 * {@code java.util.concurrent.StructuredTaskScopeImpl}, the class the agent instruments (issue
 * #130). A JDK that renamed that class still loads it, but the agent's type matcher never matches
 * it and ByteBuddy reports nothing, so the trace would hold {@code ScopeOpened} and nothing else
 * with no word why. Then one line is printed, once per JVM:
 *
 * <pre>{@code
 * [scope-tracer] WARN cannot instrument java.util.concurrent.StructuredTaskScopeImpl2, the trace will be incomplete: StructuredTaskScope.open returned it, and the agent instruments only java.util.concurrent.StructuredTaskScopeImpl
 * }</pre>
 *
 * <p>Called by {@code ScopeOpenAdvice} before its filter and its claim on the scope, so a filtered
 * or sampled-out first scope is checked too. The advice is inlined into {@code
 * java.util.concurrent}, which is why the entry point is public. After the first {@code open} it
 * costs one volatile read. It compares class names only, keeps no {@code Class} reference, only
 * prints, and never throws.
 */
public final class FirstOpenCheck {

  static final String REASON =
      "StructuredTaskScope.open returned it, and the agent instruments only "
          + TransformFailureListener.SCOPE_IMPL;

  private static final FirstOpenCheck INSTANCE = new FirstOpenCheck(CannotInstrument.SHARED);

  private final AtomicBoolean checked = new AtomicBoolean();
  private final CannotInstrument lines;

  FirstOpenCheck(CannotInstrument lines) {
    this.lines = lines;
  }

  /**
   * What {@code ScopeOpenAdvice} calls with each scope {@code open} returns.
   *
   * @param scope the scope {@code open} returned; {@code null} is ignored.
   */
  public static void onOpen(Object scope) {
    INSTANCE.check(scope);
  }

  /** The check: the first non-null scope only, by any thread. */
  void check(Object scope) {
    try {
      if (scope == null || checked.get() || !checked.compareAndSet(false, true)) return;
      String name = scope.getClass().getName();
      if (TransformFailureListener.SCOPE_IMPL.equals(name)) return;
      lines.warn(AgentConfig.shown(name), REASON);
    } catch (Throwable ignored) {
      // the line is lost, the program is not
    }
  }
}
