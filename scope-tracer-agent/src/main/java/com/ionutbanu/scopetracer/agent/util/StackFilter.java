package com.ionutbanu.scopetracer.agent.util;

import net.bytebuddy.ByteBuddy;

/**
 * Shared stack-frame filter used by {@link ScopeNameDeriver} and {@link TaskNameDeriver}.
 *
 * <p>Excludes JDK scope machinery (which surfaces inlined advice as {@code StructuredTaskScope*}
 * frames) and ByteBuddy internals. The deriver classes themselves must also be excluded — see
 * {@link #isUserFrame(String, String)}.
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 *
 * <p>The fat-jar relocates ByteBuddy, so the agent's own ByteBuddy frames are not under {@code
 * net.bytebuddy}. Its package is therefore taken from the class, which the shade plugin relocates
 * along with everything else, and {@code net.bytebuddy.} is kept as well for an application's own
 * ByteBuddy.
 */
public final class StackFilter {

  /** {@code net.bytebuddy.}, built at run time so that the shade plugin cannot relocate it. */
  private static final String BYTE_BUDDY_PACKAGE = String.join(".", "net", "bytebuddy", "");

  /** The agent's own ByteBuddy package: {@code net.bytebuddy.} unshaded, relocated in the jar. */
  private static final String AGENT_BYTE_BUDDY_PACKAGE = ByteBuddy.class.getPackageName() + ".";

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
        && !className.startsWith(AGENT_BYTE_BUDDY_PACKAGE)
        && !className.startsWith(BYTE_BUDDY_PACKAGE);
  }
}
