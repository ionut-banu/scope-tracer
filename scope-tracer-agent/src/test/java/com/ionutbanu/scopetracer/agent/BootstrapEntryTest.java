package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first {@code premain}'s only step, when the agent's classes were loaded by the application
 * class loader (the jar is not under the name its {@code Boot-Class-Path} lists, issue #113): find
 * the jar, append it to the bootstrap search path, and enter the bootstrap copy. Every way that can
 * fail must leave one warning naming the fix and throw nothing (criterion 11).
 */
class BootstrapEntryTest {

  private static final String WARN_NOT_TRACING = "[scope-tracer] WARN not tracing: ";
  private static final String FIX_RENAME = "rename the jar to ";
  private static final String FIX_FLAG = "or add -Xbootclasspath/a:";

  /** A fake {@link Instrumentation}: records every jar appended, or throws when told to. */
  private static final class FakeInstrumentation {
    final List<String> appended = new ArrayList<>();
    RuntimeException failure;

    Instrumentation proxy() {
      return (Instrumentation)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Instrumentation.class},
              (self, method, args) -> {
                if (method.getName().equals("appendToBootstrapClassLoaderSearch")) {
                  if (failure != null) throw failure;
                  appended.add(((JarFile) args[0]).getName());
                  return null;
                }
                throw new UnsupportedOperationException(method.getName());
              });
    }
  }

  /** Runs the step with stderr captured; returns the lines it printed. */
  private static List<String> enter(CodeSource source, FakeInstrumentation inst) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      assertThatCode(() -> BootstrapEntry.enter("record", inst.proxy(), source))
          .doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private static CodeSource codeSource(String url) throws Exception {
    return new CodeSource(URI.create(url).toURL(), (Certificate[]) null);
  }

  /** A jar whose manifest lists {@code bootClassPath}, as the fat-jar's does. */
  private static Path jarListing(Path path, String bootClassPath) throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Boot-Class-Path", bootClassPath);
    Files.createDirectories(path.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
      out.flush();
    }
    return path;
  }

  @Test
  void nullCodeSourceWarnsOnceAndAppendsNothing() {
    var inst = new FakeInstrumentation();
    List<String> lines = enter(null, inst);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0))
        .startsWith(WARN_NOT_TRACING)
        .contains(FIX_RENAME + "scope-tracer-agent-<version>-agent.jar")
        .contains(FIX_FLAG + "<path to the jar>");
    assertThat(inst.appended).isEmpty();
  }

  @Test
  void codeSourceWithoutLocationWarnsOnceAndAppendsNothing() {
    var inst = new FakeInstrumentation();
    List<String> lines = enter(new CodeSource(null, (Certificate[]) null), inst);

    assertThat(lines).singleElement().asString().startsWith(WARN_NOT_TRACING).contains(FIX_FLAG);
    assertThat(inst.appended).isEmpty();
  }

  @Test
  void urlThatIsNotAFileWarnsOnceNamingItAndAppendsNothing() throws Exception {
    var inst = new FakeInstrumentation();
    List<String> lines = enter(codeSource("https://example.invalid/agent.jar"), inst);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains("https://example.invalid/agent.jar")
        .contains(FIX_RENAME)
        .contains(FIX_FLAG);
    assertThat(inst.appended).isEmpty();
  }

  @Test
  void pathThatDoesNotExistWarnsOnceNamingItAndAppendsNothing(@TempDir Path dir) throws Exception {
    Path missing = dir.resolve("gone.jar");
    var inst = new FakeInstrumentation();
    List<String> lines = enter(new CodeSource(missing.toUri().toURL(), (Certificate[]) null), inst);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains(missing.toString())
        .contains(FIX_RENAME)
        .contains(FIX_FLAG + missing);
    assertThat(inst.appended).isEmpty();
  }

  @Test
  void appendThatThrowsWarnsOnceNamingTheJarAndTheNameItShouldHave(@TempDir Path dir)
      throws Exception {
    Path jar = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var inst = new FakeInstrumentation();
    inst.failure = new UnsupportedOperationException("no appending here");
    List<String> lines = enter(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), inst);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains("no appending here")
        .contains(FIX_RENAME + "scope-tracer-agent-9.9.9-agent.jar")
        .contains(FIX_FLAG + jar);
  }

  /**
   * The jar is found from its code source, a space and a non-ASCII character surviving, and
   * appended. The bootstrap loader of this test JVM still has no {@code ScopeTracerAgent} (the jar
   * holds none), so entering the bootstrap copy fails, with one warning and no exception.
   */
  @Test
  void jarFromTheCodeSourceIsAppendedAndNamedInTheInfoLine(@TempDir Path dir) throws Exception {
    Path jar =
        jarListing(dir.resolve("dir with space").resolve("agent ü.jar"), "expected-name.jar");
    var inst = new FakeInstrumentation();
    List<String> lines = enter(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), inst);

    assertThat(inst.appended).containsExactly(jar.toString());
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0))
        .isEqualTo(
            "[scope-tracer] added "
                + jar
                + " to the bootstrap class path, because the jar is not named expected-name.jar");
    assertThat(lines.get(1)).startsWith(WARN_NOT_TRACING).contains("ClassNotFoundException");
  }

  @Test
  void jarWithoutBootClassPathFallsBackToTheGenericName(@TempDir Path dir) throws Exception {
    Path jar = dir.resolve("plain.jar");
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      out.flush();
    }
    var inst = new FakeInstrumentation();
    List<String> lines = enter(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), inst);

    assertThat(lines.get(0)).endsWith("not named scope-tracer-agent-<version>-agent.jar");
  }
}
