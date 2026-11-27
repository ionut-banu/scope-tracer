package com.ionutbanu.scopetracer.plugin;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser;
import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import com.ionutbanu.scopetracer.plugin.model.RunRecordings;
import com.ionutbanu.scopetracer.plugin.model.RunRecordings.Outcome;
import com.ionutbanu.scopetracer.plugin.model.RunRecordings.Show;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Opens the recordings a traced run left in its recording directory, one per JVM (#64). {@link
 * RunRecordings} decides which are analyzed and which are shown; this class lists the directory,
 * runs the analyzer once per recording in one background task (never on the EDT), and puts each
 * trace in the tool window: one in the main content, as before, or several in tabs of their own,
 * named after the configuration and the pid. A new run of the same configuration closes the tabs
 * of the previous one.
 */
final class TracedRunRecordings {

  private static final Logger LOG = Logger.getInstance(TracedRunRecordings.class);

  /** Marks a tab holding one recording of a run, with the configuration it came from. */
  private static final Key<String> RUN_CONFIGURATION = Key.create("scopeTracer.runConfiguration");

  static final String NOTHING_RECORDED =
      "The run finished but wrote no recording, so there is nothing to show.";

  static final String NO_SCOPES = "The recording contains no StructuredTaskScope activity.";

  private TracedRunRecordings() {}

  static void open(
      Project project,
      Path directory,
      AnalyzerJdkChooser.@Nullable Candidate tracedRunJdk,
      String configurationName,
      String configurationKey) {
    new Task.Backgroundable(project, "Analyzing scope-tracer recordings", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        var selection = RunRecordings.select(list(directory));
        try {
          analyzeAndShow(
              project, directory, selection, tracedRunJdk, configurationName, configurationKey,
              indicator);
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (CancellationException e) {
          // Cancelled from the progress bar, or the project closed: not a failure.
          LOG.info("Scope Tracer: " + e.getMessage());
        } catch (RuntimeException e) {
          ScopeTracerNotifier.notify(
              project,
              "Scope Tracer analysis failed",
              escape(String.valueOf(e.getMessage())),
              NotificationType.ERROR);
        } finally {
          if (selection.deleteAfterAnalysis()) {
            TracedRunDirectories.delete(project, directory);
          } else {
            TracedRunDirectories.keep(project, directory);
          }
        }
      }
    }.queue();
  }

  private static void analyzeAndShow(
      Project project,
      Path directory,
      RunRecordings.Selection selection,
      AnalyzerJdkChooser.@Nullable Candidate tracedRunJdk,
      String configurationName,
      String configurationKey,
      ProgressIndicator indicator) {
    if (selection.nothingRecorded()) {
      ScopeTracerNotifier.notify(project, "Scope Tracer", NOTHING_RECORDED, NotificationType.WARNING);
      return;
    }
    if (selection.keepDirectory()) {
      ScopeTracerNotifier.notify(
          project,
          "Scope Tracer",
          escape(RunRecordings.keptNotice(selection, directory)),
          NotificationType.INFORMATION);
    }
    indicator.setText("Choosing a JDK for the analyzer");
    var javaPath = AnalyzerJdkSettings.resolve(project, tracedRunJdk);
    if (javaPath == null) {
      return; // the error notification, with "Configure…", is shown
    }

    var outcomes = new ArrayList<Outcome>();
    Map<Path, PluginTraceModel> models = new HashMap<>();
    int n = 0;
    for (var file : selection.analyze()) {
      n++;
      if (indicator.isCanceled()) {
        throw new CancellationException("analysis cancelled");
      }
      indicator.setText(
          "Analyzing scope-tracer recording"
              + (selection.analyze().size() > 1 ? " " + n + " of " + selection.analyze().size() : ""));
      try {
        var model = AnalyzerProcessRunner.analyze(file, javaPath, indicator::isCanceled);
        models.put(file, model);
        outcomes.add(new Outcome.Parsed(file, !model.scopes().isEmpty()));
      } catch (CancellationException e) {
        throw e;
      } catch (IOException | RuntimeException e) {
        // One error naming the file; the other recordings are still analyzed.
        outcomes.add(new Outcome.Failed(file));
        ScopeTracerNotifier.notify(
            project,
            "Scope Tracer analysis failed",
            escape(file.getFileName() + ": " + e.getMessage()),
            NotificationType.ERROR);
      }
    }

    switch (RunRecordings.show(selection, outcomes)) {
      case Show.NothingRecorded nothing ->
          ScopeTracerNotifier.notify(
              project, "Scope Tracer", NOTHING_RECORDED, NotificationType.WARNING);
      case Show.NoScopes none ->
          ScopeTracerNotifier.notify(
              project, "Scope Tracer", NO_SCOPES, NotificationType.INFORMATION);
      case Show.Nothing nothing -> {}
      case Show.Main main -> {
        var panels = onEdt(project, configurationKey, List.of(), configurationName);
        if (panels != null && !panels.isEmpty()) {
          render(project, panels.getFirst(), models.get(main.file()), main.file(), javaPath, indicator);
        }
      }
      case Show.Tabs tabs -> {
        var panels = onEdt(project, configurationKey, tabs.files(), configurationName);
        if (panels != null) {
          for (int i = 0; i < panels.size(); i++) {
            var file = tabs.files().get(i);
            render(project, panels.get(i), models.get(file), file, javaPath, indicator);
          }
        }
      }
    }
  }

