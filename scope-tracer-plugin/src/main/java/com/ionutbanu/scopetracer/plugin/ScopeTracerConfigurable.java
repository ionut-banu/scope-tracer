package com.ionutbanu.scopetracer.plugin;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.FormBuilder;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Choice;
import com.ionutbanu.scopetracer.plugin.model.AnalyzerJdkChooser.Skipped;
import com.ionutbanu.scopetracer.plugin.model.TracedLaunch;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Settings → Tools → Scope Tracer: which JDK runs the analyzer. "Automatic" (the default, no saved
 * value) or an explicit {@code java}, from a registered JDK 27+ or from disk, saved under {@code
 * scopeTracer.jdk27JavaPath} like 0.6.0 did. Application-wide. Every {@code java -version} the
 * page runs is off the EDT: the automatic preview on a pooled thread, the check on Apply under a
 * modal progress.
 */
public final class ScopeTracerConfigurable implements Configurable {

  private JPanel panel;
  private JBRadioButton automatic;
  private JBRadioButton registered;
  private JBRadioButton onDisk;
  private ComboBox<Sdk> registeredJdks;
  private TextFieldWithBrowseButton diskPath;
  private JBLabel automaticStatus;
  private volatile boolean disposed;
  /** Bumped on the EDT by every refresh of the "Automatic" preview. */
  private int statusGeneration;

  @Override
  public String getDisplayName() {
    return "Scope Tracer";
  }

  @Override
  public @Nullable JComponent createComponent() {
    disposed = false;
    automatic = new JBRadioButton("Automatic");
    registered = new JBRadioButton("Registered JDK:");
    onDisk = new JBRadioButton("JDK home or java on disk:");
    var group = new ButtonGroup();
    group.add(automatic);
    group.add(registered);
    group.add(onDisk);

    registeredJdks = new ComboBox<>();
    registeredJdks.setRenderer(
        new SimpleListCellRenderer<Sdk>() {
          @Override
          public void customize(
              @NotNull JList<? extends Sdk> list,
              Sdk sdk,
              int index,
              boolean selected,
              boolean hasFocus) {
            setText(
                sdk == null
                    ? "(no JDK " + TracedLaunch.MIN_JDK_MAJOR + "+ registered)"
                    : sdk.getName() + "  " + Objects.toString(sdk.getVersionString(), ""));
          }
        });
    diskPath = new TextFieldWithBrowseButton();
    diskPath.addBrowseFolderListener(
        null,
        FileChooserDescriptorFactory.singleFileOrDir()
            .withTitle("JDK " + TracedLaunch.MIN_JDK_MAJOR + "+ Home or java Launcher"));
    automaticStatus = new JBLabel();
    automaticStatus.setAllowAutoWrapping(true);

    automatic.addActionListener(e -> enableFields());
    registered.addActionListener(e -> enableFields());
    onDisk.addActionListener(e -> enableFields());

    panel =
        FormBuilder.createFormBuilder()
            .addComponent(
                new JBLabel(
                    "The JDK that runs the scope-tracer analyzer (JDK "
                        + TracedLaunch.MIN_JDK_MAJOR
                        + " or newer):"))
            .addComponent(automatic)
            .addComponentToRightColumn(automaticStatus)
            .addLabeledComponent(registered, registeredJdks)
            .addLabeledComponent(onDisk, diskPath)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    reset();
    return panel;
  }

  @Override
  public boolean isModified() {
    return !selectedJava().equals(AnalyzerJdkSettings.explicitJava());
  }

  @Override
  public void apply() throws ConfigurationException {
    var selected = selectedJava();
    if (onDisk.isSelected() && selected.isEmpty()) {
      throw new ConfigurationException(
          "Choose a JDK " + TracedLaunch.MIN_JDK_MAJOR + " or newer, or select Automatic.");
    }
    if (registered.isSelected() && selected.isEmpty()) {
      throw new ConfigurationException(
          "No registered JDK "
              + TracedLaunch.MIN_JDK_MAJOR
              + "+ is selected; the scope-tracer analyzer needs JDK "
              + TracedLaunch.MIN_JDK_MAJOR
              + " or newer.");
    }
    if (selected.isPresent()) {
      var java = selected.get();
      Optional<String> refusal =
          ProgressManager.getInstance()
              .runProcessWithProgressSynchronously(
                  () -> AnalyzerJdkChooser.refusalOfExplicit(java, AnalyzerJdkSettings.probe()),
                  "Checking " + java,
                  false,
                  null);
      if (refusal.isPresent()) {
        // The previous setting stays.
        throw new ConfigurationException(refusal.get(), "JDK " + TracedLaunch.MIN_JDK_MAJOR + " Needed");
      }
    }
    AnalyzerJdkSettings.explicitJava(selected);
    refreshAutomaticStatus();
  }

