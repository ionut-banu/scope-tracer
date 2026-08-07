package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.ui.Messages;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the path to a JDK 26 {@code java} launcher, used to run the bundled analyzer out of
 * process (the IDE's own JVM, JBR, is JDK 21 and cannot load the analyzer's preview-tagged
 * classes). Milestone-1: a single manually-entered path, no Settings UI page yet.
 */
final class AnalyzerJdkSettings {

  private static final String PROPERTY_KEY = "scopeTracer.jdk26JavaPath";

  private AnalyzerJdkSettings() {}

  /** Returns the configured {@code java} executable path, prompting the user if unset. */
  static Path resolveJavaPath() {
    var props = PropertiesComponent.getInstance();
    var saved = props.getValue(PROPERTY_KEY);
    if (saved != null && Files.isExecutable(Path.of(saved))) {
      return Path.of(saved);
    }
    var input =
        Messages.showInputDialog(
            "Path to a JDK 26+ 'java' launcher (needed to run the scope-tracer analyzer):",
            "Scope Tracer: Configure JDK 26",
            Messages.getQuestionIcon(),
            saved == null ? "" : saved,
            null);
    if (input == null || input.isBlank()) {
      throw new IllegalStateException("No JDK 26 java path configured.");
    }
    var path = Path.of(input.trim());
    if (!Files.isExecutable(path)) {
      throw new IllegalStateException("Not an executable file: " + path);
    }
    props.setValue(PROPERTY_KEY, path.toString());
    return path;
  }
}