  private static void render(
      Project project,
      ScopeTracerPanel panel,
      PluginTraceModel model,
      Path file,
      Path javaPath,
      ProgressIndicator indicator) {
    try {
      panel.showAnalyzed(model, file, javaPath, indicator);
    } catch (IOException e) {
      // Not thrown: the JSON run already succeeded, and an HTML failure is in the views.
      ScopeTracerNotifier.notify(
          project,
          "Scope Tracer analysis failed",
          escape(String.valueOf(e.getMessage())),
          NotificationType.ERROR);
    }
  }

  /**
   * On the EDT: closes the tabs of the configuration's previous run, then returns the panels to
   * fill. With no {@code tabFiles}, that is the main content's panel; otherwise one new closable
   * tab per file, in order. {@code null} when the tool window is gone.
   */
  private static @Nullable List<ScopeTracerPanel> onEdt(
      Project project, String configurationKey, List<Path> tabFiles, String configurationName) {
    var result = new ArrayList<ScopeTracerPanel>();
    var available = new boolean[] {false};
    ApplicationManager.getApplication()
        .invokeAndWait(
            () -> {
              if (project.isDisposed()) {
                return;
              }
              ToolWindow toolWindow =
                  ToolWindowManager.getInstance(project).getToolWindow("Scope Tracer");
              if (toolWindow == null) {
                return;
              }
              var manager = toolWindow.getContentManager();
              for (var content : manager.getContents()) {
                if (configurationKey.equals(content.getUserData(RUN_CONFIGURATION))) {
                  manager.removeContent(content, true);
                }
              }
              if (tabFiles.isEmpty()) {
                var main = mainContent(toolWindow);
                if (main == null) {
                  return;
                }
                result.add((ScopeTracerPanel) main.getComponent());
                manager.setSelectedContent(main);
              } else {
                Content first = null;
                for (var file : tabFiles) {
                  var panel = new ScopeTracerPanel(project);
                  var content =
                      ContentFactory.getInstance()
                          .createContent(
                              panel, RunRecordings.tabName(configurationName, file), false);
                  content.setCloseable(true);
                  // Closing the tab, or the project, disposes the panel and its temp HTML file.
                  content.setDisposer(panel);
                  content.putUserData(RUN_CONFIGURATION, configurationKey);
                  manager.addContent(content);
                  result.add(panel);
                  if (first == null) {
                    first = content;
                  }
                }
                manager.setSelectedContent(first);
              }
              available[0] = true;
              toolWindow.activate(null);
            });
    return available[0] ? result : null;
  }

  /** The content the tool window factory made, which a single recording replaces. */
  private static @Nullable Content mainContent(ToolWindow toolWindow) {
    for (var content : toolWindow.getContentManager().getContents()) {
      if (content.getUserData(RUN_CONFIGURATION) == null
          && content.getComponent() instanceof ScopeTracerPanel) {
        return content;
      }
    }
    return null;
  }

  private static List<RunRecordings.Found> list(Path directory) {
    var found = new ArrayList<RunRecordings.Found>();
    if (!Files.isDirectory(directory)) {
      return found;
    }
    try (Stream<Path> files = Files.list(directory)) {
      for (var file : files.toList()) {
        try {
          if (Files.isRegularFile(file)) {
            found.add(
                new RunRecordings.Found(
                    file, Files.size(file), Files.getLastModifiedTime(file).toMillis()));
          }
        } catch (IOException e) {
          // gone meanwhile: a missing recording, ignored
        }
      }
    } catch (IOException e) {
      LOG.info("Scope Tracer: could not list " + directory + ": " + e.getMessage());
    }
    return found;
  }

  /** Notification content is HTML; a path or message may contain '<' or '&'. */
  private static String escape(String text) {
    return com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text);
  }
}
