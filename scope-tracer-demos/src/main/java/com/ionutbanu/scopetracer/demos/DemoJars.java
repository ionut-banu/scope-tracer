package com.ionutbanu.scopetracer.demos;

import java.util.List;

/**
 * Names the scope-tracer jars the live demos print, with the version of the jars that were built.
 *
 * <p>The version is the {@code Implementation-Version} of the demos jar's manifest, which the build
 * writes from {@code <project.version>}. When the demo runs from {@code target/classes} or an IDE
 * there is no manifest, the version is {@code null}, and the names carry {@code <VERSION>} instead,
 * with a line saying so.
 */
final class DemoJars {

  /** Printed in place of the version when the manifest has none. */
  static final String PLACEHOLDER = "<VERSION>";

  private final String version;

  DemoJars(String version) {
    this.version = version == null || version.isBlank() ? null : version;
  }

  /** The jars of the running demo: the version is read from the demos jar's own manifest. */
  static DemoJars ofRunningDemo() {
    return new DemoJars(DemoJars.class.getPackage().getImplementationVersion());
  }

  String analyzerJar() {
    return "scope-tracer-analyzer/target/scope-tracer-analyzer-" + shown() + "-executable.jar";
  }

  String agentJar() {
    return "scope-tracer-agent/target/scope-tracer-agent-" + shown() + "-agent.jar";
  }

  /** True when the version is known, so the printed names can be pasted as they are. */
  boolean versionKnown() {
    return version != null;
  }

  /** The lines a demo prints under its header: where paths start, and the version if unknown. */
  List<String> notes() {
    var notes = new java.util.ArrayList<String>();
    notes.add("Paths are relative to the repository root; run the commands from there.");
    if (!versionKnown()) {
      notes.add(
          "The version is unknown (no jar manifest: run from target/classes or an IDE), so jar"
              + " names show "
              + PLACEHOLDER
              + "; replace it with the project version.");
    }
    return notes;
  }

  private String shown() {
    return version == null ? PLACEHOLDER : version;
  }
}
