package com.ionutbanu.scopetracer.core;

import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Derives a human-readable label and the structured call site for a task forked into a {@link
 * TracedScope}.
 *
 * <p>Two-tier strategy:
 *
 * <ol>
 *   <li>If the {@link Callable} is a real (non-synthetic, non-hidden, non-anonymous) user class,
 *       the label is its {@linkplain Class#getSimpleName() simple name} and the call-site class is
 *       its {@linkplain Class#getName() binary name}, with no method and no line. This covers
 *       {@code scope.fork(new FindUserTask())}-style call sites.
 *   <li>Otherwise (typically a lambda or method reference), walk the stack and take the first
 *       caller frame outside {@code TracedScope}, this class and the JDK's {@code
 *       StructuredTaskScope}. The label is {@code SimpleClassName#methodName:line} — matching the
 *       agent's convention — and the call site is the frame's binary class name, method and line.
 *       The line number makes sibling forks in the same method distinguishable (e.g. three {@code
 *       scope.fork(() -> …)} calls each get a unique label).
 * </ol>
 *
 * <p>The call-site class is the JVM binary name, uncut: {@code com.acme.Plain$Named} for a nested
 * class, {@code com.acme.Plain$1} for an anonymous one, the hosting class for a lambda. The label
 * keeps the simple name it always had. The agent's {@code TaskNameDeriver} applies the same rule
 * and {@code ProducerCallSiteIT} in the agent module checks that the two agree.
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
   * Derives the structured call-site components for {@code task}: the binary class name (always
   * present when derivation succeeds), the enclosing method name (present only for the caller-frame
   * tier), and the source line ({@code 0} when unknown). Returns {@code null} under the same
   * conditions as {@link #derive(Callable)} — never throws. The stack is walked at most once.
   *
   * <p>Used both to build {@link #derive(Callable)}'s formatted label and, independently, by {@link
   * TracedScope#fork(String, Callable)} to stamp {@code TaskForkedEvent}'s {@code callSite*} fields
   * even when the caller supplied an explicit label — so an explicitly-named fork's source location
   * is never lost.
   */
  static CallSiteInfo deriveCallSite(Callable<?> task) {
    try {
      CallSiteInfo fromClass = fromClass(task);
      if (fromClass != null) return fromClass;
      return StackWalker.getInstance().walk(TaskNameDeriver::fromFrames);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static CallSiteInfo fromClass(Callable<?> task) {
    if (task == null) return null;
    Class<?> cls = task.getClass();
    if (cls.isHidden() || cls.isSynthetic()) return null;
    String simple = cls.getSimpleName();
    if (simple == null || simple.isBlank()) return null;
    // Defensive: getSimpleName on a synthetic-but-not-flagged lambda class typically contains
    // "$$Lambda" — skip those too.
    if (simple.contains("$$Lambda")) return null;
    return new CallSiteInfo(cls.getName(), simple, null, 0);
  }

  /**
   * The call site of the first user frame in {@code frames}, or {@code null} when there is none.
   * Package-private so a test can pass frames no real stack has.
   */
  static CallSiteInfo fromFrames(Stream<? extends StackWalker.StackFrame> frames) {
    return frames
        .filter(
            f -> {
              String cls = f.getClassName();
              // Exclude only the deriver and TracedScope themselves — NOT the entire
              // com.ionutbanu.scopetracer.core package, which would also drop test classes living
              // in that same package. JDK scope machinery is filtered by the StructuredTaskScope
              // prefix check.
              return !cls.equals("com.ionutbanu.scopetracer.core.TracedScope")
                  && !cls.equals("com.ionutbanu.scopetracer.core.TaskNameDeriver")
                  && !cls.startsWith("java.util.concurrent.StructuredTaskScope");
            })
        .findFirst()
        .map(TaskNameDeriver::toCallSiteInfo)
        .orElse(null);
  }

  /**
   * Maps a caller frame to its call site: the binary class name as the frame reports it, and for
   * the label the simple name cut at the first {@code $} (so a fork in {@code Outer$Inner} or in a
   * lambda of {@code Outer} is labelled {@code Outer}).
   */
  static CallSiteInfo toCallSiteInfo(StackWalker.StackFrame f) {
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
    return new CallSiteInfo(cls, simple, method, f.getLineNumber());
  }

  static String format(CallSiteInfo info) {
    if (info == null) return null;
    if (info.methodName() == null) return info.simpleName();
    return info.line() > 0
        ? info.simpleName() + "#" + info.methodName() + ":" + info.line()
        : info.simpleName() + "#" + info.methodName();
  }

  /**
   * Structured call-site components, as an alternative to {@link #derive(Callable)}'s pre-formatted
   * string.
   *
   * @param className JVM binary name of the class, uncut (e.g. {@code com.acme.Plain$Named});
   *     always present when derivation succeeds. Stamped as {@code callSiteClassName}.
   * @param simpleName the class name the label uses: the simple name of a named {@code Callable},
   *     or the frame's class name without its package and cut at the first {@code $}.
   * @param methodName present only when derived from a caller stack frame (lambda/method
   *     reference); {@code null} for a named {@code Callable} class.
   * @param line source line of the caller frame; {@code 0} when unknown or not applicable (the
   *     named-class tier never has a line).
   */
  record CallSiteInfo(String className, String simpleName, String methodName, int line) {}
}
