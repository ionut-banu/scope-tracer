package com.ionutbanu.scopetracer.plugin;

import com.ionutbanu.scopetracer.plugin.model.AnalyzerInvocation;
import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import com.ionutbanu.scopetracer.plugin.model.TraceModelJsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Runs the bundled {@code scope-tracer-analyzer} executable jar as a subprocess against a
 * {@code .jfr} file: once with {@code --format=json}, parsed for the "Tasks" table, and once with
 * the default output, the HTML report shown as the "Timeline".
 *
 * <p>The analyzer's classes are compiled with {@code --enable-preview} (JDK 27) and can never be
 * loaded inside the IDE's own JVM (JBR, JDK 25 in IntelliJ IDEA 2026.3) — running out-of-process
 * on a real JDK 27 and exchanging JSON is the only way to bridge the two JVM versions. {@link
 * AnalyzerJdkSettings} refuses a configured {@code java} older than 27 before it gets here. How the
 * subprocess is bounded (timeout, cancellation, temp files) is {@link AnalyzerInvocation}'s, in
 * {@code :model}.
 */
final class AnalyzerProcessRunner {

  private static final String BUNDLED_ANALYZER_RESOURCE = "/analyzer/analyzer.jar";

  private static final AnalyzerInvocation.JarSource BUNDLED_JAR =
      () -> AnalyzerProcessRunner.class.getResourceAsStream(BUNDLED_ANALYZER_RESOURCE);

  private AnalyzerProcessRunner() {}

  /**
   * @param cancelled polled while the analyzer runs, normally {@code indicator::isCanceled}.
   * @throws CancellationException when {@code cancelled} turned true; the subprocess is dead.
   */
  static PluginTraceModel analyze(Path jfrFile, Path javaExecutable, BooleanSupplier cancelled)
      throws IOException {
    var json =
        AnalyzerInvocation.run(
            javaExecutable, BUNDLED_JAR, jfrFile, tempDir(), AnalyzerInvocation.TIMEOUT, cancelled);
    return TraceModelJsonParser.parse(json);
  }

  /**
   * Writes the analyzer's HTML report for {@code jfrFile} to {@code outputHtml}: a second run of
   * the same bundled jar on the same JDK 27, with no {@code --format} option, so the file is byte
   * for byte what the CLI writes. Same timeout and cancellation as {@link #analyze} (#62).
   *
   * @throws CancellationException when {@code cancelled} turned true; the subprocess is dead.
   */
  static void writeHtml(
      Path jfrFile, Path javaExecutable, Path outputHtml, BooleanSupplier cancelled)
      throws IOException {
    AnalyzerInvocation.writeHtml(
        javaExecutable,
        BUNDLED_JAR,
        jfrFile,
        outputHtml,
        tempDir(),
        AnalyzerInvocation.TIMEOUT,
        cancelled);
  }

  private static Path tempDir() {
    return Path.of(System.getProperty("java.io.tmpdir"));
  }
}
