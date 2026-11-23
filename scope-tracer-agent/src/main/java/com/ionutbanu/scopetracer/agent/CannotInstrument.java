package com.ionutbanu.scopetracer.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@code [scope-tracer] WARN cannot instrument <class>, the trace will be incomplete:
 * <reason>} line per class name, whoever finds the problem: {@link TransformFailureListener} when
 * ByteBuddy reports an error (#121), {@link MatcherMissListener} when an advice matched no method,
 * and {@link FirstOpenCheck} when {@code open} returned a class the agent does not instrument
 * (#130). The first report for a class name prints its line; every later one, from any of them and
 * on any thread, prints nothing.
 *
 * <p>Keeps class names only, never a {@code Class} or {@code ClassLoader}. The names it ever holds
 * are the two scope classes and the class the first {@code open} returned, so it stays tiny.
 */
final class CannotInstrument {

  /** The state the installed listeners and the first-open check share. */
  static final CannotInstrument SHARED = new CannotInstrument();

  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  CannotInstrument() {}

  /** Whether a line for {@code className} has been printed already. */
  boolean isReported(String className) {
    return reported.contains(className);
  }

  /**
   * Prints the line for {@code className} with {@code reason}, unless one was printed for that name
   * before. {@code className} is printed as given; a caller that has a name it did not choose
   * escapes it first.
   */
  void warn(String className, String reason) {
    if (!reported.add(className)) return;
    AgentLog.warn("cannot instrument " + className + ", the trace will be incomplete: " + reason);
  }
}
