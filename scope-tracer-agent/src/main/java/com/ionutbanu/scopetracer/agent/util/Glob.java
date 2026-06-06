package com.ionutbanu.scopetracer.agent.util;

import java.util.regex.Pattern;

/**
 * Translates a glob pattern into a {@link Pattern} anchored to match the whole input string.
 *
 * <p>Glob dialect:
 *
 * <ul>
 *   <li>{@code *} or {@code **} — matches any sequence of characters (including dots and any other
 *       punctuation). Adjacent asterisks are collapsed.
 *   <li>{@code ?} — matches exactly one character.
 *   <li>All other characters are matched literally; regex metacharacters are escaped.
 * </ul>
 *
 * <p>This is intentionally <b>not</b> path-segment aware: {@code com.acme.*} matches both {@code
 * com.acme.foo} and {@code com.acme.foo.bar}. Scope names and package names have no canonical
 * separator in the agent's filter API, so a single dialect is used for both. Operators who want a
 * strict prefix should write a literal pattern (e.g. {@code com.acme.checkout}).
 *
 * <p>This class must be accessible from the bootstrap classloader (it is included in the agent
 * fat-jar listed on {@code Boot-Class-Path}).
 */
public final class Glob {

  private Glob() {}

  /**
   * Compiles a glob pattern into an anchored {@link Pattern}.
   *
   * @param glob non-null, non-empty glob pattern.
   * @return a compiled {@code Pattern} that matches the entire input string.
   * @throws IllegalArgumentException if {@code glob} is null or empty.
   */
  public static Pattern toRegex(String glob) {
    if (glob == null || glob.isEmpty()) {
      throw new IllegalArgumentException("glob must be non-null and non-empty");
    }
    var sb = new StringBuilder(glob.length() + 4);
    sb.append('^');
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      switch (c) {
        case '*' -> {
          sb.append(".*");
          while (i + 1 < glob.length() && glob.charAt(i + 1) == '*') i++;
        }
        case '?' -> sb.append('.');
        default -> {
          if (isRegexMeta(c)) sb.append('\\');
          sb.append(c);
        }
      }
      i++;
    }
    sb.append('$');
    return Pattern.compile(sb.toString());
  }

  private static boolean isRegexMeta(char c) {
    return switch (c) {
      case '.', '^', '$', '|', '(', ')', '[', ']', '{', '}', '\\', '+' -> true;
      default -> false;
    };
  }
}
