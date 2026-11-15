package com.ionutbanu.scopetracer.agent;

import java.util.concurrent.atomic.AtomicBoolean;
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
 * {@code ClassLoader} reference, and never throws. A class that loads but in which no advised
 * method matches gives ByteBuddy no error, so it is not reported here (#130).
 */
final class TransformFailureListener extends AgentBuilder.Listener.Adapter {

  static final String SCOPE = "java.util.concurrent.StructuredTaskScope";
  static final String SCOPE_IMPL = "java.util.concurrent.StructuredTaskScopeImpl";

  /** The one the agent installs, so a second {@code premain} in the same JVM shares its state. */
  static final TransformFailureListener INSTANCE = new TransformFailureListener();

  private final AtomicBoolean scopeReported = new AtomicBoolean();
  private final AtomicBoolean scopeImplReported = new AtomicBoolean();

  TransformFailureListener() {}

  @Override
  public void onError(
      String typeName,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      Throwable throwable) {
    try {
      AtomicBoolean reported;
      if (SCOPE.equals(typeName)) {
        reported = scopeReported;
      } else if (SCOPE_IMPL.equals(typeName)) {
        reported = scopeImplReported;
      } else {
        return;
      }
      if (!reported.compareAndSet(false, true)) return;
      AgentLog.warn(
          "cannot instrument " + typeName + ", the trace will be incomplete: " + reason(throwable));
    } catch (Throwable ignored) {
      // a listener that throws would end up in ByteBuddy's own error handling; lose the line
    }
  }

  /** {@link AgentRecording#describe}, or only the exception's type when its message throws. */
  private static String reason(Throwable throwable) {
    if (throwable == null) return "unknown error";
    try {
      return AgentRecording.describe(throwable);
    } catch (Throwable t) {
      return throwable.getClass().getSimpleName();
    }
  }
}
