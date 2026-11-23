package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What each analyzer jar carries for logging (#58). The plain jar is a library: it configures no
 * logging and brings no binding, so a host's SLF4J setup decides where the parser's lines go. The
 * {@code -executable} jar is the CLI: it bundles Logback and the one {@code logback.xml}.
 */
class AnalyzerJarsIT {

  /** Every file name a common SLF4J backend reads its configuration from. */
  private static final List<String> LOGGING_CONFIG =
      List.of(
          "logback.xml",
          "logback-test.xml",
          "logback.groovy",
          "logback.scmo",
          "logback-test.scmo",
          "log4j2.xml",
          "log4j2.properties",
          "log4j.properties",
          "simplelogger.properties",
          "logging.properties");

  @TempDir Path dir;

  @Test
  void plainJarHasNoLoggingConfigurationAndNoBinding() throws Exception {
    var names = entries(plainJar());
    assertThat(names).contains("com/ionutbanu/scopetracer/analyzer/JfrParser.class");
    assertThat(names).noneMatch(n -> LOGGING_CONFIG.contains(fileName(n)));
    assertThat(names)
        .noneMatch(n -> n.startsWith("ch/qos/logback/"))
        .noneMatch(n -> n.startsWith("org/slf4j/"))
        .noneMatch(n -> n.startsWith("META-INF/services/"));
  }

  @Test
  void executableJarHasLogbackAndOnlyItsOwnConfiguration() throws Exception {
    var names = entries(AnalyzerCliIT.executableJar());
    assertThat(names).contains("logback.xml").anyMatch(n -> n.startsWith("ch/qos/logback/"));
    assertThat(names.stream().filter(n -> LOGGING_CONFIG.contains(fileName(n))))
        .containsExactly("logback.xml");
  }

  /**
   * A host with the plain jar, core and {@code slf4j-api}, which is what a library user gets now
   * that Logback is optional, and no binding of its own: the plain jar's classes load and parse
   * without Logback, SLF4J falls back to no-op, and nothing from the analyzer reaches stdout. That
   * the POM no longer passes Logback on is checked with {@code dependency:tree}, as the issue says.
   */
  @Test
  void libraryUserWithoutABindingGetsNoAnalyzerOutputOnStdout() throws Exception {
    var jfr = TestRecordings.leftOpen(dir.resolve("open.jfr"), "lib-open", 1, 1);
    var command =
        AnalyzerCliIT.javaCommand(
            AnalyzerCliIT.java(),
            "-cp",
            AnalyzerCliIT.classPath(
                plainJar(), jarOf(TracedScope.class), jarOf(org.slf4j.Logger.class), testClasses()),
            LibraryUser.class.getName(),
            jfr.toString());
    var out = dir.resolve("out.txt");
    var err = dir.resolve("err.txt");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start();
    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    String stderr = Files.readString(err, StandardCharsets.UTF_8);

    assertThat(process.exitValue()).as(stderr).isZero();
    assertThat(Files.readString(out, StandardCharsets.UTF_8)).isEqualTo("scopes 2\n");
    assertThat(stderr).doesNotContain("multiple SLF4J providers").doesNotContain("logback");
  }

  /** The host program of {@link #libraryUserWithoutABindingGetsNoAnalyzerOutputOnStdout}. */
  public static final class LibraryUser {
    private LibraryUser() {}

    public static void main(String[] args) throws Exception {
      var model = JfrParser.parse(Path.of(args[0]));
      System.out.println("scopes " + model.scopes().size());
    }
  }

  // --- helpers ---

  private static Path plainJar() {
    var jar = Path.of(System.getProperty("analyzerPlainJar", "missing-plain.jar"));
    assertThat(jar).as("the plain analyzer jar; run with mvn verify").exists();
    return jar;
  }

  private static Path testClasses() throws URISyntaxException {
    return Path.of(LibraryUser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static Path jarOf(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static List<String> entries(Path jar) throws Exception {
    try (var zip = new ZipFile(jar.toFile())) {
      var names = new ArrayList<String>();
      for (var entry : Collections.list(zip.entries())) names.add(entry.getName());
      return names;
    }
  }

  private static String fileName(String entry) {
    return entry.substring(entry.lastIndexOf('/') + 1);
  }
}
