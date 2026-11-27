package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
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

  /** How long {@code java -version} may take before the JDK is reported as undeterminable. */
  public static final Duration VERSION_CHECK_TIMEOUT = Duration.ofSeconds(30);

  private AnalyzerJdk() {}

  /** The version check's command line: {@code <java> -version}. */
  public static List<String> versionCommand(Path java) {
    return List.of(java.toString(), "-version");
  }

  /**
   * Runs {@code command} (normally {@link #versionCommand}) through {@link BoundedProcess} and
   * returns what it printed on stdout and stderr together, or {@code ""} when it could not be
   * started, did not exit within {@code timeout}, or the thread was interrupted. {@code ""} makes
   * {@link #refusal} say the version could not be determined.
   */
  public static String versionOutput(List<String> command, Duration timeout, Path tempDir) {
    try {
      var result =
          BoundedProcess.run(
              command, BoundedProcess.Capture.STDOUT_AND_STDERR, timeout, () -> false, tempDir);
      return result instanceof BoundedProcess.Exited e ? e.output() : "";
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * The probe {@link AnalyzerJdkChooser} uses: from a {@code java} launcher to what its {@code
   * -version} printed, bounded by {@link #VERSION_CHECK_TIMEOUT}; {@code ""} when it could not be
   * read.
   */
  public static Function<Path, String> probe(Path tempDir) {
    return probe(tempDir, AnalyzerJdk::versionOutput);
  }

  /** {@link #probe(Path)} with another timeout. */
  public static Function<Path, String> probe(Path tempDir, Duration timeout) {
    return java -> versionOutput(versionCommand(java), timeout, tempDir);
  }

  /** Runs a version command: what {@link #versionOutput} does, replaceable by a test. */
  @FunctionalInterface
  interface VersionRunner {
    String run(List<String> command, Duration timeout, Path tempDir);
  }

  /** {@link #probe(Path)} through {@code runner}, so a test sees the timeout it is given. */
  static Function<Path, String> probe(Path tempDir, VersionRunner runner) {
    return java -> runner.run(versionCommand(java), VERSION_CHECK_TIMEOUT, tempDir);
  }

  /**
   * Runs {@code <java> -version}, bounded by {@link #VERSION_CHECK_TIMEOUT}, and returns why that
   * {@code java} cannot run the analyzer, or empty when it can.
   */
  public static Optional<String> check(Path java, Path tempDir) {
    return check(java, tempDir, AnalyzerJdk::versionOutput);
  }

  /** {@link #check(Path, Path)} through {@code runner}, so a test sees the timeout it is given. */
  static Optional<String> check(Path java, Path tempDir, VersionRunner runner) {
    return refusal(
        java.toString(), runner.run(versionCommand(java), VERSION_CHECK_TIMEOUT, tempDir));
  }

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
