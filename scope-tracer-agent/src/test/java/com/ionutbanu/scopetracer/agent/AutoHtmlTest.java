package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AutoHtml} on its own, without a child JVM: where the report goes and what is left on disk.
 * The recording comes from {@link TracedScope}; no agent is attached to this JVM, so the events are
 * emitted once.
 */
class AutoHtmlTest {

  @Test
  void notShuttingDownWhileTheJvmRuns() {
    assertThat(AutoHtml.shuttingDown()).isFalse();
  }

  @Test
  void writesTheReportBesideTheRecordingAndNothingElse(@TempDir Path dir) throws Exception {
    Path jfr = record(dir.resolve("r.jfr"), "unit-scope");

    AutoHtml.write(jfr, AgentConfig.parse(null));

    assertThat(Files.readString(dir.resolve("r.html"))).contains("unit-scope");
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder("r.jfr", "r.html");
    }
  }

  @Test
  void replacesAnExistingReport(@TempDir Path dir) throws Exception {
    Path jfr = record(dir.resolve("r.jfr"), "fresh-scope");
    Files.writeString(dir.resolve("r.html"), "stale");

    AutoHtml.write(jfr, AgentConfig.parse(null));

    assertThat(Files.readString(dir.resolve("r.html"))).contains("fresh-scope");
  }

  @Test
  void directoryAtTheReportPathIsLeftAloneAndNoReportIsWritten(@TempDir Path dir) throws Exception {
    Path jfr = record(dir.resolve("r.jfr"), "blocked-scope");
    Path inTheWay = Files.createDirectory(dir.resolve("r.html"));

    AutoHtml.write(jfr, AgentConfig.parse(null));

    assertThat(inTheWay).isEmptyDirectory();
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder("r.jfr", "r.html");
    }
  }

  @Test
  void emptyRecordingFileGivesNoReport(@TempDir Path dir) throws Exception {
    Path jfr = Files.createFile(dir.resolve("empty.jfr"));

    AutoHtml.write(jfr, AgentConfig.parse(null));

    assertThat(dir.resolve("empty.html")).doesNotExist();
  }

  @Test
  void htmlPathFollowsOutputDirAndSuffix() {
    Path jfr = Path.of("/rec/a.jfr");
    assertThat(AutoHtml.htmlPath(jfr, AgentConfig.parse(null))).isEqualTo(Path.of("/rec/a.html"));
    assertThat(AutoHtml.htmlPath(jfr, AgentConfig.parse("output.dir=/out,output.suffix=.r.html")))
        .isEqualTo(Path.of("/out/a.r.html"));
    assertThat(AutoHtml.htmlPath(Path.of("/rec/b.data"), AgentConfig.parse(null)))
        .isEqualTo(Path.of("/rec/b.data.html"));
  }

  private static Path record(Path jfr, String scopeName) throws Exception {
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      try (var scope = TracedScope.open(scopeName)) {
        scope.fork(() -> "ok");
        scope.join();
      }
      recording.stop();
      recording.dump(jfr);
    }
    return jfr;
  }
}
