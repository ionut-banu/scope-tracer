package com.ionutbanu.scopetracer.plugin.model;

import java.util.List;

/**
 * The build tool IntelliJ handed a "Run with Scope Tracer" run to instead of starting it itself
 * (#196). Such a run is started from a run profile the tool builds, which the program patcher is
 * never asked about, so it is not traced; {@link #notice()} says so and names the setting that
 * makes IntelliJ run it itself. No IntelliJ dependency, and no Gradle or Maven class is loaded:
 * the tool is told from the class names of the profile the platform reported.
 */
public enum DelegatedTool {
  GRADLE(
      "Gradle",
      "Settings | Build, Execution, Deployment | Build Tools | Gradle: set \"Build and run"
          + " using\" to \"IntelliJ IDEA\""),
  MAVEN(
      "Maven",
      "Settings | Build, Execution, Deployment | Build Tools | Maven | Runner: turn off"
          + " \"Delegate IDE build/run actions to Maven\""),
  OTHER("a build tool", "the run setting that delegates it to the build tool");

  private final String displayName;
  private final String setting;

  DelegatedTool(String displayName, String setting) {
    this.displayName = displayName;
    this.setting = setting;
  }

  /**
   * The notice for a delegated run. Fixed text: nothing from a configuration is in it, so it needs
   * no escaping as notification HTML.
   */
  public String notice() {
    return "IntelliJ runs this configuration with "
        + displayName
        + ", not itself, so this run is not traced. To trace it, change "
        + setting
        + ", then run it again with Scope Tracer.";
  }

  /**
   * Which tool a run profile belongs to, from the fully qualified names of its class and every
   * superclass (outermost first). Gradle's application and test runs use a profile in {@code
   * org.jetbrains.plugins.gradle} or the platform's external-system run configuration; Maven's, one
   * in {@code org.jetbrains.idea.maven}. Anything else is {@link #OTHER}.
   */
  public static DelegatedTool of(List<String> profileClassNames) {
    for (var name : profileClassNames) {
      if (name.startsWith("org.jetbrains.idea.maven.")) {
        return MAVEN;
      }
      if (name.startsWith("org.jetbrains.plugins.gradle.")) {
        return GRADLE;
      }
    }
    for (var name : profileClassNames) {
      if (name.endsWith(".ExternalSystemRunConfiguration")) {
        return GRADLE;
      }
    }
    return OTHER;
  }
}
