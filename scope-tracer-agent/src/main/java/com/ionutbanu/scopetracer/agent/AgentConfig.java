package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.agent.util.Glob;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parsed agent arguments. Constructed via {@link #parse(String)} from the raw {@code -javaagent}
 * argument string. An unknown key (keys are case-sensitive), a key with no name, a boolean key
 * ({@code record}, {@code html}, {@code verbose}) with a value that is not {@code true} or {@code
 * false}, a key that needs a value and has none, and a number out of range or malformed each log
 * one warning via {@link AgentLog} and are otherwise ignored or clamped; the other arguments still
 * apply. Parsing never throws: {@code premain} must not.
 *
 * @param record whether the agent starts its own recording ({@code record}, {@code record=true} or
 *     {@code record=<path>}).
 * @param recordPath where that recording goes as given, or {@code null} for the default file name
 *     in the working directory; resolved by {@link AgentRecording#resolveDestination}.
 */
record AgentConfig(
    boolean verbose,
    boolean generateHtml,
    Path outputDir,
    String outputSuffix,
    int minScopes,
    CaptureFilter filter,
    boolean record,
    Path recordPath) {

  /** Every key the agent understands; the unknown-key check and its warning are built from it. */
  static final List<String> KNOWN_KEYS =
      List.of(
          "record",
          "html",
          "verbose",
          "output.dir",
          "output.suffix",
          "min.scopes",
          "include.name",
          "exclude.name",
          "include.package",
          "exclude.package",
          "sample.rate");

  /**
   * A warning never repeats more of an argument than this, so a huge value stays one short line.
   */
  private static final int MAX_SHOWN = 100;

  static AgentConfig parse(String args) {
    boolean verbose = Boolean.getBoolean("scopetracer.agent.verbose");
    boolean generateHtml = true;
    Path outputDir = null;
    String outputSuffix = ".html";
    int minScopes = 1;
    Pattern includeName = null;
    Pattern excludeName = null;
    Pattern includePackage = null;
    Pattern excludePackage = null;
    double sampleRate = 1.0;
    boolean record = false;
    Path recordPath = null;

    if (args != null) {
      for (String raw : args.split(",")) {
        String token = raw.trim();
        if (token.isEmpty()) continue;
        String[] kv = token.split("=", 2);
        String key = kv[0].trim();
        String value = kv.length > 1 ? kv[1].trim() : "";
        if (key.isEmpty()) {
          if (!value.isEmpty()) {
            AgentLog.warn("ignoring agent argument with no key: '" + shown(token) + "'");
          }
          continue;
        }
        if (!KNOWN_KEYS.contains(key)) {
          AgentLog.warn(
              "ignoring unknown agent argument '"
                  + shown(key)
                  + "'; known arguments: "
                  + String.join(", ", KNOWN_KEYS));
          continue;
        }
        switch (key) {
          case "verbose" -> verbose = bool(key, value, verbose);
          case "html" -> generateHtml = bool(key, value, generateHtml);
          case "output.dir" -> {
            if (needsValue(key, value)) outputDir = toPath(key, value, outputDir);
          }
          case "output.suffix" -> {
            if (needsValue(key, value)) {
              outputSuffix = value.startsWith(".") ? value : "." + value;
            }
          }
          case "min.scopes" -> {
            if (needsValue(key, value)) {
              try {
                int n = Integer.parseInt(value);
                if (n < 1) AgentLog.warn("min.scopes must be at least 1, using 1");
                minScopes = Math.max(1, n);
              } catch (NumberFormatException e) {
                AgentLog.warn("ignoring malformed min.scopes value: " + shown(value));
              }
            }
          }
          case "include.name" -> includeName = compileGlob(key, value, includeName);
          case "exclude.name" -> excludeName = compileGlob(key, value, excludeName);
          case "include.package" -> includePackage = compileGlob(key, value, includePackage);
          case "exclude.package" -> excludePackage = compileGlob(key, value, excludePackage);
          case "sample.rate" -> {
            if (needsValue(key, value)) {
              try {
                double r = Double.parseDouble(value);
                if (Double.isNaN(r)) {
                  AgentLog.warn("ignoring malformed sample.rate value: " + shown(value));
                } else {
                  sampleRate = Math.max(0.0, Math.min(1.0, r));
                  if (r < 0.0 || r > 1.0) {
                    AgentLog.warn("sample.rate must be between 0.0 and 1.0, using " + sampleRate);
                  }
                }
              } catch (NumberFormatException e) {
                AgentLog.warn("ignoring malformed sample.rate value: " + shown(value));
              }
            }
          }
          case "record" -> {
            if (value.isEmpty() || value.equalsIgnoreCase("true")) {
              record = true;
              recordPath = null;
            } else if (value.equalsIgnoreCase("false")) {
              record = false;
              recordPath = null;
            } else {
              Path path = toPath(key, value, null);
              record = path != null;
              recordPath = path;
            }
          }
          default -> {
            // unreachable: every key in KNOWN_KEYS has a case above
          }
        }
      }
    }

    CaptureFilter filter;
    if (includeName == null
        && excludeName == null
        && includePackage == null
        && excludePackage == null
        && sampleRate >= 1.0) {
      filter = CaptureFilter.PASSTHROUGH;
    } else {
      filter =
          new CaptureFilter(includeName, excludeName, includePackage, excludePackage, sampleRate);
    }

    return new AgentConfig(
        verbose, generateHtml, outputDir, outputSuffix, minScopes, filter, record, recordPath);
  }

  /** {@code true} or {@code false}, ignoring case; a bare key or an empty value is {@code true}. */
  private static boolean bool(String key, String value, boolean current) {
    if (value.isEmpty() || value.equalsIgnoreCase("true")) return true;
    if (value.equalsIgnoreCase("false")) return false;
    AgentLog.warn(
        "ignoring malformed " + key + " value: '" + shown(value) + "' (expected true or false)");
    return current;
  }

  /** Whether {@code value} is present; warns when it is not, for a key that needs one. */
  private static boolean needsValue(String key, String value) {
    if (!value.isEmpty()) return true;
    AgentLog.warn("ignoring " + key + " with no value");
    return false;
  }

  /**
   * {@code s} as one safe line: control and other non-printable characters escaped, and cut short.
   */
  static String shown(String s) {
    var sb = new StringBuilder();
    int i = 0;
    while (i < s.length() && sb.length() < MAX_SHOWN) {
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

  private static Path toPath(String key, String value, Path current) {
    try {
      return Path.of(value);
    } catch (InvalidPathException e) {
      AgentLog.warn(
          "ignoring "
              + key
              + " value that is not a valid path: "
              + shown(String.valueOf(e.getReason())));
      return current;
    }
  }

  private static Pattern compileGlob(String key, String value, Pattern current) {
    if (!needsValue(key, value)) return current;
    try {
      return Glob.toRegex(value);
    } catch (IllegalArgumentException e) {
      AgentLog.warn("ignoring malformed " + key + " value: " + shown(value));
      return current;
    }
  }
}
