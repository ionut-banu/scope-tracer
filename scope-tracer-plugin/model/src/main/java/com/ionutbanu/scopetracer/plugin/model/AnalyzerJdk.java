package com.ionutbanu.scopetracer.plugin.model;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides whether a configured {@code java} launcher can run the bundled analyzer, from what that
 * launcher prints for {@code java -version}. The analyzer jar is compiled for {@link
 * TracedLaunch#MIN_JDK_MAJOR}, and an older {@code java} fails with a raw {@code
 * UnsupportedClassVersionError}; this check says which JDK is needed instead. Pure logic with no
 * IntelliJ dependency so it runs as ordinary JUnit.
 */
public final class AnalyzerJdk {

  /** The quoted version in a {@code java -version} line: {@code openjdk version "27.0.1" ...}. */
  private static final Pattern VERSION_LINE = Pattern.compile("version \"([^\"]+)\"");

  private AnalyzerJdk() {}

  /**
   * Finds the major version in the output of {@code java -version} (stderr and stdout together).
   * Lines before the version line, such as {@code Picked up JAVA_TOOL_OPTIONS: -Xmx1g}, are
   * skipped. Returns {@code -1} when there is no version line.
   */
  public static int parseVersionOutput(String output) {
    if (output == null) return -1;
    var m = VERSION_LINE.matcher(output);
    return m.find() ? TracedLaunch.parseJavaMajor(m.group(1)) : -1;
  }

  /**
   * Returns why the {@code java} at {@code javaPath} cannot run the analyzer, or empty when it can.
   *
   * @param javaPath the configured launcher, named in the message.
   * @param versionOutput what {@code <javaPath> -version} printed.
   */
  public static Optional<String> refusal(String javaPath, String versionOutput) {
    int major = parseVersionOutput(versionOutput);
    if (major >= TracedLaunch.MIN_JDK_MAJOR) {
      return Optional.empty();
    }
    var needed = "the scope-tracer analyzer needs JDK " + TracedLaunch.MIN_JDK_MAJOR + " or newer.";
    return Optional.of(
        major < 0
            ? "Could not determine the JDK version of " + javaPath + "; " + needed
            : javaPath + " is JDK " + major + "; " + needed);
  }
}
