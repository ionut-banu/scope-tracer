package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.agent.util.Glob;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parsed agent arguments. Constructed via {@link #parse(String)} from the raw {@code -javaagent}
 * argument string. Unknown keys are ignored. Malformed values for typed keys (e.g. {@code
 * min.scopes=abc}, {@code sample.rate=abc}) fall back to the default and log a warning via {@link
 * AgentLog}.
 */
record AgentConfig(
    boolean verbose,
    boolean generateHtml,
    Path outputDir,
    String outputSuffix,
    int minScopes,
    CaptureFilter filter) {

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

    if (args != null) {
      for (String token : args.split(",")) {
        String[] kv = token.split("=", 2);
        String key = kv[0].trim();
        String value = kv.length > 1 ? kv[1].trim() : "";
        switch (key) {
          case "verbose" -> verbose = true;
          case "html" -> generateHtml = value.isEmpty() || !value.equalsIgnoreCase("false");
          case "output.dir" -> {
            if (!value.isEmpty()) outputDir = Path.of(value);
          }
          case "output.suffix" -> {
            if (!value.isEmpty()) outputSuffix = value.startsWith(".") ? value : "." + value;
          }
          case "min.scopes" -> {
            try {
              if (!value.isEmpty()) minScopes = Math.max(1, Integer.parseInt(value));
            } catch (NumberFormatException e) {
              AgentLog.warn("ignoring malformed min.scopes value: " + value);
            }
          }
          case "include.name" -> includeName = compileGlob(key, value, includeName);
          case "exclude.name" -> excludeName = compileGlob(key, value, excludeName);
          case "include.package" -> includePackage = compileGlob(key, value, includePackage);
          case "exclude.package" -> excludePackage = compileGlob(key, value, excludePackage);
          case "sample.rate" -> {
            try {
              if (!value.isEmpty()) {
                double r = Double.parseDouble(value);
                sampleRate = Math.max(0.0, Math.min(1.0, r));
              }
            } catch (NumberFormatException e) {
              AgentLog.warn("ignoring malformed sample.rate value: " + value);
            }
          }
          default -> {
            // ignore unknown keys for forward compatibility
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
          new CaptureFilter(
              includeName, excludeName, includePackage, excludePackage, sampleRate);
    }

    return new AgentConfig(verbose, generateHtml, outputDir, outputSuffix, minScopes, filter);
  }

  private static Pattern compileGlob(String key, String value, Pattern current) {
    if (value.isEmpty()) return current;
    try {
      return Glob.toRegex(value);
    } catch (IllegalArgumentException | PatternSyntaxException e) {
      AgentLog.warn("ignoring malformed " + key + " value: " + value);
      return current;
    }
  }
}
