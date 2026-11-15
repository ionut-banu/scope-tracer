package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a {@code [scope-tracer] WARN} line says when an exception caused it (issue #132): the cause
 * chain, one escaped line, and the program unchanged. Each test runs {@link ExitSubject} in a child
 * JVM with the agent fat-jar attached.
 */
class AgentWarnReasonIT {

  private static final String WARN = ChildJvm.AGENT_PREFIX + " WARN ";

  private static String agent;
  private static Path fatJar;

  @BeforeAll
  static void requireFatJar() {
    agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
    fatJar = Path.of(agent);
  }

  private static String javaagent(Path jar, String args) {
    return "-javaagent:" + jar + (args.isEmpty() ? "" : "=" + args);
  }

  // --- criterion 1 ---

  @ParameterizedTest
  @ValueSource(strings = {"", "verbose"})
  void anAgentJarThatCannotRetransformNamesTheJdksReason(String args, @TempDir Path dir)
      throws Exception {
    Path jar = withoutRetransform(dir);
    var with = ChildJvm.run(dir, List.of(javaagent(jar, args)), "scopes", "s");
    var without = ChildJvm.run(dir, List.of(), "scopes", "s");

    with.assertSameProgramAs(without);
    assertThat(with.exitCode()).isZero();
    assertThat(with.warnings())
        .containsExactly(
            WARN
                + "not tracing: instrumentation failed: IllegalStateException: Could not install"
                + " class file transformer; caused by UnsupportedOperationException: adding"
                + " retransformable transformers is not supported in this environment");
  }

  // --- criterion 2 ---

  /**
   * The issue expected {@code ; caused by ClassNotFoundException: ...} here. The JDK gives none:
   * the agent's classes are on the bootstrap loader, and a missing class that loader cannot find is
   * a {@code NoClassDefFoundError} with no cause (a class from the application loader gets the
   * {@code ClassNotFoundException} as cause, which is what the issue's probe saw). So the lines are
   * as on main, and still end in a reason.
   */
  @Test
  void withoutJfrModulesBothLinesNameTheMissingClassWithoutACause(@TempDir Path dir)
      throws Exception {
    Path jfr = dir.resolve("x.jfr");
    var limit = List.of("--limit-modules", "java.base,java.instrument");
    var with =
        ChildJvm.run(dir, withAgent(limit, javaagent(fatJar, "record=" + jfr)), "scopes", "s");
    var without = ChildJvm.run(dir, limit, "scopes", "s");

    with.assertSameProgramAs(without);
    assertThat(with.exitCode()).isZero();
    assertThat(with.warnings())
        .containsExactly(
            WARN + "no auto-HTML: NoClassDefFoundError: jdk/jfr/FlightRecorderListener",
            WARN
                + "not recording: cannot record to "
                + jfr
                + ": NoClassDefFoundError: jdk/jfr/Recording");
  }

  // --- criterion 3 ---

  @Test
  void aRecordPathUnderARegularFileKeepsItsLine(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("afile"), "x");
    var with =
        ChildJvm.run(
            dir, List.of(javaagent(fatJar, "record=" + file.resolve("x.jfr"))), "scopes", "s");

    assertThat(with.exitCode()).isZero();
    assertThat(with.warnings())
        .containsExactly(
            WARN
                + "not recording: cannot record to "
                + file.resolve("x.jfr")
                + ": FileAlreadyExistsException: "
                + file);
  }

  @Test
  void anOutputDirThatCannotBeCreatedKeepsItsReason(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("afile"), "x");
    Path jfr = dir.resolve("t.jfr");
    var with =
        ChildJvm.run(
            dir,
            List.of(javaagent(fatJar, "record=" + jfr + ",output.dir=" + file.resolve("sub"))),
            "scopes",
            "s");

    assertThat(with.exitCode()).isZero();
    assertThat(with.warnings())
        .singleElement()
        .isEqualTo(
            WARN
                + "cannot write HTML report to "
                + file.resolve("sub").resolve("t.html")
                + ": FileSystemException: "
                + file.resolve("sub")
                + ": Not a directory");
  }

  @Test
  void aReadOnlyOutputDirKeepsItsReason(@TempDir Path dir) throws Exception {
    Path readOnly = Files.createDirectory(dir.resolve("ro"));
    Path jfr = dir.resolve("t.jfr");
    Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      var with =
          ChildJvm.run(
              dir,
              List.of(javaagent(fatJar, "record=" + jfr + ",output.dir=" + readOnly)),
              "scopes",
              "s");

      assertThat(with.warnings())
          .singleElement()
          .asString()
          .matches(
              java.util.regex.Pattern.quote(
                      WARN
                          + "cannot write HTML report to "
                          + readOnly.resolve("t.html")
                          + ": AccessDeniedException: "
                          + readOnly
                          + "/.t.html.")
                  + "[0-9a-f]+\\.tmp");
    } finally {
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  // --- criterion 14 ---

  /**
   * {@code BootstrapEntry}, from the application class loader, after the jar is appended: the
   * bootstrap copy's {@code premainFromAppendedJar} is a stub that throws with a cause, so line I
   * is printed from the copy that has not been told about the jar's classes. No {@code
   * IllegalAccessError}.
   */
  @Test
  void aStartupFailureAfterTheJarIsAppendedNamesItsCause(@TempDir Path dir) throws Exception {
    Path renamed = Files.copy(fatJar, dir.resolve("agent.jar"));
    Path stub =
        compile(
            dir,
            "stub",
            Map.of(
                "com/ionutbanu/scopetracer/agent/ScopeTracerAgent",
                """
                package com.ionutbanu.scopetracer.agent;
                public final class ScopeTracerAgent {
                  static void premainFromAppendedJar(
                      String args, java.lang.instrument.Instrumentation inst, java.nio.file.Path jar) {
                    throw new IllegalStateException(
                        "stub failed", new java.io.IOException("stub cause"));
                  }
                }
                """));
    Path helper = helperAgent(dir);

    var with =
        ChildJvm.run(
            dir,
            List.of(
                "-Xbootclasspath/a:" + stub, "-Drenamed.jar=" + renamed, "-javaagent:" + helper),
            List.of(renamed.toString()),
            "scopes",
            "s");

    assertThat(with.stderr()).doesNotContain("IllegalAccessError");
    assertThat(with.warnings())
        .containsExactly(
            WARN
                + "not tracing: agent startup failed: IllegalStateException: stub failed; caused by"
                + " IOException: stub cause");
    assertThat(with.agentLines()).anyMatch(l -> l.contains("to the bootstrap class path"));
  }

  // --- criterion 15 ---

  @Test
  void aNewlineInARecordPathStaysOnOneLine(@TempDir Path dir) throws Exception {
    Path readOnly = Files.createDirectory(dir.resolve("ro"));
    Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      String arg = "record=" + readOnly + "/a\nb.jfr";
      var with = ChildJvm.run(dir, List.of(javaagent(fatJar, arg)), "scopes", "s");
      var without = ChildJvm.run(dir, List.of(), "scopes", "s");

      with.assertSameProgramAs(without);
      assertThat(with.warnings()).hasSize(1);
      String line = with.warnings().get(0);
      assertThat(line).startsWith(WARN + "not recording: cannot record to ");
      assertThat(line.split(java.util.regex.Pattern.quote("a\\nb.jfr"), -1))
          .as("the escaped path, once in the prefix and once in the message: %s", line)
          .hasSize(3);
      assertThat(with.stderr().lines().filter(l -> l.startsWith("b.jfr"))).isEmpty();
    } finally {
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  // --- helpers ---

  private static List<String> withAgent(List<String> jvmArgs, String javaagent) {
    var all = new ArrayList<>(jvmArgs);
    all.add(javaagent);
    return all;
  }

  /**
   * The fat-jar under its own name, in a new directory, with no {@code Can-Retransform-Classes}.
   */
  private static Path withoutRetransform(Path dir) throws Exception {
    Path jar = Files.createDirectory(dir.resolve("norc")).resolve(fatJar.getFileName());
    try (var in = new JarFile(fatJar.toFile());
        var out = new JarOutputStream(Files.newOutputStream(jar), manifestWithout(in))) {
      for (var entry : Collections.list(in.entries())) {
        if (entry.getName().equals(JarFile.MANIFEST_NAME)) continue;
        out.putNextEntry(new JarEntry(entry.getName()));
        if (!entry.isDirectory()) in.getInputStream(entry).transferTo(out);
        out.closeEntry();
      }
    }
    return jar;
  }

  private static Manifest manifestWithout(JarFile in) throws Exception {
    var manifest = new Manifest(in.getManifest());
    assertThat(manifest.getMainAttributes().remove(new Attributes.Name("Can-Retransform-Classes")))
        .as("the fat-jar's manifest has Can-Retransform-Classes")
        .isNotNull();
    return manifest;
  }

  /** A {@code -javaagent} jar in the agent's package whose {@code premain} calls BootstrapEntry. */
  private static Path helperAgent(Path dir) throws Exception {
    String name = "com.ionutbanu.scopetracer.agent.CallsBootstrapEntry";
    Path classes =
        compile(
            dir,
            "helper",
            Map.of(
                name.replace('.', '/'),
                """
                package com.ionutbanu.scopetracer.agent;
                public final class CallsBootstrapEntry {
                  public static void premain(String args, java.lang.instrument.Instrumentation inst)
                      throws Exception {
                    var url = java.nio.file.Path.of(System.getProperty("renamed.jar")).toUri().toURL();
                    BootstrapEntry.enter(
                        args,
                        inst,
                        new java.security.CodeSource(url, (java.security.cert.Certificate[]) null));
                  }
                }
                """));
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", name);
    Path jar = dir.resolve("helper.jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      out.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
      out.write(Files.readAllBytes(classes.resolve(name.replace('.', '/') + ".class")));
      out.closeEntry();
    }
    return jar;
  }

  /** Compiles {@code sources} (path without {@code .java} to source) into {@code dir/<id>}. */
  private static Path compile(Path dir, String id, Map<String, String> sources) throws Exception {
    Path src = dir.resolve(id + "-src");
    Path classes = Files.createDirectories(dir.resolve(id));
    var args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", fatJar.toString()));
    for (var e : sources.entrySet()) {
      Path file = src.resolve(e.getKey() + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue());
      args.add(file.toString());
    }
    var err = new ByteArrayOutputStream();
    int rc = ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new));
    assertThat(rc).as(err.toString(StandardCharsets.UTF_8)).isZero();
    return classes;
  }
}
