package com.ionutbanu.scopetracer.plugin;

import com.ionutbanu.scopetracer.plugin.model.PluginTraceModel;
import com.ionutbanu.scopetracer.plugin.model.TraceModelJsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Runs the bundled {@code scope-tracer-analyzer} executable jar as a subprocess against a
 * {@code .jfr} file and parses its {@code --format=json} output.
 *
 * <p>The analyzer's classes are compiled with {@code --enable-preview} (JDK 26) and can never be
 * loaded inside the IDE's own JVM (JBR, currently JDK 21) — running out-of-process on a real JDK
 * 26 and exchanging JSON is the only way to bridge the two JVM versions.
 */
final class AnalyzerProcessRunner {

  private static final String BUNDLED_ANALYZER_RESOURCE = "/analyzer/analyzer.jar";

  private AnalyzerProcessRunner() {}

  static PluginTraceModel analyze(Path jfrFile, Path javaExecutable) throws IOException {
    var analyzerJar = extractBundledAnalyzerJar();
    var outputJson = Files.createTempFile("scope-tracer-trace-", ".json");
    try {
      var process =
          new ProcessBuilder(
                  javaExecutable.toString(),
                  "--enable-preview",
                  "-jar",
                  analyzerJar.toString(),
                  jfrFile.toString(),
                  outputJson.toString(),
                  "--format=json")
              .start();

      String stderr;
      try (InputStream errStream = process.getErrorStream()) {
        stderr = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!waitFor(process)) {
        process.destroyForcibly();
        throw new IOException("Analyzer subprocess timed out for " + jfrFile);
      }
      if (process.exitValue() != 0) {
        throw new IOException(
            "Analyzer subprocess failed (exit " + process.exitValue() + "): " + stderr);
      }
      var json = Files.readString(outputJson, StandardCharsets.UTF_8);
      return TraceModelJsonParser.parse(json);
    } finally {
      Files.deleteIfExists(outputJson);
      Files.deleteIfExists(analyzerJar);
    }
  }

  private static boolean waitFor(Process process) throws IOException {
    try {
      return process.waitFor(2, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for analyzer subprocess", e);
    }
  }

  private static Path extractBundledAnalyzerJar() throws IOException {
    var tempJar = Files.createTempFile("scope-tracer-analyzer-", ".jar");
    try (InputStream in =
        AnalyzerProcessRunner.class.getResourceAsStream(BUNDLED_ANALYZER_RESOURCE)) {
      if (in == null) {
        throw new IOException(
            "Bundled analyzer jar not found at "
                + BUNDLED_ANALYZER_RESOURCE
                + " — plugin build is broken.");
      }
      Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
    }
    return tempJar;
  }
}
