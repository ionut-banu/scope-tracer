package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which navigations the embedded browser lets through (#62): the report's own file, with or
 * without a fragment (the overview's {@code #scope-N} links), and nothing else.
 */
class ReportNavigationTest {

  @TempDir Path dir;

  private Path report() throws IOException {
    return Files.writeString(dir.resolve("scope-tracer-report-1.html"), "<html/>");
  }

  @Test
  void theReportsOwnFileUrlIsAllowed() throws IOException {
    var report = report();

    assertThat(ReportNavigation.allowed(report.toUri().toString(), report)).isTrue();
  }

  @Test
  void aFragmentOfTheReportIsAllowedSoTheOverviewCanScroll() throws IOException {
    var report = report();

    assertThat(ReportNavigation.allowed(report.toUri() + "#scope-3", report)).isTrue();
  }

  @Test
  void theSingleSlashFileUrlFormIsTheSameFile() throws IOException {
    var report = report();

    assertThat(ReportNavigation.allowed("file:" + report.toAbsolutePath(), report)).isTrue();
  }

  @Test
  void theSameFileReachedThroughASymlinkedDirectoryIsAllowed() throws IOException {
    var report = report();
    var link = Files.createSymbolicLink(dir.resolve("link"), dir);

    assertThat(
            ReportNavigation.allowed(
                link.resolve(report.getFileName()).toUri().toString(), report))
        .isTrue();
  }

  @Test
  void aPathWithSpacesIsMatchedAfterDecoding() throws IOException {
    var spaced = Files.createDirectories(dir.resolve("with space"));
    var report = Files.writeString(spaced.resolve("r.html"), "<html/>");

    assertThat(report.toUri().toString()).contains("%20");
    assertThat(ReportNavigation.allowed(report.toUri().toString(), report)).isTrue();
  }

  @Test
  void anotherFileIsRefusedEvenNextToTheReport() throws IOException {
    var report = report();
    var other = Files.writeString(dir.resolve("other.html"), "<html/>");

    assertThat(ReportNavigation.allowed(other.toUri().toString(), report)).isFalse();
    assertThat(ReportNavigation.allowed(dir.toUri().toString(), report)).isFalse();
  }

  @Test
  void everythingThatIsNotAFileUrlIsRefused() throws IOException {
    var report = report();

    for (var url :
        new String[] {
          "https://example.com/",
          "http://localhost:63342/x.html",
          "about:blank",
          "blob:file:///0b3c-11",
          "data:text/html,<p>x</p>",
          "javascript:alert(1)",
          "not a url at all",
          "",
          "file://otherhost" + report.toAbsolutePath()
        }) {
      assertThat(ReportNavigation.allowed(url, report)).as(url).isFalse();
    }
    assertThat(ReportNavigation.allowed(null, report)).isFalse();
  }

  @Test
  void withNoReportNothingIsAllowed() throws IOException {
    var report = report();

    assertThat(ReportNavigation.allowed(report.toUri().toString(), null)).isFalse();
  }
}
