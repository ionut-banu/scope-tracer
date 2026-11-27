package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.ionutbanu.scopetracer.plugin.model.DelegatedTool;
import com.ionutbanu.scopetracer.plugin.model.RunActionState;
import com.ionutbanu.scopetracer.plugin.model.TracedRunLifecycle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Starts one traced run of a configuration: the Tools menu entry ({@link
 * RunWithScopeTracerAction}) with the selected configuration, the gutter and editor context-menu
 * entries with the one their context offers. Both ask {@link RunActionState} first, and both end
 * here, so a JUnit run and an application run are traced the same way (#64).
 */
final class TracedRunStarter {

  private TracedRunStarter() {}

  /** The kind of {@code settings}, as {@link RunActionState#decide} takes it. */
  static RunActionState.Selection selectionOf(@Nullable RunnerAndConfigurationSettings settings) {
    if (settings == null) {
      return new RunActionState.Selection.None();
    }
    var configuration = settings.getConfiguration();
    if (configuration instanceof ApplicationConfiguration) {
      return new RunActionState.Selection.Application();
    }
    for (var kind : TracedConfigurationKind.EP_NAME.getExtensionList()) {
      var selection = kind.selectionOf(configuration);
      if (selection != null) {
        return selection;
      }
    }
    return new RunActionState.Selection.Other(settings.getName(), settings.getType().getDisplayName());
  }

  /**
   * Decides, then either explains (an information notification) or starts the traced run with the
   * platform's Run executor. On the EDT.
   */
  static void runOrExplain(Project project, @Nullable RunnerAndConfigurationSettings settings) {
    var decision = RunActionState.decide(true, selectionOf(settings));
    if (settings == null || !decision.runs()) {
      if (decision.explanation() != null) {
        // Notification content is HTML; a configuration name may contain '<' or '&'.
        ScopeTracerNotifier.notify(
            project,
            "Scope Tracer",
            StringUtil.escapeXmlEntities(decision.explanation()),
            NotificationType.INFORMATION);
      }
      return;
    }
    start(project, settings);
  }

  private static DelegatedTool toolOf(RunProfile runProfile) {
    var names = new ArrayList<String>();
    for (Class<?> type = runProfile.getClass(); type != null; type = type.getSuperclass()) {
      names.add(type.getName());
    }
    return DelegatedTool.of(names);
  }

  private static void start(Project project, RunnerAndConfigurationSettings settings) {
    var profile = settings.getConfiguration();

    // No JDK lookup here: the analyzer's JDK is chosen when the run ends, in the analysis's
    // background task, with the JDK the run itself used (seen by the patcher) first.
    Path recordingDirectory;
    try {
      recordingDirectory = TracedRunDirectories.create(project);
    } catch (IOException ex) {
      ScopeTracerNotifier.notify(
          project, "Scope Tracer", String.valueOf(ex.getMessage()), NotificationType.ERROR);
      return;
    }

    var active =
        new TracedRuns.Active(
            project, recordingDirectory, settings.getName(), settings.getUniqueID());
    var connection = project.getMessageBus().connect();
    active.connection(connection);
    // Every platform callback is forwarded to the lifecycle, which decides what it means.
    connection.subscribe(
        ExecutionManager.EXECUTION_TOPIC,
        new ExecutionListener() {
          private TracedRunLifecycle.Launch launch(String executorId, ExecutionEnvironment env) {
            return new TracedRunLifecycle.Launch(
                executorId, env.getExecutionId(), env.getRunProfile() == profile);
          }

          /**
           * The configuration's own launch is claimed; a Run launch of another profile before it
           * is how a run IntelliJ hands to Gradle or Maven shows up (#196), and ends the request
           * with a notice. The classes are only named, never loaded.
           */
          private void launchSeen(String executorId, ExecutionEnvironment env) {
            var launch = launch(executorId, env);
            var lifecycle = active.lifecycle();
            if (launch.sameConfiguration()) {
              TracedRuns.apply(profile, active, lifecycle.launchSeen(launch));
            } else {
              TracedRuns.apply(
                  profile,
                  active,
                  lifecycle.delegatedLaunchSeen(launch, toolOf(env.getRunProfile())));
            }
          }

          @Override
          public void processStartScheduled(
              @NotNull String executorId, @NotNull ExecutionEnvironment env) {
            launchSeen(executorId, env);
          }

          @Override
          public void processStarting(
              @NotNull String executorId, @NotNull ExecutionEnvironment env) {
            launchSeen(executorId, env);
          }

          @Override
          public void processStarted(
              @NotNull String executorId,
              @NotNull ExecutionEnvironment env,
              @NotNull ProcessHandler handler) {
            launchSeen(executorId, env);
          }

          @Override
          public void processNotStarted(
              @NotNull String executorId,
              @NotNull ExecutionEnvironment env,
              @Nullable Throwable cause) {
            TracedRuns.apply(profile, active, active.lifecycle().notStarted(launch(executorId, env)));
          }

          @Override
          public void processTerminated(
              @NotNull String executorId,
              @NotNull ExecutionEnvironment env,
              @NotNull ProcessHandler handler,
              int exitCode) {
            TracedRuns.apply(profile, active, active.lifecycle().terminated(launch(executorId, env)));
          }
        });

    // A repeated click replaces the earlier request: release it so it leaks nothing.
    var previous = TracedRuns.register(profile, active);
    if (previous != null) {
      var release = previous.lifecycle().abandon();
      if (!release.isNothing()) {
        TracedRuns.apply(profile, previous, release);
        ScopeTracerNotifier.notify(
            project,
            "Scope Tracer",
            "An earlier traced run of this configuration is replaced by this one; its trace will not be shown.",
            NotificationType.INFORMATION);
      }
    }

    try {
      ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
    } catch (RuntimeException ex) {
      TracedRuns.apply(profile, active, active.lifecycle().abandon());
      throw ex;
    }
  }
}
