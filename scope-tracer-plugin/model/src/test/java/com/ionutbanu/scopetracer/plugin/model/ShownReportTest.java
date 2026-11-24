package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The temp HTML file behind the tool window's views (#62): replaced with the next recording's,
 * deleted when the tool window's content (the project) is disposed, never left behind by an
 * analysis that finishes after that.
 */
class ShownReportTest {

  @TempDir Path dir;

  private Path html(String name) throws IOException {
    return Files.writeString(dir.resolve(name + ".html"), name);
  }

  @Test
  void theFirstReportIsShownAndKept() throws IOException {
    var shown = new ShownReport();
    var first = html("first");

    assertThat(shown.show(first)).isTrue();

    assertThat(shown.current()).isEqualTo(first);
    assertThat(first).exists();
  }

  @Test
  void aSecondRecordingDeletesTheFirstRecordingsFile() throws IOException {
    var shown = new ShownReport();
    var first = html("first");
    var second = html("second");
    shown.show(first);

    assertThat(shown.show(second)).isTrue();

    assertThat(first).doesNotExist();
    assertThat(second).exists();
    assertThat(shown.current()).isEqualTo(second);
  }

  @Test
  void aRecordingWithNoReportStillDeletesThePreviousOne() throws IOException {
    var shown = new ShownReport();
    var first = html("first");
    shown.show(first);

    assertThat(shown.show(null)).isTrue();

    assertThat(first).doesNotExist();
    assertThat(shown.current()).isNull();
  }

  @Test
  void showingTheSameFileAgainKeepsIt() throws IOException {
    var shown = new ShownReport();
    var first = html("first");
    shown.show(first);

    shown.show(first);

    assertThat(first).exists();
  }

  @Test
  void disposingDeletesTheFileShown() throws IOException {
    var shown = new ShownReport();
    var first = html("first");
    shown.show(first);

    shown.dispose();

    assertThat(first).doesNotExist();
    assertThat(shown.current()).isNull();
    assertThat(shown.isDisposed()).isTrue();
  }

  @Test
  void aReportArrivingAfterDisposalIsRefusedAndDeleted() throws IOException {
    var shown = new ShownReport();
    shown.dispose();
    var late = html("late");

    assertThat(shown.show(late)).isFalse();

    assertThat(late).doesNotExist();
    assertThat(shown.current()).isNull();
  }

  @Test
  void aFileAlreadyGoneIsNotAnError() throws IOException {
    var shown = new ShownReport();
    var first = html("first");
    shown.show(first);
    Files.delete(first);

    shown.show(html("second"));
    shown.dispose();
    shown.dispose();

    assertThat(dir).isEmptyDirectory();
  }
}
