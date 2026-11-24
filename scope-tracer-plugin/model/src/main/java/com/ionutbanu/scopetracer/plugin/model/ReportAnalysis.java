package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

/**
 * The two analyzer runs behind one recording in the tool window (#62): the JSON run, whose trace
 * fills the "Tasks" table, then the HTML run, whose report is the "Timeline". The HTML is the
 * analyzer's own default output, never derived from the JSON or read back out of the HTML.
 *
 * <ul>
 *   <li>A JSON run that fails or is cancelled ends the analysis: its exception is rethrown and
 *       the HTML run never starts.
 *   <li>An HTML run that fails (or whose file cannot be made) costs only the timeline: the result
 *       carries the trace and {@link HtmlReport.Failed}, and its file is deleted.
 *   <li>An HTML run that is cancelled cancels the analysis, as a cancelled JSON run does.
 *   <li>{@code afterRuns} (deleting the temp recording of "Run with Scope Tracer") runs exactly
 *       once, after both runs have finished, before this returns or throws.
 * </ul>
 *
 * <p>Plain Java with no IntelliJ dependency. The plugin passes the two {@link AnalyzerInvocation}
 * runs, so both get its timeout and cancellation.
 *
 * <pre>{@code
 * var result = ReportAnalysis.run(
 *     () -> parse(AnalyzerInvocation.run(java, jar, jfr, tmp, TIMEOUT, cancelled)),
 *     () -> Files.createTempFile("scope-tracer-report-", ".html"),
 *     html -> AnalyzerInvocation.writeHtml(java, jar, jfr, html, tmp, TIMEOUT, cancelled),
 *     () -> deleteTempRecording());
 * }</pre>
 */
public final class ReportAnalysis {

  /** The JSON run, returning the parsed trace. */
  @FunctionalInterface
  public interface JsonRun<T> {
    T run() throws IOException;
  }

  /** Makes the empty temp file the HTML run writes into. */
  @FunctionalInterface
  public interface HtmlFile {
    Path create() throws IOException;
  }

  /** The HTML run, writing the analyzer's report into {@code html}. */
  @FunctionalInterface
  public interface HtmlRun {
    void writeTo(Path html) throws IOException;
  }

  /** The trace of the JSON run and how the HTML run ended ({@link HtmlReport.Written} or Failed). */
  public record Result<T>(T trace, HtmlReport html) {}

  private ReportAnalysis() {}

  /**
   * Runs JSON, then HTML; see the class comment for every ending.
   *
   * @throws IOException when the JSON run failed.
   * @throws CancellationException when either run was cancelled.
   */
  public static <T> Result<T> run(
      JsonRun<T> json, HtmlFile htmlFile, HtmlRun html, Runnable afterRuns) throws IOException {
    try {
      var trace = json.run();
      return new Result<>(trace, html(htmlFile, html));
    } finally {
      afterRuns.run();
    }
  }

  private static HtmlReport html(HtmlFile htmlFile, HtmlRun html) {
    Path file;
    try {
      file = htmlFile.create();
    } catch (IOException | RuntimeException e) {
      return new HtmlReport.Failed(message(e));
    }
    try {
      html.writeTo(file);
      return new HtmlReport.Written(file);
    } catch (CancellationException e) {
      deleteQuietly(file);
      throw e;
    } catch (IOException | RuntimeException e) {
      deleteQuietly(file);
      return new HtmlReport.Failed(message(e));
    }
  }

  private static String message(Exception e) {
    var message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getName() : message;
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // a temp file left behind is the worst case; never mask the outcome
    }
  }
}
