package com.ionutbanu.scopetracer.plugin.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Scope Tracer tool window shows once an analysis has ended (#62), decided from whether
 * JCEF is supported, how the JSON run ended and how the HTML run ended. Pure logic with no
 * IntelliJ dependency, like {@link TracedRunLifecycle}; the panel only carries it out.
 *
 * <ul>
 *   <li>The JSON run failed: nothing is replaced and there is one error ({@link Display.Keep}).
 *   <li>Otherwise both views are replaced ({@link Display.Replace}). "Tasks" always shows the
 *       trace. "Timeline" is the report page when JCEF is supported and the report was written, a
 *       message when JCEF is supported and it was not, and absent without JCEF. "Open in Browser"
 *       opens the report when there is one and offers nothing otherwise.
 *   <li>A failed HTML run is one error, and then no JCEF notice: there is no report to open.
 * </ul>
 *
 * <pre>{@code
 * switch (ReportViews.decide(JBCefApp.isSupported(), new Json.Parsed(empty), html)) {
 *   case Display.Keep k -> showError(k.error());
 *   case Display.Replace r -> { tasks(model); timeline(r.timeline()); ... }
 * }
 * }</pre>
 */
public final class ReportViews {

  /** The "Timeline" view's text when the HTML run failed. */
  public static final String TIMELINE_FAILED = "The timeline could not be generated.";

  /** The "Timeline" view's text when there was no HTML run. */
  public static final String TIMELINE_NOT_RUN = "No timeline was generated for this recording.";

  /** How the JSON run ended. */
  public sealed interface Json {
    /** The trace was parsed; {@code empty} when it holds no scopes. */
    record Parsed(boolean empty) implements Json {}

    /** The run failed; {@code message} says why. */
    record Failed(String message) implements Json {}
  }

  /** What the "Timeline" view shows. */
  public sealed interface Timeline {
    /** No "Timeline" view at all: JCEF is not supported, so only "Tasks" is shown. */
    Timeline NONE = new None();

    /** The analyzer's report, loaded from its file. */
    record Page(Path file) implements Timeline {}

    /** A line of text instead of the report. */
    record Message(String text) implements Timeline {}

    /** See {@link #NONE}. */
    record None() implements Timeline {}
  }

  /** An information notification beside the views. */
  public enum Notice {
    /** The recording holds no scope-tracer events. */
    NO_SCOPES,
    /** The timeline needs JCEF; the report can be opened in the browser. Shown once a session. */
    JCEF_NEEDED
  }

  /** What to do with the tool window. */
  public sealed interface Display {
    /**
     * Keep the views as they are and show {@code error}. {@code discard} is a report file the
     * analysis wrote that nothing will show, to delete, or {@code null}.
     */
    record Keep(String error, Path discard) implements Display {}

    /**
     * Replace both views.
     *
     * @param timeline what "Timeline" shows.
     * @param htmlFile the report "Open in Browser" opens, now owned by the views; {@code null}
     *     when there is none and the action offers nothing.
     * @param error an error to show once, or {@code null}.
     * @param notices information notifications to show.
     */
    record Replace(Timeline timeline, Path htmlFile, String error, List<Notice> notices)
        implements Display {}
  }

  private ReportViews() {}

  public static Display decide(boolean jcefSupported, Json json, HtmlReport html) {
    if (json instanceof Json.Failed failed) {
      return new Display.Keep(
          failed.message(), html instanceof HtmlReport.Written w ? w.file() : null);
    }
    boolean empty = ((Json.Parsed) json).empty();
    var notices = new ArrayList<Notice>();
    if (empty) {
      notices.add(Notice.NO_SCOPES);
    }
    return switch (html) {
      case HtmlReport.Written w -> {
        if (!jcefSupported) {
          notices.add(Notice.JCEF_NEEDED);
        }
        yield new Display.Replace(
            jcefSupported ? new Timeline.Page(w.file()) : Timeline.NONE,
            w.file(),
            null,
            List.copyOf(notices));
      }
      case HtmlReport.Failed f ->
          new Display.Replace(
              jcefSupported ? new Timeline.Message(TIMELINE_FAILED) : Timeline.NONE,
              null,
              f.message(),
              List.copyOf(notices));
      case HtmlReport.NotRun n ->
          new Display.Replace(
              jcefSupported ? new Timeline.Message(TIMELINE_NOT_RUN) : Timeline.NONE,
              null,
              null,
              List.copyOf(notices));
    };
  }
}
