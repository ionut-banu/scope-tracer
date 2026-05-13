package com.ionutbanu.scopetracer.agent;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Minimal logger for {@code [scope-tracer]}-prefixed user-facing messages emitted by the agent
 * itself.
 *
 * <p>The agent is loaded on the JVM bootstrap classloader and may run alongside a host application
 * that brings its own SLF4J binding. To avoid binding-version conflicts and noisy multi-binding
 * warnings, the agent does <b>not</b> go through SLF4J for its own diagnostics. Messages are
 * written to {@code System.err} with a fixed prefix so operators can grep for them. The host app's
 * own logging is untouched.
 *
 * <p>Levels:
 *
 * <ul>
 *   <li>{@code info} — always emitted. Used for one-off operational confirmations (e.g. "HTML
 *       report written to ...").
 *   <li>{@code warn} — always emitted, with full stack trace when a {@link Throwable} is supplied.
 *       Used for recoverable errors that did not crash the JVM (e.g. auto-HTML failure).
 *   <li>{@code debug} — gated on {@code -Dscopetracer.agent.verbose=true}; otherwise dropped.
 * </ul>
 */
final class AgentLog {

  private static final String PREFIX = "[scope-tracer] ";
  private static final boolean DEBUG = Boolean.getBoolean("scopetracer.agent.verbose");

  private AgentLog() {}

  static void info(String msg) {
    System.err.println(PREFIX + msg);
  }

  static void warn(String msg) {
    System.err.println(PREFIX + "WARN " + msg);
  }

  static void warn(String msg, Throwable t) {
    var sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    System.err.println(PREFIX + "WARN " + msg + System.lineSeparator() + sw);
  }

  static void debug(String msg) {
    if (DEBUG) System.err.println(PREFIX + "DEBUG " + msg);
  }
}
