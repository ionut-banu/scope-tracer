package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.ui.Messages;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdk;
import com.ionutbanu.scopetracer.plugin.model.TracedLaunch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Persists the path to a JDK 27 {@code java} launcher, used to run the bundled analyzer out of
 * process (the IDE's own JVM, JBR, is JDK 21 and cannot load the analyzer's JDK 27 classes).
 * Milestone-1: a single manually-entered path, no Settings UI page yet.
 *
 * <p>Every path, saved or entered, is checked with {@code java -version} through {@link
 * AnalyzerJdk}, so a {@code java} older than 27 is refused with a message naming the JDK needed
 * instead of failing later with the analyzer's raw {@code UnsupportedClassVersionError}.
 */
final class AnalyzerJdkSettings {

  /**
   * Named for the JDK the analyzer needs. The key the plugin used before 0.3.0 is never read: the
   * path saved there is for the JDK the analyzer was built for then, which cannot load it now.
   */
  private static final String PROPERTY_KEY = "scopeTracer.jdk27JavaPath";

  private AnalyzerJdkSettings() {}

  /** Returns the configured {@code java} executable path, prompting the user if unset or unfit. */
  static Path resolveJavaPath() {
    var props = PropertiesComponent.getInstance();
    var saved = props.getValue(PROPERTY_KEY);
    String problem = null;
    if (saved != null && Files.isExecutable(Path.of(saved))) {
      var refusal = check(Path.of(saved));
      if (refusal.isEmpty()) {
        return Path.of(saved);
      }
      problem = refusal.get();
    }
    var prompt =
        "Path to a JDK "
            + TracedLaunch.MIN_JDK_MAJOR
            + "+ 'java' launcher (needed to run the scope-tracer analyzer):";
    var input =
        Messages.showInputDialog(
            problem == null ? prompt : problem + "\n\n" + prompt,
            "Scope Tracer: Configure JDK " + TracedLaunch.MIN_JDK_MAJOR,
            Messages.getQuestionIcon(),
            saved == null ? "" : saved,
            null);
    if (input == null || input.isBlank()) {
      throw new IllegalStateException(
          "No JDK " + TracedLaunch.MIN_JDK_MAJOR + " java path configured.");
    }
    var path = Path.of(input.trim());
    if (!Files.isExecutable(path)) {
      throw new IllegalStateException("Not an executable file: " + path);
    }
    var refusal = check(path);
    if (refusal.isPresent()) {
      throw new IllegalStateException(refusal.get());
    }
    props.setValue(PROPERTY_KEY, path.toString());
    return path;
  }

  /** Runs {@code <java> -version} and asks {@link AnalyzerJdk} whether it can run the analyzer. */
  private static Optional<String> check(Path java) {
    return AnalyzerJdk.refusal(java.toString(), versionOutput(java));
  }

  private static String versionOutput(Path java) {
    try {
      var process =
          new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true).start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return "";
      }
      return output;
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }
}
