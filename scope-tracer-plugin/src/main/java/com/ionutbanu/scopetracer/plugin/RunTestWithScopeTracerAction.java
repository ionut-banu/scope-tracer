package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.BaseRunConfigurationAction;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/**
 * "Run '&lt;name&gt;' with Scope Tracer" in the run gutter icon's menu ({@code
 * RunLineMarkerExtraActions}) and the editor's and project view's context menu ({@code
 * RunContextGroupInner}), on a test class, method, package or directory (#64).
 *
 * <p>It is a {@link BaseRunConfigurationAction}, as the platform's own Run item is, so it offers
 * exactly the configurations the context offers, with the same chooser when there are several,
 * restricted to JUnit ones: on a plain class with a {@code main} method, or where nothing can be
 * run, it is not shown. A click runs the chosen configuration as Run would (an existing one, or a
 * new temporary one, added as Run adds it), but through {@link TracedRunStarter}, traced. Defined
 * in {@code scope-tracer-junit.xml}, which is loaded only with the bundled JUnit plugin.
 */
public final class RunTestWithScopeTracerAction extends BaseRunConfigurationAction {

  public RunTestWithScopeTracerAction() {
    super(
        () -> "Run with Scope Tracer",
        () -> "Run this test with the scope-tracer agent and open the trace when it ends",
        (Icon) null);
  }

  @Override
  protected boolean isEnabledFor(@NotNull RunConfiguration configuration) {
    return configuration instanceof JUnitConfiguration;
  }

  @Override
  protected void updatePresentation(
      @NotNull Presentation presentation,
      @NotNull String actionText,
      @NotNull ConfigurationContext context) {
    presentation.setText(
        actionText.isBlank() ? "Run with Scope Tracer" : "Run '" + actionText + "' with Scope Tracer",
        false);
  }

  /** As the platform's {@code RunContextAction.perform}, with the traced run at the end. */
  @Override
  protected void perform(
      @NotNull RunnerAndConfigurationSettings settings, @NotNull ConfigurationContext context) {
    var project = context.getProject();
    var runManager = RunManagerEx.getInstanceEx(project);
    ReadAction.nonBlocking(() -> findExisting(context))
        .finishOnUiThread(
            ModalityState.nonModal(),
            existing -> {
              if (project.isDisposed()) {
                return;
              }
              if (existing != settings && !runManager.hasSettings(settings)) {
                runManager.setTemporaryConfiguration(settings);
              }
              if (runManager.shouldSetRunConfigurationFromContext()) {
                runManager.setSelectedConfiguration(settings);
              }
              TracedRunStarter.runOrExplain(project, settings);
            })
        .submit(AppExecutorUtil.getAppExecutorService());
  }
}
