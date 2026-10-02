package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the selected Java application run configuration with the scope-tracer agent attached and,
 * when the process exits, opens the resulting recording in the Scope Tracer tool window.
 */
public final class RunWithScopeTracerAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    var project = e.getProject();
    var selected = project == null ? null : RunManager.getInstance(project).getSelectedConfiguration();
    e.getPresentation()
        .setEnabledAndVisible(
            selected != null && selected.getConfiguration() instanceof ApplicationConfiguration);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    var project = e.getProject();
    if (project == null) {
      return;
    }
    var settings = RunManager.getInstance(project).getSelectedConfiguration();
    if (settings == null || !(settings.getConfiguration() instanceof ApplicationConfiguration)) {
      ScopeTracerNotifier.notify(
          project,
          "Scope Tracer",
          "Select a Java application run configuration first.",
          NotificationType.INFORMATION);
      return;
    }
    var profile = settings.getConfiguration();

    // The analyzer needs a JDK 26 `java`; resolve it now, on the EDT, because it may prompt.
    Path analyzerJava;
    Path jfrFile;
    try {
      analyzerJava = AnalyzerJdkSettings.resolveJavaPath();
      jfrFile = Files.createTempFile("scope-tracer-run-", ".jfr");
    } catch (IllegalStateException | IOException ex) {
      ScopeTracerNotifier.notify(
          project, "Scope Tracer", String.valueOf(ex.getMessage()), NotificationType.ERROR);
      return;
    }

    var pending = new TracedRuns.Pending(project, jfrFile, analyzerJava, new Path[1]);
    TracedRuns.register(profile, pending);

    var connection = project.getMessageBus().connect();
    connection.subscribe(
        ExecutionManager.EXECUTION_TOPIC,
        new ExecutionListener() {
          @Override
          public void processTerminated(
              @NotNull String executorId,
              @NotNull ExecutionEnvironment env,
              @NotNull ProcessHandler handler,
              int exitCode) {
            if (env.getRunProfile() != profile) {
              return;
            }
            connection.disconnect();
            var finished = TracedRuns.remove(profile);
            if (finished == null) {
              // Patching was refused or failed; the run was untraced and already reported.
              deleteQuietly(jfrFile);
              return;
            }
            if (finished.agentJar() != null) {
              BundledAgent.cleanUp(finished.agentJar());
            }
            openRecording(project, jfrFile, analyzerJava);
          }
        });

    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
  }

  private static void openRecording(Project project, Path jfrFile, Path analyzerJava) {
    try {
      if (!Files.exists(jfrFile) || Files.size(jfrFile) == 0) {
        ScopeTracerNotifier.notify(
            project,
            "Scope Tracer",
            "The run finished but wrote no recording, so there is nothing to show.",
            NotificationType.WARNING);
        deleteQuietly(jfrFile);
        return;
      }
    } catch (IOException ex) {
      ScopeTracerNotifier.notify(
          project, "Scope Tracer", String.valueOf(ex.getMessage()), NotificationType.ERROR);
      return;
    }
    com.intellij.openapi.application.ApplicationManager.getApplication()
        .invokeLater(
            () -> {
              var toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Scope Tracer");
              if (toolWindow == null) {
                deleteQuietly(jfrFile);
                return;
              }
              toolWindow.activate(
                  () -> {
                    var content = toolWindow.getContentManager().getContent(0);
                    if (content != null
                        && content.getComponent() instanceof ScopeTracerPanel panel) {
                      panel.analyzeRecording(jfrFile, analyzerJava, true);
                    } else {
                      deleteQuietly(jfrFile);
                    }
                  });
            });
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // temp file
    }
  }
}
