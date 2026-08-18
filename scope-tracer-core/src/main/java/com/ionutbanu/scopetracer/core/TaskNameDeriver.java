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
    return format(deriveCallSite(task));
  }

  /**
   * Derives the structured call-site components for {@code task}: the class name (always present
   * when derivation succeeds), the enclosing method name (present only for the caller-frame tier),
   * and the source line ({@code 0} when unknown). Returns {@code null} under the same conditions as
   * {@link #derive(Callable)} — never throws.
   *
   * <p>Used both to build {@link #derive(Callable)}'s formatted label and, independently, by {@link
   * TracedScope#fork(String, Callable)} to stamp {@code TaskForkedEvent}'s {@code callSite*} fields
   * even when the caller supplied an explicit label — so an explicitly-named fork's source location
   * is never lost.
   */
  static CallSiteInfo deriveCallSite(Callable<?> task) {
    try {
      String fromClass = fromClass(task);
      if (fromClass != null) return new CallSiteInfo(fromClass, null, 0);
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

  private static CallSiteInfo fromCallerFrame() {
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
                    .map(TaskNameDeriver::toCallSiteInfo)
                    .orElse(null));
  }

  private static CallSiteInfo toCallSiteInfo(StackWalker.StackFrame f) {
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
    return new CallSiteInfo(simple, method, f.getLineNumber());
  }

  static String format(CallSiteInfo info) {
    if (info == null) return null;
    if (info.methodName() == null) return info.className();
    return info.line() > 0
        ? info.className() + "#" + info.methodName() + ":" + info.line()
        : info.className() + "#" + info.methodName();
  }

  /**
   * Structured call-site components, as an alternative to {@link #derive(Callable)}'s pre-formatted
   * string.
   *
   * @param className always present when derivation succeeds.
   * @param methodName present only when derived from a caller stack frame (lambda/method
   *     reference); {@code null} for a named {@code Callable} class.
   * @param line source line of the caller frame; {@code 0} when unknown or not applicable (the
   *     named-class tier never has a line).
   */
  record CallSiteInfo(String className, String methodName, int line) {}
}
