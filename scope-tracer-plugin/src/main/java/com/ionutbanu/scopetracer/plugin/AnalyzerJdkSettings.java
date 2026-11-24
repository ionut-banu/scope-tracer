package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.ui.Messages;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdk;
import com.ionutbanu.scopetracer.plugin.model.TracedLaunch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the path to a JDK 27 {@code java} launcher, used to run the bundled analyzer out of
 * process (the IDE's own JVM, JBR 25 in IntelliJ IDEA 2026.3, cannot load the analyzer's JDK 27
 * classes).
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

  /**
   * Runs {@code <java> -version}, bounded by {@link AnalyzerJdk#VERSION_CHECK_TIMEOUT}, and asks
   * {@link AnalyzerJdk} whether it can run the analyzer. A {@code java} that hangs is reported as
   * "could not determine the JDK version" after the timeout. This still runs on the EDT; moving it
   * off is #63's.
   */
  private static Optional<String> check(Path java) {
    return AnalyzerJdk.check(java, Path.of(System.getProperty("java.io.tmpdir")));
  }
}
