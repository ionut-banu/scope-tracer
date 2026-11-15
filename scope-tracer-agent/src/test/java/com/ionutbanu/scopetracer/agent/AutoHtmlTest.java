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
  void warningsEscapeThePathsTheyName(@TempDir Path dir) throws Exception {
    Path jfr = record(dir.resolve("r.jfr"), "escaped-scope");
    Path outDir = Files.createDirectory(dir.resolve("x\ny"));
    Files.createDirectory(outDir.resolve("r.html"));
    var config = AgentConfig.parse("output.dir=" + outDir);

    var captured = new java.io.ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      AutoHtml.write(jfr, config);
    } finally {
      System.setErr(original);
    }

    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList())
        .containsExactly(
            "[scope-tracer] WARN cannot write HTML report to "
                + dir
                + "/x\\ny/r.html: a directory is in the way");
  }

  @Test
  void aReportThatCannotBeWrittenNamesTheEscapedPathAndTheReason(@TempDir Path dir)
      throws Exception {
    Path jfr = record(dir.resolve("r.jfr"), "unwritable-scope");
    Path file = Files.writeString(dir.resolve("a\nfile"), "x");
    var config = AgentConfig.parse("output.dir=" + file.resolve("sub"));

    var captured = new java.io.ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      AutoHtml.write(jfr, config);
    } finally {
      System.setErr(original);
    }

    assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList())
        .singleElement()
        .asString()
        .startsWith(
            "[scope-tracer] WARN cannot write HTML report to " + dir + "/a\\nfile/sub/r.html: ")
        .contains("FileSystemException: ")
        .contains("a\\nfile/sub");
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

  /**
   * What {@code AutoHtml.write} prints on stderr for {@code dest} with the gate set to {@code v}.
   */
  private static String stderrOfWrite(Path dest, Boolean v) {
    var captured = new java.io.ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
    try {
      AgentLog.setVerbose(v);
      AutoHtml.write(dest, AgentConfig.parse(null));
    } finally {
      AgentLog.setVerbose(null);
      System.setErr(original);
    }
    return captured.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void nothingRecordedLineFollowsTheGateSetAfterAgentLogInitialised(@TempDir Path dir) {
    Path missing = dir.resolve("missing.jfr");
    String line = "[scope-tracer] DEBUG skipping auto-HTML: nothing recorded in " + missing;

    assertThat(stderrOfWrite(missing, true).strip()).isEqualTo(line);
    assertThat(stderrOfWrite(missing, false)).isEmpty();
    assertThat(stderrOfWrite(missing, true).strip()).isEqualTo(line);
  }

  @Test
  void theGateOverridesTheProperty(@TempDir Path dir) {
    Path missing = dir.resolve("missing.jfr");
    // the property is not set in this JVM, so null (property) prints nothing; false stays quiet
    assertThat(stderrOfWrite(missing, null)).isEmpty();
    assertThat(stderrOfWrite(missing, false)).isEmpty();
  }

  @Test
  void aRecordingWithoutScopesSaysSoAndNotBelowMinScopes(@TempDir Path dir) throws Exception {
    Path jfr = dir.resolve("noscopes.jfr");
    try (var r = new Recording()) {
      r.setDestination(jfr);
      r.start();
      r.stop();
    }
    String err = stderrOfWrite(jfr, true);

    assertThat(err.strip())
        .isEqualTo("[scope-tracer] DEBUG skipping auto-HTML: no scopes recorded in " + jfr);
  }
}
