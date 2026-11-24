package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two analyzer runs behind one recording (#62): JSON, then HTML into a temp file, and the
 * temp recording let go only after both have finished. The runs are stand-ins that record what
 * they saw, so the order is checked, not assumed.
 */
class ReportAnalysisTest {

  @TempDir Path dir;

  private final List<String> calls = new ArrayList<>();

  private Path recording() throws IOException {
    return Files.writeString(dir.resolve("run.jfr"), "recording");
  }

  private ReportAnalysis.HtmlFile htmlFile() {
    return () -> {
      calls.add("create html");
      return Files.createTempFile(dir, "scope-tracer-report-", ".html");
    };
  }

  private Runnable deleting(Path recording) {
    return () -> {
      calls.add("after runs");
      try {
        Files.deleteIfExists(recording);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    };
  }

  private List<Path> htmlFilesLeft() throws IOException {
    try (var files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".html")).toList();
    }
  }

  @Test
  void bothRunsSucceedTheHtmlIsWrittenAndTheRecordingGoesOnlyAfterBoth() throws IOException {
    var jfr = recording();

    var result =
        ReportAnalysis.run(
            () -> {
              calls.add("json, recording exists: " + Files.exists(jfr));
              return "model";
            },
            htmlFile(),
            html -> {
              calls.add("html, recording exists: " + Files.exists(jfr));
              Files.writeString(html, "<html/>");
            },
            deleting(jfr));

    assertThat(calls)
        .containsExactly(
            "json, recording exists: true",
            "create html",
            "html, recording exists: true",
            "after runs");
    assertThat(result.trace()).isEqualTo("model");
    assertThat(result.html()).isInstanceOf(HtmlReport.Written.class);
    var file = ((HtmlReport.Written) result.html()).file();
    assertThat(file).hasContent("<html/>");
    assertThat(jfr).doesNotExist();
  }

  @Test
  void aFailedJsonRunSkipsTheHtmlRunAndStillLetsTheRecordingGo() throws IOException {
    var jfr = recording();

    assertThatThrownBy(
            () ->
                ReportAnalysis.<String>run(
                    () -> {
                      throw new IOException("Analyzer subprocess failed (exit 2)");
                    },
                    htmlFile(),
                    html -> calls.add("html"),
                    deleting(jfr)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exit 2");

    assertThat(calls).containsExactly("after runs");
    assertThat(jfr).doesNotExist();
    assertThat(htmlFilesLeft()).isEmpty();
  }

  @Test
  void aCancelledJsonRunIsACancellationAndRunsNothingMore() throws IOException {
    var jfr = recording();

    assertThatThrownBy(
            () ->
                ReportAnalysis.<String>run(
                    () -> {
                      throw new CancellationException("cancelled");
                    },
                    htmlFile(),
                    html -> calls.add("html"),
                    deleting(jfr)))
        .isInstanceOf(CancellationException.class);

    assertThat(calls).containsExactly("after runs");
    assertThat(htmlFilesLeft()).isEmpty();
  }

  @Test
  void aFailedHtmlRunKeepsTheJsonResultDeletesItsFileAndCarriesTheMessage() throws IOException {
    var jfr = recording();

    var result =
        ReportAnalysis.run(
            () -> "model",
            htmlFile(),
            html -> {
              Files.writeString(html, "half a report");
              calls.add("html, recording exists: " + Files.exists(jfr));
              throw new IOException("Analyzer subprocess failed (exit 3): error: cannot render");
            },
            deleting(jfr));

    assertThat(result.trace()).isEqualTo("model");
    assertThat(result.html())
        .isEqualTo(
            new HtmlReport.Failed("Analyzer subprocess failed (exit 3): error: cannot render"));
    assertThat(calls)
        .containsExactly("create html", "html, recording exists: true", "after runs");
    assertThat(htmlFilesLeft()).isEmpty();
    assertThat(jfr).doesNotExist();
  }

  @Test
  void anUnexpectedExceptionInTheHtmlRunIsAFailedTimelineNotALostAnalysis() throws IOException {
    var result =
        ReportAnalysis.run(
            () -> "model",
            htmlFile(),
            html -> {
              throw new IllegalStateException("boom");
            },
            () -> calls.add("after runs"));

    assertThat(result.trace()).isEqualTo("model");
    assertThat(result.html()).isEqualTo(new HtmlReport.Failed("boom"));
    assertThat(htmlFilesLeft()).isEmpty();
    assertThat(calls).endsWith("after runs");
  }

  @Test
  void aCancelledHtmlRunCancelsTheWholeAnalysisAndDeletesItsFile() throws IOException {
    var jfr = recording();

    assertThatThrownBy(
            () ->
                ReportAnalysis.run(
                    () -> "model",
                    htmlFile(),
                    html -> {
                      throw new CancellationException("cancelled");
                    },
                    deleting(jfr)))
        .isInstanceOf(CancellationException.class);

    assertThat(htmlFilesLeft()).isEmpty();
    assertThat(calls).containsExactly("create html", "after runs");
    assertThat(jfr).doesNotExist();
  }

  @Test
  void anHtmlFileThatCannotBeMadeIsAFailedTimelineAndTheHtmlRunIsSkipped() throws IOException {
    var result =
        ReportAnalysis.run(
            () -> "model",
            () -> {
              throw new IOException("No space left on device");
            },
            html -> calls.add("html"),
            () -> calls.add("after runs"));

    assertThat(result.html()).isEqualTo(new HtmlReport.Failed("No space left on device"));
    assertThat(calls).containsExactly("after runs");
  }

  @Test
  void aFailureWithNoMessageStillSaysWhatFailed() throws IOException {
    var result =
        ReportAnalysis.run(
            () -> "model",
            htmlFile(),
            html -> {
              throw new IOException();
            },
            () -> {});

    assertThat(result.html()).isEqualTo(new HtmlReport.Failed("java.io.IOException"));
  }
}
