package com.ionutbanu.scopetracer.agent.util;

import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Derives a human-readable label and the structured call site for a task forked into an
 * instrumented {@code StructuredTaskScope}.
 *
 * <p>Two-tier strategy, the same as core's {@code TaskNameDeriver} (which {@code TracedScope}
 * uses), so a recording does not reveal which producer made it:
 *
 * <ol>
 *   <li>If the {@link Callable} is a real (non-synthetic, non-hidden, non-anonymous) user class,
 *       the label is its {@linkplain Class#getSimpleName() simple name} and the call-site class is
 *       its {@linkplain Class#getName() binary name}, with no method and no line.
 *   <li>Otherwise, walk the stack and take the first frame outside the agent and the JDK's {@code
 *       StructuredTaskScope} machinery. The label is {@code SimpleClassName#methodName:line}, and
 *       the call site is the frame's binary class name, method and line. The line number makes
 *       sibling forks in the same method distinguishable.
 * </ol>
 *
 * <p>The call-site class is the JVM binary name, uncut ({@code com.acme.Plain$Named}, {@code
 * com.acme.Plain$1}). The two derivers are separate copies; {@code ProducerCallSiteIT} runs one
 * subject under each producer and fails if they stop agreeing.
 *
 * <p>Returns {@code null} when no usable call site can be derived. The advice then stamps {@code
 * null} label and call-site fields on the event; the renderer falls back to the {@code #N} index.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 */
public final class TaskNameDeriver {

  private static final String SELF = "com.ionutbanu.scopetracer.agent.util.TaskNameDeriver";

  private TaskNameDeriver() {}

  /** Derives a label for {@code task}, or {@code null} if no usable label can be produced. */
  public static String derive(Callable<?> task) {
    return format(deriveCallSite(task));
  }

  /**
   * Derives the call site of a fork of {@code task}, walking the stack at most once. Returns {@code
   * null} when none can be derived; never throws.
   */
  public static CallSite deriveCallSite(Callable<?> task) {
    try {
      CallSite fromClass = fromClass(task);
      if (fromClass != null) return fromClass;
      return StackWalker.getInstance().walk(TaskNameDeriver::fromFrames);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The task label for {@code callSite}: the simple name for a named {@code Callable}, otherwise
   * {@code SimpleClassName#methodName[:line]}; {@code null} for a {@code null} call site.
   */
  public static String format(CallSite callSite) {
    if (callSite == null) return null;
    if (callSite.methodName() == null) return callSite.simpleName();
    return callSite.line() > 0
        ? callSite.simpleName() + "#" + callSite.methodName() + ":" + callSite.line()
        : callSite.simpleName() + "#" + callSite.methodName();
  }

  private static CallSite fromClass(Callable<?> task) {
    if (task == null) return null;
    Class<?> cls = task.getClass();
    if (cls.isHidden() || cls.isSynthetic()) return null;
    String simple = cls.getSimpleName();
    if (simple == null || simple.isBlank()) return null;
    if (simple.contains("$$Lambda")) return null;
    return new CallSite(cls.getName(), simple, null, 0);
  }

  /**
   * The call site of the first user frame in {@code frames}, or {@code null} when there is none.
   */
  static CallSite fromFrames(Stream<? extends StackWalker.StackFrame> frames) {
    return frames
        .filter(f -> StackFilter.isUserFrame(f.getClassName(), SELF))
        .findFirst()
        .map(TaskNameDeriver::toCallSite)
        .orElse(null);
  }

  /**
   * Maps a caller frame to its call site: the binary class name as the frame reports it, and for
   * the label the simple name cut at the first {@code $}.
   */
  static CallSite toCallSite(StackWalker.StackFrame f) {
    String cls = f.getClassName();
    int dot = cls.lastIndexOf('.');
    String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
    int dollar = simple.indexOf('$');
    if (dollar > 0) simple = simple.substring(0, dollar);
    String method = f.getMethodName();
    if (method.startsWith("lambda$")) {
      String inner = method.substring("lambda$".length());
      int lastDollar = inner.lastIndexOf('$');
      method = lastDollar > 0 ? inner.substring(0, lastDollar) : inner;
    }
    return new CallSite(cls, simple, method, f.getLineNumber());
  }

  /**
   * The call site of one fork, as stamped on {@code TaskForkedEvent}.
   *
   * @param className JVM binary name of the class, uncut; stamped as {@code callSiteClassName}.
   * @param simpleName the class name the label uses.
   * @param methodName enclosing method for the caller-frame tier; {@code null} for a named {@code
   *     Callable}. Stamped as {@code callSiteMethodName}.
   * @param line source line of the caller frame; {@code 0} when unknown or for a named {@code
   *     Callable}. Stamped as {@code callSiteLine}.
   */
  public record CallSite(String className, String simpleName, String methodName, int line) {}
}
