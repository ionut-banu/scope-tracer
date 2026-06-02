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
   * The result of a single stack-walk at scope-open time. Captures both the human-readable name
   * (used for the {@code scopeName} field on JFR events) and the caller's fully-qualified package
   * (used by capture-filter package rules).
   *
   * @param displayName e.g. {@code OrderProcessor#processOrder}; {@code "unknown-scope"} when no
   *     user frame was found.
   * @param callerPackage fully-qualified package of the call-site class, or empty string when no
   *     user frame was found.
   */
  public record DerivedFrame(String displayName, String callerPackage) {
    /** Sentinel returned when the stack walk produces no user frame. */
    public static final DerivedFrame UNKNOWN = new DerivedFrame("unknown-scope", "");
  }

  /** Self-name passed to {@link StackFilter} so this class is excluded from the walk. */
  private static final String SELF = "com.ionutbanu.scopetracer.agent.util.ScopeNameDeriver";

  /**
   * Convenience wrapper around {@link #deriveFrame()} for callers that only need the display name.
   * Returns {@code "unknown-scope"} if no suitable frame is found.
   */
  public static String derive() {
    return deriveFrame().displayName();
  }

  /**
   * Walks the stack once and returns both the formatted display name AND the caller's package.
   * Use this in preference to {@link #derive()} when package info is also needed (e.g. for
   * capture-filter package rules), so the stack walk is paid for only once.
   */
  public static DerivedFrame deriveFrame() {
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
                    .map(ScopeNameDeriver::toDerivedFrame)
                    .orElse(DerivedFrame.UNKNOWN));
  }

  private static DerivedFrame toDerivedFrame(StackWalker.StackFrame f) {
    String cls = f.getClassName();
    int dot = cls.lastIndexOf('.');
    String pkg = dot >= 0 ? cls.substring(0, dot) : "";
    String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
    int dollar = simple.indexOf('$');
    if (dollar > 0) simple = simple.substring(0, dollar);
    String method = f.getMethodName();
    if (method.startsWith("lambda$")) {
      String inner = method.substring("lambda$".length());
      int lastDollar = inner.lastIndexOf('$');
      method = lastDollar > 0 ? inner.substring(0, lastDollar) : inner;
    }
    return new DerivedFrame(simple + "#" + method, pkg);
  }
}
