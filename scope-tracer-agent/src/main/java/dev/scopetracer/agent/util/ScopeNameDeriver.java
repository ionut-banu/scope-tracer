package dev.scopetracer.agent.util;

/**
 * Derives a human-readable scope name from the call-site stack frame that opened a {@code
 * StructuredTaskScope}.
 *
 * <p>Walks the stack and returns the first frame that is neither inside {@code
 * java.util.concurrent.StructuredTaskScope} nor inside {@code dev.scopetracer.agent}, formatted as
 * {@code SimpleClassName#methodName}.
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
  public static String derive() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .filter(
                        f -> {
                          String cls = f.getClassName();
                          // Exclude JDK scope machinery and ByteBuddy internals.
                          // Do NOT exclude the entire dev.scopetracer.agent package:
                          // user code (e.g. test subjects) may live there too.
                          // The only agent class that genuinely appears on this
                          // stack is ScopeNameDeriver itself (advice methods are
                          // inlined by ByteBuddy and show up as StructuredTaskScope
                          // frames, which are already excluded below).
                          return !cls.startsWith("java.util.concurrent.StructuredTaskScope")
                              && !cls.equals("dev.scopetracer.agent.util.ScopeNameDeriver")
                              && !cls.startsWith("net.bytebuddy.");
                        })
                    .findFirst()
                    .map(
                        f -> {
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
                        })
                    .orElse("unknown-scope"));
  }
}
