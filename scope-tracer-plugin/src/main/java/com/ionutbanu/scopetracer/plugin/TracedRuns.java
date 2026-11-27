package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.configurations.RunProfile;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser;
import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands a "Run with Scope Tracer" request from the action to {@link ScopeTracerProgramPatcher},
 * which only sees the run profile, and carries out the releases that {@link TracedRunLifecycle}
 * decides on. All decisions are in the lifecycle; this class only holds the resources and frees
 * them.
 */
final class TracedRuns {

  /** Everything the patcher and the lifecycle callbacks need for one request. */
  static final class Active {
    private final TracedRunLifecycle lifecycle = new TracedRunLifecycle();
    private final Project project;
    private final Path recordingDirectory;
    private final String configurationName;
    private final String configurationKey;
    private volatile MessageBusConnection connection;
    private volatile Path agentJar;
    private volatile AnalyzerJdkChooser.Candidate tracedRunJdk;

    /**
     * @param recordingDirectory the run's own new temp directory, which every JVM of the run
     *     writes its recording into.
     * @param configurationName the configuration's name, for the tabs of a run that leaves several
     *     recordings.
     * @param configurationKey identifies the configuration across runs, so a new run's tabs
     *     replace the previous run's.
     */
    Active(
        Project project, Path recordingDirectory, String configurationName, String configurationKey) {
      this.project = project;
      this.recordingDirectory = recordingDirectory;
      this.configurationName = configurationName;
      this.configurationKey = configurationKey;
    }

    TracedRunLifecycle lifecycle() {
      return lifecycle;
    }

    Project project() {
      return project;
    }

    Path recordingDirectory() {
      return recordingDirectory;
    }

    void connection(MessageBusConnection connection) {
      this.connection = connection;
    }

    void agentJar(Path agentJar) {
      this.agentJar = agentJar;
    }

    /**
     * The JDK the traced run used, seen by the patcher; the analyzer's first automatic choice
     * when the run ends.
     */
    void tracedRunJdk(AnalyzerJdkChooser.Candidate tracedRunJdk) {
      this.tracedRunJdk = tracedRunJdk;
    }
  }

  private static final Map<RunProfile, Active> ACTIVE = new ConcurrentHashMap<>();

  private TracedRuns() {}

  /** Registers a request, returning the one it replaces (or {@code null}). */
  static Active register(RunProfile profile, Active active) {
    return ACTIVE.put(profile, active);
  }

  static Active get(RunProfile profile) {
    return ACTIVE.get(profile);
  }

  /** Carries out a release the lifecycle asked for. */
  static void apply(RunProfile profile, Active active, TracedRunLifecycle.Release release) {
    if (release.isNothing()) {
      return;
    }
    ACTIVE.remove(profile, active);
    for (var resource : release.resources()) {
      switch (resource) {
        case SUBSCRIPTION -> {
          var connection = active.connection;
          if (connection != null) {
            connection.disconnect();
          }
        }
        case RECORDING_DIRECTORY -> TracedRunDirectories.delete(active.project, active.recordingDirectory);
        case AGENT_JAR -> {
          var agentJar = active.agentJar;
          if (agentJar != null) {
            BundledAgent.cleanUp(agentJar);
          }
        }
      }
    }
    if (release.notice() != null) {
      ScopeTracerNotifier.notify(
          active.project, "Scope Tracer", release.notice(), NotificationType.WARNING);
    }
    if (release.openRecording()) {
      TracedRunRecordings.open(
          active.project,
          active.recordingDirectory,
          active.tracedRunJdk,
          active.configurationName,
          active.configurationKey);
    }
  }
}
