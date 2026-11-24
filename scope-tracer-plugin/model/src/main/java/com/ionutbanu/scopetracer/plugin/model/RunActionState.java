package com.ionutbanu.scopetracer.plugin.model;

/**
 * Decides whether Tools → "Run with Scope Tracer" is shown and enabled, and whether clicking it
 * starts a traced run or explains what is needed instead. Both the action's {@code update} and its
 * {@code actionPerformed} ask this, so the menu and the click never disagree. Pure logic with no
 * IntelliJ dependency so it runs as ordinary JUnit.
 *
 * <p>The action is shown whenever a project is open, so a new user finds it in the menu even
 * before they have a run configuration; when it cannot run, the click says why.
 */
public final class RunActionState {

  private RunActionState() {}

  /** The kind of run configuration selected in the project's run widget. */
  public sealed interface Selection {
    /** No run configuration is selected. */
    record None() implements Selection {}

    /** A Java Application run configuration: the one kind the action runs. */
    record Application() implements Selection {}

    /**
     * Any other run configuration.
     *
     * @param name the configuration's name, as the run widget shows it.
     * @param typeName its type's display name, for example {@code "JUnit"} or {@code "Gradle"}.
     */
    record Other(String name, String typeName) implements Selection {}
  }

  /**
   * What the action shows and does.
   *
   * @param visible whether the action is in the menu.
   * @param enabled whether it can be clicked.
   * @param explanation an information message to show instead of running, or {@code null} when a
   *     click starts the traced run.
   */
  public record Decision(boolean visible, boolean enabled, String explanation) {
    /** Whether a click starts a traced run of the selected configuration. */
    public boolean runs() {
      return enabled && explanation == null;
    }
  }

  private static final Decision HIDDEN = new Decision(false, false, null);
  private static final Decision RUN = new Decision(true, true, null);

  /**
   * Decides the action's state.
   *
   * @param projectOpen whether the action was invoked with a project open.
   * @param selection the selected run configuration's kind; ignored when no project is open.
   */
  public static Decision decide(boolean projectOpen, Selection selection) {
    if (!projectOpen) {
      return HIDDEN;
    }
    return switch (selection) {
      case Selection.Application a -> RUN;
      case Selection.None n ->
          new Decision(
              true,
              true,
              "No run configuration is selected. Select or create a Java Application run configuration, then choose \"Run with Scope Tracer\" again.");
      case Selection.Other o ->
          new Decision(
              true,
              true,
              "\""
                  + o.name()
                  + "\" is a "
                  + o.typeName()
                  + " run configuration. \"Run with Scope Tracer\" runs Java Application configurations: select or create one, then choose it again.");
    };
  }
}
