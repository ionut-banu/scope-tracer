package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The analyzer invocation against a stand-in analyzer jar, built here from {@code
 * children/FakeAnalyzer.java} and run with the real command line. Each test gives the invocation
 * its own temp directory and lists it afterwards: the extracted jar and the output JSON are gone
 * whatever the ending.
 */
class AnalyzerInvocationTest {

  private static final Duration GENEROUS = Duration.ofSeconds(60);

  @TempDir static Path build;
  private static Path fakeAnalyzerJar;

  @TempDir Path runnerTemp;
  @TempDir Path inputs;

  @BeforeAll
  static void buildFakeAnalyzerJar() throws IOException {
    var classes = Files.createDirectories(build.resolve("classes"));
    int rc =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                Children.source("FakeAnalyzer").toString());
    assertThat(rc).as("javac FakeAnalyzer.java").isZero();
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "FakeAnalyzer");
    fakeAnalyzerJar = build.resolve("fake-analyzer.jar");
    try (var jar = new JarOutputStream(Files.newOutputStream(fakeAnalyzerJar), manifest);
        var classFiles = Files.list(classes)) {
      for (var classFile : classFiles.toList()) {
        jar.putNextEntry(new JarEntry(classFile.getFileName().toString()));
        jar.write(Files.readAllBytes(classFile));
        jar.closeEntry();
      }
    }
  }

  @AfterEach
  void nothingLeftBehind() throws IOException {
    assertThat(Children.aliveChildren()).isEmpty();
    try (var left = Files.list(runnerTemp)) {
      assertThat(left).isEmpty();
    }
  }

  private Path recording(String mode) throws IOException {
    return Files.writeString(inputs.resolve(mode + ".jfr"), mode);
  }

  private static InputStream jar() throws IOException {
    return Files.newInputStream(fakeAnalyzerJar);
  }

  private String run(Path jfr, Duration timeout, java.util.function.BooleanSupplier cancelled)
      throws IOException {
    return AnalyzerInvocation.run(
        Children.JAVA, AnalyzerInvocationTest::jar, jfr, runnerTemp, timeout, cancelled);
  }

  @Test
  void successReturnsTheJsonAndDeletesTheTempFiles() throws IOException {
    var jfr = recording("ok");

    var json = assertTimeoutPreemptively(GENEROUS, () -> run(jfr, GENEROUS, () -> false));

    assertThat(json).isEqualTo("{\"scopes\":[]}");
  }

  @Test
  void aNonZeroExitFailsWithTheExitCodeAndStderrLineAndDeletesTheTempFiles() throws IOException {
    var jfr = recording("fail");

    assertTimeoutPreemptively(
        GENEROUS,
        () ->
            assertThatThrownBy(() -> run(jfr, GENEROUS, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit 2")
                .hasMessageContaining("error: not a recording"));
  }

  @Test
  void aTimeoutFailsWithinFiveSecondsNamingTheFileAndHowToCancelAndDeletesTheTempFiles()
      throws IOException {
    var jfr = recording("hang");

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThatThrownBy(() -> run(jfr, Duration.ofSeconds(1), () -> false))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(CancellationException.class)
                .hasMessageContaining(jfr.toString())
                .hasMessageContaining("did not finish within 1 second")
                .hasMessageContaining("cancel")
                .hasMessageContaining("#92"));
  }

  @Test
  void cancellationIsReportedAsCancelledNotAsAFailureAndDeletesTheTempFiles() throws IOException {
    var jfr = recording("hang");
    var started = System.nanoTime();
    var cancelled = new AtomicBoolean();

    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () ->
            assertThatThrownBy(
                    () ->
                        run(
                            jfr,
                            GENEROUS,
                            () -> {
                              if (System.nanoTime() - started > Duration.ofSeconds(2).toNanos()) {
                                cancelled.set(true);
                              }
                              return cancelled.get();
                            }))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("cancelled"));
  }

  @Test
  void theCommandLineIsTheAnalyzersCli() {
    var java = Path.of("/jdk27/bin/java");
    var jar = Path.of("/tmp/scope-tracer-analyzer-1.jar");
    var in = Path.of("/r/in.jfr");
    var out = Path.of("/tmp/scope-tracer-trace-1.json");

    assertThat(AnalyzerInvocation.command(java, jar, in, out))
        .isEqualTo(
            List.of(
                "/jdk27/bin/java",
                "--enable-preview",
                "-jar",
                "/tmp/scope-tracer-analyzer-1.jar",
                "/r/in.jfr",
                "/tmp/scope-tracer-trace-1.json",
                "--format=json"));
  }

  @Test
  void theProductionTimeoutIsTenMinutes() {
    assertThat(AnalyzerInvocation.TIMEOUT).isEqualTo(Duration.ofMinutes(10));
    assertThat(AnalyzerInvocation.describe(AnalyzerInvocation.TIMEOUT)).isEqualTo("10 minutes");
  }
}
