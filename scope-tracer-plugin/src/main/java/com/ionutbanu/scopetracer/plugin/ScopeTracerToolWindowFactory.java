package com.ionutbanu.scopetracer.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class ScopeTracerToolWindowFactory implements ToolWindowFactory {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    var panel = new ScopeTracerPanel(project);
    var content = ContentFactory.getInstance().createContent(panel, "", false);
    toolWindow.getContentManager().addContent(content);
  }
}
