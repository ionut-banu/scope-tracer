package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Runs the bundled analyzer jar on a recording and returns the JSON it writes ({@link #run}), or
 * writes its HTML report to a file ({@link #writeHtml}), through {@link BoundedProcess}: a run
 * ends when the analyzer exits, when {@code timeout} passes, or when the cancellation check turns
 * true, and in every case the child is dead and the temp files this class made (the extracted
 * jar, the output JSON, the stderr capture) are deleted before it returns. The HTML file is the
 * caller's. The plugin runs both, JSON first, each with its own timeout (#62).
 * Plain Java with no IntelliJ dependency, so it is tested with real child JVMs.
 *
 * <pre>{@code
 * String json = AnalyzerInvocation.run(
 *     javaPath, () -> Plugin.class.getResourceAsStream("/analyzer/analyzer.jar"),
 *     recording, tempDir, AnalyzerInvocation.TIMEOUT, indicator::isCanceled);
 * }</pre>
 */
public final class AnalyzerInvocation {

  /**
   * How long one analysis may run before it is stopped: 10 minutes, the maintainer's choice on
   * #93. The analyzer is quadratic in the number of scopes today (#92: 40,000 scopes took 75 s),
   * so this is a backstop; the user's real control is cancelling the background task. Revisit with
   * #92.
   */
  public static final Duration TIMEOUT = Duration.ofMinutes(10);

  /** Opens the analyzer jar's bytes, for example a plugin resource. */
  @FunctionalInterface
  public interface JarSource {
    InputStream open() throws IOException;
  }

  private AnalyzerInvocation() {}

  /** The analyzer command line: {@code <java> --enable-preview -jar <jar> <in> <out> --format=json}. */
  public static List<String> command(Path java, Path analyzerJar, Path jfrFile, Path outputJson) {
    return List.of(
        java.toString(),
        "--enable-preview",
        "-jar",
        analyzerJar.toString(),
        jfrFile.toString(),
        outputJson.toString(),
        "--format=json");
  }

  /**
   * The command line of the HTML run: {@code <java> --enable-preview -jar <jar> <in> <out>}, the
   * analyzer's default output, byte for byte what the CLI writes without {@code --format}.
   */
  public static List<String> htmlCommand(
      Path java, Path analyzerJar, Path jfrFile, Path outputHtml) {
    return List.of(
        java.toString(),
        "--enable-preview",
        "-jar",
        analyzerJar.toString(),
        jfrFile.toString(),
        outputHtml.toString());
  }

  /**
   * Analyzes {@code jfrFile} and returns the analyzer's JSON.
   *
   * @throws CancellationException when {@code cancelled} turned true (or the thread was
   *     interrupted) before the analyzer finished; the child has been killed.
   * @throws IOException when the analyzer exits non-zero (the message carries the exit code and
   *     the start of its stderr), does not finish within {@code timeout}, or cannot be started.
   */
  public static String run(
      Path java,
      JarSource analyzerJar,
      Path jfrFile,
      Path tempDir,
      Duration timeout,
      BooleanSupplier cancelled)
      throws IOException {
    Path outputJson = null;
    try {
      outputJson = Files.createTempFile(tempDir, "scope-tracer-trace-", ".json");
      var out = outputJson;
      invoke(
          jar -> command(java, jar, jfrFile, out), analyzerJar, jfrFile, tempDir, timeout, cancelled);
      return Files.readString(outputJson, StandardCharsets.UTF_8);
    } finally {
      deleteQuietly(outputJson);
    }
  }

  /**
   * Writes the analyzer's HTML report for {@code jfrFile} to {@code outputHtml}, under the same
   * timeout and cancellation as {@link #run}. {@code outputHtml} is the caller's: it is neither
   * made nor deleted here, whatever the ending; the extracted jar and the stderr capture are.
   *
   * @throws CancellationException when {@code cancelled} turned true before the analyzer finished.
   * @throws IOException when the analyzer exits non-zero, times out or cannot be started.
   */
  public static void writeHtml(
      Path java,
      JarSource analyzerJar,
      Path jfrFile,
      Path outputHtml,
      Path tempDir,
      Duration timeout,
      BooleanSupplier cancelled)
      throws IOException {
    invoke(
        jar -> htmlCommand(java, jar, jfrFile, outputHtml),
        analyzerJar,
        jfrFile,
        tempDir,
        timeout,
        cancelled);
  }

  private interface CommandForJar {
    List<String> command(Path extractedJar);
  }

  /** Extracts the jar, runs the command it gives through {@link BoundedProcess}, maps the end. */
  private static void invoke(
      CommandForJar command,
      JarSource analyzerJar,
      Path jfrFile,
      Path tempDir,
      Duration timeout,
      BooleanSupplier cancelled)
      throws IOException {
    Path jar = null;
    try {
      jar = Files.createTempFile(tempDir, "scope-tracer-analyzer-", ".jar");
      try (InputStream in = analyzerJar.open()) {
        if (in == null) {
          throw new IOException("Bundled analyzer jar not found; the plugin build is broken.");
        }
        Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
      }
      var result =
          BoundedProcess.run(
              command.command(jar), BoundedProcess.Capture.STDERR, timeout, cancelled, tempDir);
      switch (result) {
        case BoundedProcess.Cancelled c ->
            throw new CancellationException("The analysis of " + jfrFile + " was cancelled.");
        case BoundedProcess.TimedOut t ->
            throw new IOException(
                "The analysis of "
                    + jfrFile
                    + " did not finish within "
                    + describe(timeout)
                    + " and was stopped. Large recordings are slow to analyze (#92); an analysis"
                    + " can be cancelled from its progress bar at any time.");
        case BoundedProcess.Exited e when e.exitCode() != 0 ->
            throw new IOException(
                "Analyzer subprocess failed (exit " + e.exitCode() + "): " + e.output());
        case BoundedProcess.Exited e -> {}
      }
    } finally {
      deleteQuietly(jar);
    }
  }

  /** {@code 10 minutes}, {@code 1 second}, {@code 1500 ms}. */
  static String describe(Duration d) {
    if (d.toSeconds() >= 60 && d.toSecondsPart() == 0 && d.toNanosPart() == 0) {
      return plural(d.toMinutes(), "minute");
    }
    if (d.toNanosPart() == 0) {
      return plural(d.toSeconds(), "second");
    }
    return d.toMillis() + " ms";
  }

  private static String plural(long n, String unit) {
    return n + " " + unit + (n == 1 ? "" : "s");
  }

  private static void deleteQuietly(Path p) {
    if (p == null) return;
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // a temp file left in java.io.tmpdir is the worst case; never mask the real outcome
    }
  }
}
