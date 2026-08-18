package com.ionutbanu.scopetracer.plugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.ionutbanu.scopetracer.plugin.model.CallSiteParser;
import com.ionutbanu.scopetracer.plugin.model.PluginScopeRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskOutcome;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.NotNull;

/** Flat task table for a parsed trace: one row per scope header, indented rows per task. */
final class ScopeTracerPanel extends JPanel {

  private static final Logger LOG = Logger.getInstance(ScopeTracerPanel.class);

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

  // Parallel to tableModel's rows: the raw scope/task name behind each row, used for
  // click-to-source navigation. Explicit scope names (e.g. "order-processing-ORD-001") and
  // unlabeled tasks won't parse as a call site, so double-click on those rows is a no-op.
  private final List<String> rowRawNames = new ArrayList<>();

  ScopeTracerPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    var toolbar = new JToolBar();
    toolbar.setFloatable(false);
    var openButton = new JButton("Open .jfr…");
    openButton.addActionListener(e -> openAndAnalyze());
    toolbar.add(openButton);

    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2) {
              return;
            }
            var row = table.rowAtPoint(e.getPoint());
            if (row < 0 || row >= rowRawNames.size()) {
              LOG.info("Scope Tracer: double-click at row " + row + " — out of range, ignoring");
              return;
            }
            var rawName = rowRawNames.get(row);
            var callSite = CallSiteParser.parse(rawName);
            LOG.info(
                "Scope Tracer: double-click row " + row + " rawName=" + rawName + " -> " + callSite);
            if (callSite != null) {
              TaskSourceNavigator.navigate(project, table, callSite);
            }
          }
        });

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
    rowRawNames.clear();
    for (PluginScopeRecord scope : model.scopes()) {
      tableModel.addRow(new Object[] {scope.name(), scope.ownerThreadName(), "", "", ""});
      rowRawNames.add(scope.name());
      for (PluginTaskRecord task : scope.tasks()) {
        tableModel.addRow(
            new Object[] {
              "    #" + task.taskId() + " " + emptyToDash(task.taskName()),
              task.threadName(),
              formatOffset(scope.openTime(), task.forkTime()),
              formatDuration(task.forkTime(), task.completionTime()),
              formatOutcome(task.outcome())
            });
        rowRawNames.add(task.taskName());
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
