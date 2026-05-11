package com.ionutbanu.scopetracer.agent.util;

import java.util.concurrent.Callable;

/**
 * Derives a human-readable label for a task forked into an instrumented {@code
 * StructuredTaskScope}.
 *
 * <p>Two-tier strategy mirroring the in-core deriver:
 *
 * <ol>
 *   <li>If the {@link Callable} is a real (non-synthetic, non-hidden) user class, return its
 *       {@linkplain Class#getSimpleName() simple name}.
 *   <li>Otherwise, walk the stack and return the first frame outside the agent and the JDK's {@code
 *       StructuredTaskScope} machinery, formatted as {@code SimpleClassName#methodName:line}. The
 *       line number makes sibling forks in the same method distinguishable.
 * </ol>
 *
 * <p>Returns {@code null} when no usable label can be derived. The advice swallows the result and
 * stamps {@code null} on the event in that case; the renderer falls back to the {@code #N} index.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 */
public final class TaskNameDeriver {

  private static final String SELF = "com.ionutbanu.scopetracer.agent.util.TaskNameDeriver";

  private TaskNameDeriver() {}

  /** Derives a label for {@code task}, or {@code null} if no usable label can be produced. */
  public static String derive(Callable<?> task) {
    try {
      String fromClass = fromClass(task);
      if (fromClass != null) return fromClass;
      return fromCallerFrame();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String fromClass(Callable<?> task) {
    if (task == null) return null;
    Class<?> cls = task.getClass();
    if (cls.isHidden() || cls.isSynthetic()) return null;
    String simple = cls.getSimpleName();
    if (simple == null || simple.isBlank()) return null;
    if (simple.contains("$$Lambda")) return null;
    return simple;
  }

  private static String fromCallerFrame() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .filter(f -> StackFilter.isUserFrame(f.getClassName(), SELF))
                    .findFirst()
                    .map(TaskNameDeriver::format)
                    .orElse(null));
  }

  private static String format(StackWalker.StackFrame f) {
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
    int line = f.getLineNumber();
    return line > 0 ? simple + "#" + method + ":" + line : simple + "#" + method;
  }
}
