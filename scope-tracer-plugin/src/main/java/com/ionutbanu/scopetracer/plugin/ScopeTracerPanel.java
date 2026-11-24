package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.ionutbanu.scopetracer.plugin.model.CallSite;
import com.ionutbanu.scopetracer.plugin.model.CallSiteParser;
import com.ionutbanu.scopetracer.plugin.model.HtmlReport;
import com.ionutbanu.scopetracer.plugin.model.PluginScopeRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskOutcome;
import com.ionutbanu.scopetracer.plugin.model.PluginTaskRecord;
import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import com.ionutbanu.scopetracer.plugin.model.ReportAnalysis;
import com.ionutbanu.scopetracer.plugin.model.ReportViews;
import com.ionutbanu.scopetracer.plugin.model.ReportViews.Display;
import com.ionutbanu.scopetracer.plugin.model.ShownReport;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jetbrains.annotations.NotNull;

/**
 * The Scope Tracer tool window's content: two views of one recording (#62). "Timeline" is the
 * analyzer's own HTML report in an embedded browser ({@link TimelineView}), shown first; "Tasks" is
 * the flat task table, one row per scope header and indented rows per task, with double-click to
 * source. Without JCEF only "Tasks" is shown. "Open in Browser" opens the same HTML file in the
 * system browser either way. What is shown is decided by {@link ReportViews}; the temp HTML file
 * is owned by {@link ShownReport} and deleted when the next recording replaces it or this panel is
 * disposed with its content (the project closes).
 */
final class ScopeTracerPanel extends JPanel implements Disposable {

  private static final Logger LOG = Logger.getInstance(ScopeTracerPanel.class);

  /** The "timeline needs JCEF" notice is shown once an IDE session, not once a recording. */
  private static final AtomicBoolean JCEF_NOTICE_SHOWN = new AtomicBoolean();