  @Override
  public void reset() {
    var jdks = AnalyzerJdkSettings.registeredJdks();
    registeredJdks.removeAllItems();
    jdks.stream()
        .filter(sdk -> TracedLaunch.parseJavaMajor(sdk.getVersionString()) >= TracedLaunch.MIN_JDK_MAJOR)
        .forEach(registeredJdks::addItem);

    var explicit = AnalyzerJdkSettings.explicitJava();
    diskPath.setText("");
    if (explicit.isEmpty()) {
      automatic.setSelected(true);
    } else {
      var match =
          jdks.stream()
              .filter(sdk -> explicit.get().equals(AnalyzerJdkSettings.javaOf(sdk)))
              .findFirst();
      if (match.isPresent() && inCombo(match.get())) {
        registered.setSelected(true);
        registeredJdks.setSelectedItem(match.get());
      } else {
        onDisk.setSelected(true);
        diskPath.setText(explicit.get().toString());
      }
    }
    enableFields();
    refreshAutomaticStatus();
  }

  @Override
  public void disposeUIResources() {
    disposed = true;
    panel = null;
  }

  private boolean inCombo(Sdk sdk) {
    for (int i = 0; i < registeredJdks.getItemCount(); i++) {
      if (registeredJdks.getItemAt(i) == sdk) {
        return true;
      }
    }
    return false;
  }

  private void enableFields() {
    registeredJdks.setEnabled(registered.isSelected());
    diskPath.setEnabled(onDisk.isSelected());
  }

  /** The {@code java} the page currently selects; empty for "Automatic". */
  private Optional<Path> selectedJava() {
    if (registered.isSelected()) {
      var sdk = (Sdk) registeredJdks.getSelectedItem();
      return sdk == null ? Optional.empty() : Optional.ofNullable(AnalyzerJdkSettings.javaOf(sdk));
    }
    if (onDisk.isSelected()) {
      var text = diskPath.getText().trim();
      if (text.isEmpty()) {
        return Optional.empty();
      }
      try {
        return Optional.of(AnalyzerJdkChooser.launcherFor(Path.of(text), SystemInfo.isWindows));
      } catch (InvalidPathException e) {
        return Optional.empty(); // refused on Apply as "choose a JDK 27 or newer"
      }
    }
    return Optional.empty();
  }

  /** Works out, off the EDT, which JDK "Automatic" would use right now and what it skipped. */
  private void refreshAutomaticStatus() {
    automaticStatus.setText("Automatic: looking for a JDK " + TracedLaunch.MIN_JDK_MAJOR + "+…");
    var project = settingsProject();
    int generation = ++statusGeneration;
    ApplicationManager.getApplication()
        .executeOnPooledThread(
            () -> {
              var text = describe(AnalyzerJdkSettings.chooseAutomatic(project), project);
              ApplicationManager.getApplication()
                  .invokeLater(
                      () -> {
                        // Only the newest refresh (after Apply, say) may write the label.
                        if (!disposed && generation == statusGeneration) {
                          automaticStatus.setText(text);
                        }
                      },
                      ModalityState.any());
            });
  }

  /**
   * The project the settings dialog was opened for: from the dialog's data context once the page
   * is in it, otherwise the project of the IDE frame focused last (the one the dialog was opened
   * from), otherwise the only open project. An application-level page is not given a project, and
   * with several open this is the one whose project SDK the actions in that frame would use.
   */
  private @Nullable Project settingsProject() {
    if (panel != null) {
      var fromDialog = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(panel));
      if (fromDialog != null && !fromDialog.isDisposed()) {
        return fromDialog;
      }
    }
    var frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
    var fromFrame = frame == null ? null : frame.getProject();
    if (fromFrame != null && !fromFrame.isDisposed()) {
      return fromFrame;
    }
    var open =
        Arrays.stream(ProjectManager.getInstance().getOpenProjects())
            .filter(p -> !p.isDisposed())
            .toList();
    return open.size() == 1 ? open.get(0) : null;
  }

  private static String describe(Choice choice, @Nullable Project project) {
    var html = new StringBuilder("<html>");
    if (choice.chosen().isPresent()) {
      var chosen = choice.chosen().get();
      html.append("Automatic: <b>")
          .append(StringUtil.escapeXmlEntities(chosen.candidate().name()))
          .append("</b> (JDK ")
          .append(chosen.major())
          .append(", ")
          .append(
              switch (chosen.candidate().source()) {
                case PROJECT_SDK ->
                    "the project SDK"
                        + (project == null ? "" : " of " + StringUtil.escapeXmlEntities(project.getName()));
                case REGISTERED -> "the newest registered JDK";
                case TRACED_RUN -> "the traced run's JDK";
                case EXPLICIT -> "chosen";
              })
          .append(")");
    } else {
      html.append("Automatic: no JDK ")
          .append(TracedLaunch.MIN_JDK_MAJOR)
          .append("+ found. Add one in Project Structure → SDKs.");
    }
    List<Skipped> skipped = choice.skipped();
    if (!skipped.isEmpty()) {
      html.append("<br>Skipped: ")
          .append(
              skipped.stream()
                  .map(s -> StringUtil.escapeXmlEntities(s.line()))
                  .collect(Collectors.joining("; ")));
    }
    return html.append("<br>\"Run with Scope Tracer\" tries the run's own JDK first.</html>").toString();
  }
}
