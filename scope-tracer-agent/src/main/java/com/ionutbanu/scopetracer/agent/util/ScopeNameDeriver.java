package com.ionutbanu.scopetracer.agent.util;

/**
 * Derives a human-readable scope name from the call-site stack frame that opened a {@code
 * StructuredTaskScope}.
 *
 * <p>Walks the stack and returns the first frame that is neither inside {@code
 * java.util.concurrent.StructuredTaskScope} nor inside {@code com.ionutbanu.scopetracer.agent},
 * formatted as {@code SimpleClassName#methodName}.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 */
public final class ScopeNameDeriver {

  private ScopeNameDeriver() {}

  /**
   * Derives a scope name from the current call stack. Returns {@code "unknown-scope"} if no
   * suitable frame is found.
   */
  /** Self-name passed to {@link StackFilter} so this class is excluded from the walk. */
  private static final String SELF = "com.ionutbanu.scopetracer.agent.util.ScopeNameDeriver";

  public static String derive() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    // Do NOT exclude the entire com.ionutbanu.scopetracer.agent package — user code
                    // (e.g. test subjects) may live there too. Only this deriver class needs
                    // exclusion; advice methods are inlined and surface as StructuredTaskScope
                    // frames (already filtered by StackFilter).
                    .filter(f -> StackFilter.isUserFrame(f.getClassName(), SELF))
                    .findFirst()
                    .map(ScopeNameDeriver::format)
                    .orElse("unknown-scope"));
  }

  private static String format(StackWalker.StackFrame f) {
    String cls = f.getClassName();
    // Use simple class name (strip package)
    int dot = cls.lastIndexOf('.');
    String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
    // Strip inner-class suffix after '$'
    int dollar = simple.indexOf('$');
    if (dollar > 0) simple = simple.substring(0, dollar);
    // Clean up synthetic lambda method names: "lambda$main$0" → "main"
    String method = f.getMethodName();
    if (method.startsWith("lambda$")) {
      String inner = method.substring("lambda$".length());
      int lastDollar = inner.lastIndexOf('$');
      method = lastDollar > 0 ? inner.substring(0, lastDollar) : inner;
    }
    return simple + "#" + method;
  }
}