  private final Project project;
  private final boolean jcefSupported;
  private final TimelineView timeline; // null without JCEF
  private final ShownReport shown = new ShownReport();
  private final JButton openInBrowserButton = new JButton("Open in Browser");
  private final DefaultTableModel tableModel =
      new DefaultTableModel(
          new Object[] {"Scope / Task", "Thread", "Fork Offset", "Duration", "Outcome"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
  private final JBTable table = new JBTable(tableModel);

  // Parallel to tableModel's rows: the resolved CallSite (or null) behind each row, used for
  // click-to-source navigation. Scope header rows are always null — core never derives
  // scope-level call sites, since TracedScope.open(String) always takes a literal name. Task rows
  // resolve via CallSiteParser.forTask, which prefers the structured call site captured at fork
  // time and falls back to parsing the legacy taskName convention for older recordings.
  private final List<CallSite> rowCallSites = new ArrayList<>();

  ScopeTracerPanel(Project project) {
    super(new BorderLayout());
    this.project = project;

    var toolbar = new JToolBar();
    toolbar.setFloatable(false);
    var openButton = new JButton("Open .jfr…");
    openButton.addActionListener(e -> openAndAnalyze());
    toolbar.add(openButton);
    openInBrowserButton.setToolTipText("Open this recording's HTML report in the system browser");
    openInBrowserButton.setEnabled(false);
    openInBrowserButton.addActionListener(e -> openInBrowser());
    toolbar.add(openInBrowserButton);

    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2) {
              return;
            }
            var row = table.rowAtPoint(e.getPoint());
            if (row < 0 || row >= rowCallSites.size()) {
              LOG.info("Scope Tracer: double-click at row " + row + " — out of range, ignoring");
              return;
            }
            var callSite = rowCallSites.get(row);
            LOG.info("Scope Tracer: double-click row " + row + " -> " + callSite);
            if (callSite != null) {
              TaskSourceNavigator.navigate(project, table, callSite);
            }
          }
        });

    add(toolbar, BorderLayout.NORTH);
    var tasks = new JBScrollPane(table);
    // Asked before any JCEF object exists; without JCEF the panel is the table alone, as before.
    jcefSupported = JcefSupport.isSupported();
    if (jcefSupported) {
      timeline = new TimelineView(this);
      var tabs = new JBTabbedPane();
      tabs.addTab("Timeline", timeline.component());
      tabs.addTab("Tasks", tasks);
      tabs.setSelectedIndex(0);
      add(tabs, BorderLayout.CENTER);
    } else {
      timeline = null;
      add(tasks, BorderLayout.CENTER);
    }
  }

  /** The content is disposed (the project closes): delete the temp HTML file shown. */
  @Override
  public void dispose() {
    shown.dispose();
  }

  private void openInBrowser() {
    var file = shown.current();
    if (file != null && Files.exists(file)) {
      BrowserUtil.browse(file);
    }
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
    analyzeRecording(jfrPath, javaPath, false);
  }

  /**
   * Analyzes {@code jfrPath} off the EDT and shows the result: the JSON run, then the HTML run
   * (both in this background task, with the runner's timeout and cancellation), then both views
   * replaced on the EDT. {@code deleteAfter} removes the recording once both runs have finished
   * (used for temp recordings made by "Run with Scope Tracer").
   */
  void analyzeRecording(Path jfrPath, Path javaPath, boolean deleteAfter) {
    new Task.Backgroundable(project, "Analyzing scope-tracer recording", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          var result =
              ReportAnalysis.run(
                  () -> AnalyzerProcessRunner.analyze(jfrPath, javaPath, indicator::isCanceled),
                  () -> Files.createTempFile("scope-tracer-report-", ".html"),
                  html -> {
                    indicator.setText("Rendering the scope-tracer timeline");
                    AnalyzerProcessRunner.writeHtml(jfrPath, javaPath, html, indicator::isCanceled);
                  },
                  () -> {
                    if (deleteAfter) {
                      deleteQuietly(jfrPath);
                    }
                  });
          var model = result.trace();
          var display =
              ReportViews.decide(
                  jcefSupported,
                  new ReportViews.Json.Parsed(model.scopes().isEmpty()),
                  result.html());
          SwingUtilities.invokeLater(() -> apply(model, display));
        } catch (CancellationException e) {
          // Cancelled from the progress bar, or the project closed: the analyzer is already
          // dead. Not a failure, so no error balloon; the tool window keeps what it showed.
          LOG.info("Scope Tracer: " + e.getMessage());
        } catch (Exception e) {
          var display =
              ReportViews.decide(
                  jcefSupported,
                  new ReportViews.Json.Failed(String.valueOf(e.getMessage())),
                  HtmlReport.NOT_RUN);
          SwingUtilities.invokeLater(() -> apply(null, display));
        }
      }
    }.queue();
  }

  /** Carries out {@link ReportViews}'s decision. On the EDT. */
  private void apply(PluginTraceModel model, Display display) {
    switch (display) {
      case Display.Keep keep -> {
        deleteQuietly(keep.discard());
        if (!project.isDisposed()) {
          showError("Scope Tracer analysis failed", keep.error());
        }
      }
      case Display.Replace replace -> {
        if (!shown.show(replace.htmlFile())) {
          return; // disposed meanwhile; the file has been deleted
        }
        populate(model);
        if (timeline != null) {
          timeline.show(replace.timeline());
        }
        openInBrowserButton.setEnabled(replace.htmlFile() != null);
        if (replace.error() != null) {
          showError("Scope Tracer timeline could not be generated", replace.error());
        }
        for (var notice : replace.notices()) {
          switch (notice) {
            case NO_SCOPES ->
                ScopeTracerNotifier.notify(
                    project,
                    "Scope Tracer",
                    "The recording contains no StructuredTaskScope activity.",
                    NotificationType.INFORMATION);
            case JCEF_NEEDED -> {
              if (JCEF_NOTICE_SHOWN.compareAndSet(false, true)) {
                ScopeTracerNotifier.notify(
                    project,
                    "Scope Tracer",
                    "The timeline needs the IDE's embedded browser (JCEF), which is not available"
                        + " here, so only the task table is shown. Use Open in Browser to see the"
                        + " report's timeline in the system browser.",
                    NotificationType.INFORMATION);
              }
            }
          }
        }
      }
    }
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // temp file
    }
  }

  private void populate(PluginTraceModel model) {
    tableModel.setRowCount(0);
    rowCallSites.clear();
    for (PluginScopeRecord scope : model.scopes()) {
      tableModel.addRow(new Object[] {scope.name(), scope.ownerThreadName(), "", "", ""});
      rowCallSites.add(null);
      for (PluginTaskRecord task : scope.tasks()) {
        tableModel.addRow(
            new Object[] {
              "    #" + task.taskId() + " " + emptyToDash(task.taskName()),
              task.threadName(),
              formatOffset(scope.openTime(), task.forkTime()),
              formatDuration(task.forkTime(), task.completionTime()),
              formatOutcome(task.outcome())
            });
        rowCallSites.add(CallSiteParser.forTask(task));
      }
    }
  }

  private void showError(Exception e) {
    showError("Scope Tracer analysis failed", String.valueOf(e.getMessage()));
  }

  private void showError(String title, String message) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Scope Tracer")
        .createNotification(title, message, NotificationType.ERROR)
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
