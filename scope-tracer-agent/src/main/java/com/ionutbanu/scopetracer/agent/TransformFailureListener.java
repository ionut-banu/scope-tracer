package com.ionutbanu.scopetracer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.utility.JavaModule;

/**
 * Prints one {@code [scope-tracer] WARN} line when ByteBuddy cannot transform {@code
 * java.util.concurrent.StructuredTaskScope} or {@code java.util.concurrent.StructuredTaskScopeImpl}
 * (issue #121). Without it, such a failure was silent unless {@code verbose} was set: the program
 * ran, and the recording came out empty or held only {@code ScopeOpened}.
 *
 * <p>The line is printed at most once per class per JVM, whichever thread reports it and however
 * often: ByteBuddy reports a class again on every retransformation. It is printed with or without
 * {@code verbose}, next to ByteBuddy's own listeners, which it does not replace:
 *
 * <pre>{@code
 * [scope-tracer] WARN cannot instrument java.util.concurrent.StructuredTaskScope, the trace will be incomplete: IllegalStateException: ... does not define an index 9
 * }</pre>
 *
 * <p>It only prints. It never retries, retransforms or disables anything, keeps no {@code Class} or
 * {@code ClassLoader} reference, and never throws. A class that loads but in which an advice
 * matches no method gives ByteBuddy no error; {@link MatcherMissListener} reports that (#130). Both
 * share one {@link CannotInstrument}, so a class gets at most one {@code cannot instrument} line,
 * whichever reports it first.
 */
final class TransformFailureListener extends AgentBuilder.Listener.Adapter {

  static final String SCOPE = "java.util.concurrent.StructuredTaskScope";
  static final String SCOPE_IMPL = "java.util.concurrent.StructuredTaskScopeImpl";

  /** The one the agent installs, so a second {@code premain} in the same JVM shares its state. */
  static final TransformFailureListener INSTANCE =
      new TransformFailureListener(CannotInstrument.SHARED);

  private final CannotInstrument lines;

  /** A listener with a state of its own, for a test. */
  TransformFailureListener() {
    this(new CannotInstrument());
  }

  TransformFailureListener(CannotInstrument lines) {
    this.lines = lines;
  }

  @Override
  public void onError(
      String typeName,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      Throwable throwable) {
    try {
      if (!SCOPE.equals(typeName) && !SCOPE_IMPL.equals(typeName)) return;
      if (lines.isReported(typeName)) return;
      lines.warn(typeName, AgentLog.describe(throwable));
    } catch (Throwable ignored) {
      // a listener that throws would end up in ByteBuddy's own error handling; lose the line
    }
  }
}
