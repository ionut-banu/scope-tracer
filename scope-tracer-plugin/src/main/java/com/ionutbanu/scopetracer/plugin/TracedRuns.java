package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.configurations.RunProfile;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle;
import java.io.IOException;
import java.nio.file.Files;
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
    private final Path jfrFile;
    private final Path analyzerJava;
    private volatile MessageBusConnection connection;
    private volatile Path agentJar;

    Active(Project project, Path jfrFile, Path analyzerJava) {
      this.project = project;
      this.jfrFile = jfrFile;
      this.analyzerJava = analyzerJava;
    }

    TracedRunLifecycle lifecycle() {
      return lifecycle;
    }

    Project project() {
      return project;
    }

    Path jfrFile() {
      return jfrFile;
    }

    void connection(MessageBusConnection connection) {
      this.connection = connection;
    }

    void agentJar(Path agentJar) {
      this.agentJar = agentJar;
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
        case JFR_FILE -> deleteQuietly(active.jfrFile);
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
      RunWithScopeTracerAction.openRecording(active.project, active.jfrFile, active.analyzerJava);
    }
  }

  static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // temp file
    }
  }
}
