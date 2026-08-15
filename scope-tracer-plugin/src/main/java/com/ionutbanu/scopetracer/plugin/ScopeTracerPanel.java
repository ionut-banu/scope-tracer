package com.ionutbanu.scopetracer.plugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.ionutbanu.scopetracer.plugin.model.PluginScopeRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskOutcome;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.NotNull;

/** Flat task table for a parsed trace: one row per scope header, indented rows per task. */
final class ScopeTracerPanel extends JPanel {

  private final Project project;
  private final DefaultTableModel tableModel =
      new DefaultTableModel(
          new Object[] {"Scope / Task", "Thread", "Fork Offset", "Duration", "Outcome"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
  private final JBTable table = new JBTable(tableModel);

  ScopeTracerPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    var toolbar = new JToolBar();
    toolbar.setFloatable(false);
    var openButton = new JButton("Open .jfr…");
    openButton.addActionListener(e -> openAndAnalyze());
    toolbar.add(openButton);

    add(toolbar, BorderLayout.NORTH);
    add(new JBScrollPane(table), BorderLayout.CENTER);
  }

  void openAndAnalyze() {
    var descriptor = FileChooserDescriptorFactory.singleFile().withExtensionFilter("jfr");
    var file = FileChooser.chooseFile(descriptor, project, null);
    if (file == null) {
      return;
    }
    var jfrPath = Path.of(file.getPath());

    // Resolved on the EDT (this method is only ever invoked from EDT event handlers) because
    // it may pop a modal input dialog; Task.Backgroundable.run() executes on a pooled
    // background thread, where showing UI throws "Access is allowed from EDT only".
    Path javaPath;
    try {
      javaPath = AnalyzerJdkSettings.resolveJavaPath();
    } catch (IllegalStateException e) {
      showError(e);
      return;
    }
    var resolvedJavaPath = javaPath;

    new Task.Backgroundable(project, "Analyzing scope-tracer recording", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          var model = AnalyzerProcessRunner.analyze(jfrPath, resolvedJavaPath);
          SwingUtilities.invokeLater(() -> populate(model));
        } catch (Exception e) {
          SwingUtilities.invokeLater(() -> showError(e));
        }
      }
    }.queue();
  }

  private void populate(PluginTraceModel model) {
    tableModel.setRowCount(0);
    for (PluginScopeRecord scope : model.scopes()) {
      tableModel.addRow(new Object[] {scope.name(), scope.ownerThreadName(), "", "", ""});
      for (PluginTaskRecord task : scope.tasks()) {
        tableModel.addRow(
            new Object[] {
              "    #" + task.taskId() + " " + emptyToDash(task.taskName()),
              task.threadName(),
              formatOffset(scope.openTime(), task.forkTime()),
              formatDuration(task.forkTime(), task.completionTime()),
              formatOutcome(task.outcome())
            });
      }
    }
  }

  private void showError(Exception e) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Scope Tracer")
        .createNotification(
            "Scope Tracer analysis failed", String.valueOf(e.getMessage()), NotificationType.ERROR)
        .notify(project);
  }

  private static String emptyToDash(String s) {
    return (s == null || s.isBlank()) ? "-" : s;
  }

  private static String formatOffset(Instant scopeOpen, Instant forkTime) {
    if (scopeOpen == null || forkTime == null) {
      return "-";
    }
    return "+" + Duration.between(scopeOpen, forkTime).toMillis() + "ms";
  }

  private static String formatDuration(Instant start, Instant end) {
    if (start == null || end == null) {
      return "-";
    }
    return Duration.between(start, end).toMillis() + "ms";
  }

  private static String formatOutcome(PluginTaskOutcome outcome) {
    return switch (outcome) {
      case null -> "incomplete";
      case PluginTaskOutcome.Success s -> "success";
      case PluginTaskOutcome.Cancelled c -> "cancelled";
      case PluginTaskOutcome.Failed f -> "failed: " + f.exceptionType();
    };
  }
}
