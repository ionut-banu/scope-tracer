package com.ionutbanu.scopetracer.core;

import java.util.concurrent.Callable;

/**
 * Derives a human-readable label for a task forked into a {@link TracedScope}.
 *
 * <p>Two-tier strategy:
 *
 * <ol>
 *   <li>If the {@link Callable} is a real (non-synthetic, non-hidden) user class, return its
 *       {@linkplain Class#getSimpleName() simple name}. This covers {@code scope.fork(new
 *       FindUserTask())}-style call sites.
 *   <li>Otherwise (typically a lambda or method reference), walk the stack and return the first
 *       caller frame outside {@code com.ionutbanu.scopetracer.core} and the JDK's {@code
 *       StructuredTaskScope}, formatted as {@code SimpleClassName#methodName:line} — matching the
 *       agent's {@code ScopeNameDeriver} convention. The line number makes sibling forks in the
 *       same method distinguishable (e.g. three {@code scope.fork(() -> …)} calls each get a unique
 *       label).
 * </ol>
 *
 * <p>Returns {@code null} when no usable label can be derived (e.g. an opaque proxy invoked from a
 * thread with no application frames). Callers stamp {@code null} on the event and the renderer
 * falls back to {@code #N}.
 */
final class TaskNameDeriver {

  private TaskNameDeriver() {}

  /**
   * Derives a label for {@code task}, or {@code null} if no usable label can be produced. Never
   * throws — any {@link RuntimeException} during derivation is swallowed and {@code null} is
   * returned.
   */
  static String derive(Callable<?> task) {
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
    // Defensive: getSimpleName on a synthetic-but-not-flagged lambda class typically contains
    // "$$Lambda" — skip those too.
    if (simple.contains("$$Lambda")) return null;
    return simple;
  }

  private static String fromCallerFrame() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .filter(
                        f -> {
                          String cls = f.getClassName();
                          // Exclude only the deriver and TracedScope themselves — NOT the entire
                          // com.ionutbanu.scopetracer.core package, which would also drop test
                          // classes living in that same package. JDK scope machinery is filtered
                          // by the StructuredTaskScope prefix check.
                          return !cls.equals("com.ionutbanu.scopetracer.core.TracedScope")
                              && !cls.equals("com.ionutbanu.scopetracer.core.TaskNameDeriver")
                              && !cls.startsWith("java.util.concurrent.StructuredTaskScope");
                        })
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
