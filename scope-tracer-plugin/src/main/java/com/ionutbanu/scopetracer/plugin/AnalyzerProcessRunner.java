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
 * {@code .jfr} file and parses its {@code --format=json} output.
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

  private AnalyzerProcessRunner() {}

  /**
   * @param cancelled polled while the analyzer runs, normally {@code indicator::isCanceled}.
   * @throws CancellationException when {@code cancelled} turned true; the subprocess is dead.
   */
  static PluginTraceModel analyze(Path jfrFile, Path javaExecutable, BooleanSupplier cancelled)
      throws IOException {
    var json =
        AnalyzerInvocation.run(
            javaExecutable,
            () -> AnalyzerProcessRunner.class.getResourceAsStream(BUNDLED_ANALYZER_RESOURCE),
            jfrFile,
            Path.of(System.getProperty("java.io.tmpdir")),
            AnalyzerInvocation.TIMEOUT,
            cancelled);
    return TraceModelJsonParser.parse(json);
  }
}
