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
 *   <li>{@code debug} — the verbose-only lines (why an auto-HTML report was skipped). Gated on the
 *       parsed {@code verbose} agent argument, set by {@code ScopeTracerAgent.start}; until then,
 *       and when the argument is absent or malformed, on {@code -Dscopetracer.agent.verbose=true}.
 *       {@code verbose=false} beats the property. Otherwise dropped.
 * </ul>
 */
final class AgentLog {

  private static final String PREFIX = "[scope-tracer] ";
  private static final boolean PROPERTY = Boolean.getBoolean("scopetracer.agent.verbose");

  /**
   * The parsed {@code verbose} value, or {@code null} until {@code start} sets it. Volatile: it is
   * read on the auto-HTML thread and on JFR's shutdown-hook thread.
   */
  private static volatile Boolean verbose;

  private AgentLog() {}

  /** Sets the debug gate from the parsed arguments; {@code null} goes back to the property. */
  static void setVerbose(Boolean value) {
    verbose = value;
  }

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
    Boolean v = verbose;
    if (v != null ? v : PROPERTY) System.err.println(PREFIX + "DEBUG " + msg);
  }
}
