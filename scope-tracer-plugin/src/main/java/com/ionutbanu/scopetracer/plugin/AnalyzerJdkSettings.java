package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdk;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Candidate;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Choice;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Source;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The IntelliJ side of choosing the analyzer's JDK: the explicit setting, the JDKs the IDE knows
 * as {@link Candidate}s, and the notifications. The choice itself is {@link AnalyzerJdkChooser}'s,
 * in {@code :model}. The IDE's own JVM (JBR 25 in IntelliJ IDEA 2026.3) cannot load the analyzer's
 * JDK 27 classes, which is why it runs on another JDK at all.
 *
 * <p>Nothing here probes on the EDT: {@link #resolve} runs inside the analysis's background task,
 * and the settings page calls {@link #choose} from a pooled thread.
 */
final class AnalyzerJdkSettings {

  /**
   * The explicit choice: a {@code java} path, as 0.6.0 saved it. Its absence is "Automatic". The
   * key the plugin used before 0.3.0 is never read: the path saved there is for the JDK the
   * analyzer was built for then, which cannot load it now.
   */
  private static final String PROPERTY_KEY = "scopeTracer.jdk27JavaPath";

  private AnalyzerJdkSettings() {}

  /** The saved explicit {@code java} path as text, or empty for "Automatic". */
  static Optional<String> explicitJavaText() {
    var saved = PropertiesComponent.getInstance().getValue(PROPERTY_KEY);
    return saved == null || saved.isBlank() ? Optional.empty() : Optional.of(saved);
  }

  /**
   * The explicit {@code java} path, or empty for "Automatic" or a saved value that is not a valid
   * path (which the analysis still reports as a skipped explicit choice).
   */
  static Optional<Path> explicitJava() {
    try {
      return explicitJavaText().map(Path::of);
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }

  /** Saves the explicit {@code java} path; empty means "Automatic" and removes the key. */
  static void explicitJava(Optional<Path> java) {
    PropertiesComponent.getInstance().setValue(PROPERTY_KEY, java.map(Path::toString).orElse(null));
  }

  /** The probe every check uses: {@code java -version}, bounded, through {@code BoundedProcess}. */
  static Function<Path, String> probe() {
    return AnalyzerJdk.probe(Path.of(System.getProperty("java.io.tmpdir")));
  }

  /**
   * The JDK the patcher saw for a traced run, as a candidate. Read in the patcher, where {@code
   * JavaParameters.getJdk()} is known, and carried to the analysis when the run ends.
   */
  static @Nullable Candidate tracedRun(@Nullable Sdk sdk) {
    return sdk == null ? null : candidate(Source.TRACED_RUN, sdk);
  }

  /**
   * Chooses the analyzer's JDK, tells the user what it skipped, and returns its {@code java}, or
   * {@code null} when nothing is usable (an error notification with "Configure…" has then been
   * shown). Probes: call it off the EDT.
   */
  static @Nullable Path resolve(Project project, @Nullable Candidate tracedRun) {
    var choice = choose(project, tracedRun);
    if (choice.chosen().isEmpty()) {
      ScopeTracerNotifier.notifyWithConfigure(
          project,
          "Scope Tracer: no JDK for the analyzer",
          html(choice.failureLines()),
          NotificationType.ERROR);
      return null;
    }
    choice
        .explicitWarning()
        .ifPresent(
            warning ->
                ScopeTracerNotifier.notifyWithConfigure(
                    project,
                    "Scope Tracer: chosen JDK skipped",
                    StringUtil.escapeXmlEntities(warning),
                    NotificationType.WARNING));
    return choice.chosen().get().candidate().java();
  }

  /** Runs the chooser over the explicit setting and the JDKs the IDE knows. Probes. */
  static Choice choose(@Nullable Project project, @Nullable Candidate tracedRun) {
    return choose(project, tracedRun, explicitJavaText());
  }

  /** What "Automatic" would choose right now, for the settings page. Probes. */
  static Choice chooseAutomatic(@Nullable Project project) {
    return choose(project, null, Optional.empty());
  }

  private static Choice choose(
      @Nullable Project project, @Nullable Candidate tracedRun, Optional<String> explicit) {
    var inputs =
        ApplicationManager.getApplication()
            .runReadAction(
            (Computable<AnalyzerJdkChooser.Inputs>) () -> {
              var registered = registeredJdks();
              return new AnalyzerJdkChooser.Inputs(
                  explicit.map(java -> explicitCandidate(java, registered)),
                  Optional.ofNullable(tracedRun),
                  Optional.ofNullable(projectSdk(project)),
                  registered.stream().map(sdk -> candidate(Source.REGISTERED, sdk)).toList());
            });
    return AnalyzerJdkChooser.choose(inputs, probe());
  }

  /** The JDKs registered in the IDE (Project Structure → SDKs). */
  static List<Sdk> registeredJdks() {
    return List.copyOf(ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance()));
  }

  /** The {@code java} launcher of an SDK, or {@code null} when it has none or it is not a path. */
  static @Nullable Path javaOf(Sdk sdk) {
    var path = javaTextOf(sdk);
    try {
      return path == null ? null : Path.of(path);
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static @Nullable String javaTextOf(Sdk sdk) {
    if (!(sdk.getSdkType() instanceof JavaSdkType type) || sdk.getHomePath() == null) {
      return null;
    }
    return type.getVMExecutablePath(sdk);
  }

  /** What the page and the warning call an explicit {@code java}: its SDK's name, or the path. */
  static String nameOf(Path java, List<Sdk> registered) {
    return registered.stream()
        .filter(sdk -> java.equals(javaOf(sdk)))
        .map(Sdk::getName)
        .findFirst()
        .orElse(java.toString());
  }

  /** Opens Settings → Tools → Scope Tracer. */
  static void openSettings(@Nullable Project project) {
    ApplicationManager.getApplication()
        .invokeLater(
            () ->
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, ScopeTracerConfigurable.class));
  }

  private static Candidate explicitCandidate(String java, List<Sdk> registered) {
    var candidate = Candidate.of(Source.EXPLICIT, java, null, java, null);
    return candidate.java() == null
        ? candidate
        : new Candidate(Source.EXPLICIT, nameOf(candidate.java(), registered), null, candidate.java(), null);
  }

  private static @Nullable Candidate projectSdk(@Nullable Project project) {
    if (project == null || project.isDisposed()) {
      return null;
    }
    var sdk = ProjectRootManager.getInstance(project).getProjectSdk();
    return sdk == null || !(sdk.getSdkType() instanceof JavaSdkType)
        ? null
        : candidate(Source.PROJECT_SDK, sdk);
  }

  /**
   * One SDK as a candidate. Anything that goes wrong reading one SDK makes it a skipped candidate
   * with the reason, so one bad entry in the JDK table never stops the analysis.
   */
  private static Candidate candidate(Source source, Sdk sdk) {
    var name = String.valueOf(sdk.getName());
    String stored = null;
    try {
      stored = sdk.getVersionString();
      return Candidate.of(source, name, sdk.getHomePath(), javaTextOf(sdk), stored);
    } catch (ProcessCanceledException e) {
      throw e;
    } catch (RuntimeException e) {
      return Candidate.unreadable(source, name, stored, "could not be read (" + e + ")");
    }
  }

  private static String html(List<String> lines) {
    return String.join("<br>", lines.stream().map(StringUtil::escapeXmlEntities).toList());
  }
}
