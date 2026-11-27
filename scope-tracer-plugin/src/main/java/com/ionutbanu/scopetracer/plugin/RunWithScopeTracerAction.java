package com.ionutbanu.scopetracer.plugin;

import com.intellij.execution.RunManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.ionutbanu.scopetracer.plugin.model.RunActionState;
import org.jetbrains.annotations.NotNull;

/**
 * Tools → "Run with Scope Tracer": runs the selected Java Application or JUnit run configuration
 * with the scope-tracer agent attached and, when the run ends, opens its recordings in the Scope
 * Tracer tool window. Shown whenever a project is open; {@link RunActionState} decides whether a
 * click runs or explains why it cannot. The gutter and editor context-menu entry for tests is
 * {@code RunTestWithScopeTracerAction}; both start the run through {@link TracedRunStarter}.
 */
public final class RunWithScopeTracerAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    var project = e.getProject();
    var decision =
        RunActionState.decide(
            project != null,
            TracedRunStarter.selectionOf(
                project == null ? null : RunManager.getInstance(project).getSelectedConfiguration()));
    e.getPresentation().setVisible(decision.visible());
    e.getPresentation().setEnabled(decision.enabled());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    var project = e.getProject();
    if (project == null) {
      return;
    }
    TracedRunStarter.runOrExplain(
        project, RunManager.getInstance(project).getSelectedConfiguration());
  }
}
