package com.ionutbanu.scopetracer.demos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The files {@code jbang demo@ionut-banu/scope-tracer} reads from the repository: the catalog and
 * the single-file demo. Surefire runs in the module directory, so the repository root is its
 * parent. JBang itself is not run here (a run needs a release on Central and a network).
 */
class JbangFilesTest {

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Path CATALOG = ROOT.resolve("jbang-catalog.json");
  private static final Path DEMO =
      ROOT.resolve("scope-tracer-demos/jbang/ScopeTracerDemo.java").normalize();

  @Test
  void theDemoCompilesWithoutAnyScopeTracerClass(@TempDir Path out) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();

    // No class path: the file must compile with the JDK alone. Its //DEPS line is a comment to
    // javac, and the agent jar it names is only used at run time.
    int result =
        javac.run(
            null,
            null,
            null,
            "--enable-preview",
            "--release",
            "27",
            "-d",
            out.toString(),
            DEMO.toString());

    assertThat(result).as("javac exit code").isZero();
    assertThat(out.resolve("ScopeTracerDemo.class")).exists();
  }

  @Test
  void theDemoUsesNoScopeTracerApi() throws IOException {
    List<String> lines = Files.readAllLines(DEMO);

    assertThat(lines).noneMatch(l -> l.startsWith("import com.ionutbanu"));
    assertThat(lines).noneMatch(l -> l.contains("TracedScope"));
    // The agent version is allowed on one line only, the //DEPS line the release check reads.
    assertThat(lines)
        .filteredOn(l -> l.matches(".*scope-tracer.*\\d+\\.\\d+\\.\\d+.*"))
        .singleElement()
        .asString()
        .startsWith("//DEPS com.ionutbanu:scope-tracer-agent:");
    assertThat(lines).anyMatch(l -> l.startsWith("//JAVA 27+"));
    assertThat(lines).anyMatch(l -> l.equals("//PREVIEW"));
  }

  @Test
  void theCatalogDefinesBothAliasesAndTheDemoSourceExists() throws IOException {
    String catalog = Files.readString(CATALOG);

    assertThat(catalog).contains("\"analyze\": {", "\"demo\": {");
    assertThat(catalog)
        .contains("\"script-ref\": \"scope-tracer-demos/jbang/ScopeTracerDemo.java\"");
    assertThat(DEMO).isRegularFile();
    // Both aliases need JDK 27; the demo needs the preview flag for StructuredTaskScope.
    assertThat(catalog.split("\"java\": \"27\\+\"", -1)).hasSize(3);
    assertThat(catalog).contains("\"enable-preview\": true");
  }

  @Test
  void theCatalogNamesNoMovingVersion() throws IOException {
    String catalog = Files.readString(CATALOG);

    assertThat(catalog).doesNotContain("SNAPSHOT", "RELEASE", "LATEST", "${");
    assertThat(catalog.toLowerCase(java.util.Locale.ROOT)).doesNotContain("releases/latest");
    assertThat(Files.readString(DEMO).toLowerCase(java.util.Locale.ROOT))
        .doesNotContain("releases/latest");
  }

  @Test
  void theCatalogItselfNamesAPinnedScopeTracerArtifact() throws IOException {
    String catalog = Files.readString(CATALOG);

    // The analyze alias: the released -executable jar, or a coordinate, at a literal version.
    assertThat(catalog)
        .containsPattern(
            "releases/download/v\\d+\\.\\d+\\.\\d+/scope-tracer-analyzer-\\d+\\.\\d+\\.\\d+-executable\\.jar"
                + "|com\\.ionutbanu:scope-tracer-[a-z]+:\\d+\\.\\d+\\.\\d+");
  }
}
