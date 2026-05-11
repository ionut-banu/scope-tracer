package com.ionutbanu.scopetracer.agent.util;

/**
 * Shared stack-frame filter used by {@link ScopeNameDeriver} and {@link TaskNameDeriver}.
 *
 * <p>Excludes JDK scope machinery (which surfaces inlined advice as {@code StructuredTaskScope*}
 * frames) and ByteBuddy internals. The deriver classes themselves must also be excluded — see
 * {@link #isUserFrame(String, String)}.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 */
public final class StackFilter {

  private StackFilter() {}

  /**
   * Returns {@code true} when {@code className} is a frame outside the agent's own machinery — i.e.
   * a user-code or library frame that should be considered a candidate for naming.
   *
   * @param className fully-qualified class name from the stack frame.
   * @param selfClassName fully-qualified name of the deriver class doing the walk; that frame must
   *     be excluded.
   */
  public static boolean isUserFrame(String className, String selfClassName) {
    return !className.startsWith("java.util.concurrent.StructuredTaskScope")
        && !className.equals(selfClassName)
        && !className.startsWith("net.bytebuddy.");
  }
}
