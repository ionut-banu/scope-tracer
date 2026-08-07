package com.ionutbanu.scopetracer.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenTraceReportAction extends AnAction {

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    var project = e.getProject();
    if (project == null) {
      return;
    }
    var toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Scope Tracer");
    if (toolWindow == null) {
      return;
    }
    toolWindow.activate(
        () -> {
          var content = toolWindow.getContentManager().getContent(0);
          if (content != null && content.getComponent() instanceof ScopeTracerPanel panel) {
            panel.openAndAnalyze();
          }
        });
  }
}
