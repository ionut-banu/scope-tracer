package com.ionutbanu.scopetracer.agent;

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
 *   <li>{@code warn} — always emitted, one line. Used for recoverable errors that did not crash the
 *       JVM (e.g. auto-HTML failure). A line that names an exception builds its reason with {@link
 *       #describe}, never a stack trace.
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

  /** Longest an escaped type name or message gets in {@link #describe}. */
  static final int MAX_PART = 300;

  /** Entries {@link #describe} prints: the throwable and three causes. */
  private static final int MAX_ENTRIES = 4;

  /**
   * The reason a WARN line gives for {@code t}: {@code Type: message}, then {@code ; caused by
   * Type: message} for each cause, at most four entries and then {@code ; caused by ...}. It never
   * throws, never prints a stack trace or suppressed exceptions, and never calls {@code toString()}
   * or {@code getLocalizedMessage()} except to leave out a message that only repeats its cause.
   *
   * <p>{@code Type} is the simple class name, or the binary name when that is empty. A {@code null}
   * or blank message is left out with its {@code : }. Types and messages are {@link #escape
   * escaped} and cut at {@value #MAX_PART} characters. The chain stops at a throwable already
   * printed. A {@code null} gives {@code unknown error}.
   */
  static String describe(Throwable t) {
    try {
      if (t == null) return "unknown error";
      var sb = new StringBuilder();
      var seen = new Throwable[MAX_ENTRIES];
      int n = 0;
      Throwable current = t;
      while (current != null) {
        if (isIn(seen, n, current)) break;
        if (n == MAX_ENTRIES) {
          sb.append("; caused by ...");
          break;
        }
        seen[n] = current;
        Throwable cause = null;
        try {
          cause = current.getCause();
        } catch (Throwable ignored) {
          // the chain stops after this entry
        }
        String message = null;
        try {
          message = current.getMessage();
        } catch (Throwable ignored) {
          // this entry is its type alone
        }
        if (n > 0) sb.append("; caused by ");
        sb.append(escape(typeOf(current), MAX_PART));
        if (message != null && !message.isBlank() && !repeatsCause(message, cause)) {
          sb.append(": ").append(escape(message, MAX_PART));
        }
        current = cause;
        n++;
      }
      return sb.toString();
    } catch (Throwable ignored) {
      return "unknown error";
    }
  }

  private static boolean isIn(Throwable[] seen, int count, Throwable t) {
    for (int i = 0; i < count; i++) {
      if (seen[i] == t) return true;
    }
    return false;
  }

  private static String typeOf(Throwable t) {
    Class<?> type = t.getClass();
    try {
      String simple = type.getSimpleName();
      if (!simple.isEmpty()) return simple;
    } catch (Throwable ignored) {
      // a malformed nested name: fall back to the binary name
    }
    return type.getName();
  }

  /**
   * Whether {@code message} is exactly {@code cause.toString()}, as {@code Throwable(Throwable)}.
   */
  private static boolean repeatsCause(String message, Throwable cause) {
    if (cause == null) return false;
    try {
      return message.equals(cause.toString());
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * {@code s} as one safe line: tab, CR and LF as {@code \t}, {@code \r}, {@code \n}, every other
   * control, format, unassigned, surrogate, separator or private-use code point as a backslash,
   * {@code u} and four hex digits, and cut after {@code max} characters with {@code ...} appended.
   * Pass {@link Integer#MAX_VALUE} to escape without cutting, as for a path.
   */
  static String escape(String s, int max) {
    var sb = new StringBuilder();
    int i = 0;
    while (i < s.length() && sb.length() < max) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      switch (cp) {
        case '\t' -> sb.append("\\t");
        case '\r' -> sb.append("\\r");
        case '\n' -> sb.append("\\n");
        default -> {
          int type = Character.getType(cp);
          boolean printable =
              !Character.isISOControl(cp)
                  && type != Character.FORMAT
                  && type != Character.UNASSIGNED
                  && type != Character.SURROGATE
                  && type != Character.LINE_SEPARATOR
                  && type != Character.PARAGRAPH_SEPARATOR
                  && type != Character.PRIVATE_USE;
          if (printable) sb.appendCodePoint(cp);
          else sb.append(String.format("\\u%04x", cp > 0xFFFF ? 0xFFFD : cp));
        }
      }
    }
    if (i < s.length()) sb.append("...");
    return sb.toString();
  }

  /** A path or other value in a WARN prefix: escaped, never cut. */
  static String path(Object value) {
    return escape(String.valueOf(value), Integer.MAX_VALUE);
  }

  static void debug(String msg) {
    Boolean v = verbose;
    if (v != null ? v : PROPERTY) System.err.println(PREFIX + "DEBUG " + msg);
  }
}
