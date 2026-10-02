package com.ionutbanu.scopetracer.plugin.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which JVM arguments to add to a user's run configuration so the scope-tracer agent and
 * a JFR recording are attached, or why that must not happen. Pure logic with no IntelliJ
 * dependency so it runs as ordinary JUnit.
 */
public final class TracedLaunch {

  /** The oldest JDK the agent supports (spec §1: no JDK before 26). */
  public static final int MIN_JDK_MAJOR = 26;

  private TracedLaunch() {}

  /** Outcome of {@link #plan}: arguments to append, or a reason tracing cannot start. */
  public sealed interface Plan {
    /** Arguments to append after the configuration's own VM options, in this order. */
    record Apply(List<String> extraVmArgs) implements Plan {}

    /** Tracing must not start; {@code reason} is shown to the user as is. */
    record Refuse(String reason) implements Plan {}
  }

  /**
   * Plans the extra VM arguments for one traced run.
   *
   * @param existingVmArgs the run configuration's VM options, left unchanged by this method.
   * @param jdkMajor the major version of the JDK the run configuration uses, or {@code -1} if it
   *     could not be determined.
   * @param agentJar the agent jar bundled with the plugin.
   * @param jfrFile where the recording should be written.
   */
  public static Plan plan(
      List<String> existingVmArgs, int jdkMajor, Path agentJar, Path jfrFile) {
    if (jdkMajor < MIN_JDK_MAJOR) {
      return new Plan.Refuse(
          jdkMajor < 0
              ? "Could not determine the JDK version of this run configuration; scope-tracer needs JDK "
                  + MIN_JDK_MAJOR
                  + " or newer."
              : "This run configuration uses JDK "
                  + jdkMajor
                  + "; scope-tracer needs JDK "
                  + MIN_JDK_MAJOR
                  + " or newer.");
    }
    for (String arg : existingVmArgs) {
      if (arg.startsWith("-javaagent:") && arg.contains("scope-tracer-agent")) {
        return new Plan.Refuse(
            "This run configuration already attaches the scope-tracer agent; running it again would emit every event twice.");
      }
      if (arg.startsWith("-XX:StartFlightRecording")) {
        return new Plan.Refuse(
            "This run configuration already starts its own JFR recording, which would collide with the one scope-tracer adds.");
      }
    }
    var extra = new ArrayList<String>();
    extra.add("-javaagent:" + agentJar);
    if (!existingVmArgs.contains("--enable-preview")) {
      extra.add("--enable-preview");
    }
    extra.add("-XX:StartFlightRecording=filename=" + jfrFile + ",dumponexit=true");
    return new Plan.Apply(List.copyOf(extra));
  }

  private static final Pattern VERSION = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

  /**
   * Finds the major version in a JDK version string, which may be bare or embedded in text:
   * {@code "26"}, {@code "26.0.2"}, {@code "27-ea"}, {@code "1.8.0_292"}, {@code "java version
   * \"26\""}. Returns {@code -1} when there is no number.
   */
  public static int parseJavaMajor(String version) {
    if (version == null) return -1;
    Matcher m = VERSION.matcher(version);
    if (!m.find()) return -1;
    try {
      int first = Integer.parseInt(m.group(1));
      if (first == 1 && m.group(2) != null) return Integer.parseInt(m.group(2));
      return first;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
