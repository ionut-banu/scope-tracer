package com.ionutbanu.scopetracer.plugin.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides what to do with the recordings one "Run with Scope Tracer" run left in its recording
 * directory (#64). Every JVM of the run writes its own file ({@link
 * TracedLaunch#RECORDING_FILE_NAME}), so a JUnit run whose tests fork leaves several: the parent
 * JVM's, which holds no scopes, and one per forked JVM. They are never merged into one trace,
 * because {@code scopeId} restarts at 1 in each JVM (#197). Pure logic with no IntelliJ
 * dependency so it runs as ordinary JUnit; the glue lists the directory, analyzes and shows.
 *
 * <ol>
 *   <li>{@link #select}: missing and empty files are ignored; the first {@link #MAX_OPENED} by
 *       modification time are analyzed. With more than that, all of them are kept on disk and the
 *       user is told where; otherwise everything is deleted once analyzed.
 *   <li>{@link #show}, once each selected recording has been analyzed (or failed to be): one
 *       recording is shown as before, in the tool window's main content; of several, the ones that
 *       hold scopes are shown, one in the main content, or each in its own tab when there are
 *       more.
 * </ol>
 *
 * <pre>{@code
 * var selection = RunRecordings.select(found);
 * var outcomes = ...analyze each of selection.analyze()...;
 * switch (RunRecordings.show(selection, outcomes)) { ... }
 * if (selection.deleteAfterAnalysis()) { ...delete the directory... }
 * }</pre>
 */
public final class RunRecordings {

  /** The most recordings of one run that are analyzed and opened; the rest stay on disk. */
  public static final int MAX_OPENED = 10;

  private static final Pattern PID = Pattern.compile("scope-tracer-(\\d+)\\.jfr");

  private RunRecordings() {}

  /**
   * A file found in the run's recording directory.
   *
   * @param file its path.
   * @param size its size in bytes; {@code 0} for a JVM that was killed before it wrote anything.
   * @param lastModifiedMillis its modification time.
   */
  public record Found(Path file, long size, long lastModifiedMillis) {}

  /**
   * What to analyze.
   *
   * @param analyze the recordings to analyze, oldest first, at most {@link #MAX_OPENED}.
   * @param recorded how many non-empty recordings the run left in all.
   */
  public record Selection(List<Path> analyze, int recorded) {

    /** Whether the run left no recording with anything in it. */
    public boolean nothingRecorded() {
      return analyze.isEmpty();
    }

    /**
     * Whether some recordings are not opened: then every recording and the directory are kept,
     * and the user is told where they are.
     */
    public boolean keepDirectory() {
      return recorded > analyze.size();
    }

    /** Whether the recordings and their directory are deleted once all have been analyzed. */
    public boolean deleteAfterAnalysis() {
      return !keepDirectory();
    }
  }

  /** Picks the recordings to analyze from what the directory holds. */
  public static Selection select(List<Found> found) {
    var recorded =
        found.stream()
            .filter(f -> f.size() > 0 && f.file().getFileName().toString().endsWith(".jfr"))
            .sorted(
                Comparator.comparingLong(Found::lastModifiedMillis)
                    .thenComparing(f -> f.file().getFileName().toString()))
            .map(Found::file)
            .toList();
    var analyze = recorded.stream().limit(MAX_OPENED).toList();
    return new Selection(analyze, recorded.size());
  }

  /** How the analysis of one selected recording ended. */
  public sealed interface Outcome {
    Path file();

    /** The analyzer read it; {@code hasScopes} when its trace holds at least one scope. */
    record Parsed(Path file, boolean hasScopes) implements Outcome {}

    /** The analyzer failed on it (already reported, once, naming the file). */
    record Failed(Path file) implements Outcome {}
  }

  /** What the tool window shows for the run. */
  public sealed interface Show {
    /** The run wrote no recording: the existing "wrote no recording" warning, once. */
    record NothingRecorded() implements Show {}

    /**
     * Several recordings were read and none holds a scope: the existing "no StructuredTaskScope
     * activity" notice, once, and nothing replaced.
     */
    record NoScopes() implements Show {}

    /** Show {@code file} in the main content, replacing what it showed, as before #64. */
    record Main(Path file) implements Show {}

    /**
     * Show each of {@code files} in its own closable tab, in this order, leaving the main content
     * as it was.
     */
    record Tabs(List<Path> files) implements Show {}

    /** Every analysis failed; each failure was its own error, so there is nothing more to say. */
    record Nothing() implements Show {}
  }

  /** Decides what to show, once every recording in {@code selection} has an outcome. */
  public static Show show(Selection selection, List<Outcome> outcomes) {
    if (selection.nothingRecorded()) {
      return new Show.NothingRecorded();
    }
    var parsed = new ArrayList<Outcome.Parsed>();
    for (var outcome : outcomes) {
      if (outcome instanceof Outcome.Parsed p) {
        parsed.add(p);
      }
    }
    if (parsed.isEmpty()) {
      return new Show.Nothing();
    }
    // One recording is shown as before #64, scopes or not: the views say it is empty.
    if (selection.analyze().size() == 1) {
      return new Show.Main(parsed.getFirst().file());
    }
    var withScopes = parsed.stream().filter(Outcome.Parsed::hasScopes).map(Outcome::file).toList();
    return switch (withScopes.size()) {
      case 0 -> new Show.NoScopes();
      case 1 -> new Show.Main(withScopes.getFirst());
      default -> new Show.Tabs(withScopes);
    };
  }

  /**
   * The pid JFR put in the recording's file name ({@code %p} in {@link
   * TracedLaunch#RECORDING_FILE_NAME}), or {@code null} when the name holds none.
   */
  public static Long pidOf(Path file) {
    var name = file.getFileName();
    if (name == null) {
      return null;
    }
    var m = PID.matcher(name.toString());
    if (!m.matches()) {
      return null;
    }
    try {
      return Long.parseLong(m.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The name of the tab that shows {@code file}: {@code <configuration name> (pid <pid>)}, or the
   * file name in place of the pid when the name holds none.
   */
  public static String tabName(String configurationName, Path file) {
    var pid = pidOf(file);
    var name = file.getFileName();
    return configurationName
        + (pid != null ? " (pid " + pid + ")" : " (" + (name == null ? file : name) + ")");
  }

  /** The text of the notification when a run left more recordings than are opened. */
  public static String keptNotice(Selection selection, Path directory) {
    return "The run left "
        + selection.recorded()
        + " recordings. The first "
        + selection.analyze().size()
        + " are opened; all of them are kept in "
        + directory
        + ".";
  }
}
