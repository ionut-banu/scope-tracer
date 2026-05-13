package com.ionutbanu.scopetracer.agent;

import java.nio.file.Path;

/**
 * Parsed agent arguments. Constructed via {@link #parse(String)} from the raw {@code -javaagent}
 * argument string. Unknown keys are ignored. Malformed values for typed keys (e.g. {@code
 * min.scopes=abc}) fall back to the default and log a warning via {@link AgentLog}.
 */
record AgentConfig(
    boolean verbose, boolean generateHtml, Path outputDir, String outputSuffix, int minScopes) {

  static AgentConfig parse(String args) {
    boolean verbose = Boolean.getBoolean("scopetracer.agent.verbose");
    boolean generateHtml = true;
    Path outputDir = null;
    String outputSuffix = ".html";
    int minScopes = 1;

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
          default -> {
            // ignore unknown keys for forward compatibility
          }
        }
      }
    }

    return new AgentConfig(verbose, generateHtml, outputDir, outputSuffix, minScopes);
  }
}
