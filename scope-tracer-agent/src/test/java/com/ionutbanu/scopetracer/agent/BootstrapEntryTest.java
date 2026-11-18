package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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

  // --- issue #132, line J: a cause, and an escaped path ---

  @Test
  void appendThatThrowsWithACauseNamesTheCauseAndEscapesThePaths(@TempDir Path dir)
      throws Exception {
    Path jar = jarListing(dir.resolve("a\nb.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var inst = new FakeInstrumentation();
    inst.failure = new UnsupportedOperationException("outer", new java.io.IOException("inner"));
    List<String> lines = enter(new CodeSource(jar.toUri().toURL(), (Certificate[]) null), inst);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING + "the agent jar is not on the bootstrap class path, and")
        .contains("cannot add " + dir + "/a\\nb.jar to it: UnsupportedOperationException: outer;")
        .endsWith(FIX_FLAG + dir + "/a\\nb.jar");
    assertThat(lines.get(0))
        .contains("; caused by IOException: inner; rename the jar to scope-tracer-agent-9.9.9");
  }

  // --- issue #128: which jar is the -javaagent jar ---

  private static final String CLASS_ENTRY =
      "!/" + ScopeTracerAgent.class.getName().replace('.', '/') + ".class";

  /**
   * The URL the system class loader gives for {@code ScopeTracerAgent.class} inside {@code jar}.
   */
  private static URL copyIn(Path jar) throws Exception {
    return URI.create("jar:" + jar.toUri() + CLASS_ENTRY).toURL();
  }

  private static String classPath(Path... entries) {
    return String.join(
        File.pathSeparator, java.util.Arrays.stream(entries).map(Path::toString).toList());
  }

  /** What {@code chooseJar} returned and printed. */
  private record Choice(Path jar, List<String> lines) {}

  private static Choice choose(Path running, Callable<List<URL>> copies, String classPath) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    Path[] chosen = new Path[1];
    try {
      assertThatCode(() -> chosen[0] = BootstrapEntry.chooseJar(running, copies, classPath))
          .doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return new Choice(chosen[0], bytes.toString(StandardCharsets.UTF_8).lines().toList());
  }

  @Test
  void runningJarThatIsTheLastCopyIsChosenSilently(@TempDir Path dir) throws Exception {
    Path agent = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent)), classPath(dir.resolve("classes")));

    assertThat(choice.jar()).isEqualTo(agent);
    assertThat(choice.lines()).isEmpty();
  }

  @Test
  void laterCopyNotOnTheClassPathIsChosenAndBothAreNamed(@TempDir Path dir) throws Exception {
    Path thin = jarListing(dir.resolve("cp/thin.jar"), "unused.jar");
    Path agent = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var choice =
        choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), classPath(thin, dir.resolve("c")));

    assertThat(choice.jar()).isEqualTo(agent.toRealPath());
    assertThat(choice.lines())
        .containsExactly(
            "[scope-tracer] ignoring "
                + thin
                + " on the class path: the -javaagent jar is "
                + agent.toRealPath());
  }

  @Test
  void lastCopyThatIsAClassPathEntryAndNotTheRunningOneRefuses(@TempDir Path dir) throws Exception {
    Path a = jarListing(dir.resolve("a.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    Path agent = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    Path b = jarListing(dir.resolve("b.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var choice =
        choose(a, () -> List.of(copyIn(a), copyIn(agent), copyIn(b)), classPath(a, agent, b));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines())
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains("the -javaagent jar cannot be told apart from the class path")
        .contains(a.toString(), b.toRealPath().toString())
        .contains(FIX_RENAME + "scope-tracer-agent-9.9.9-agent.jar")
        .endsWith(FIX_FLAG + "<path to the jar>");
  }

  /** A symbolic link or {@code x/../} on {@code -cp} does not hide that the copy is an entry. */
  @Test
  void classPathEntryIsRecognisedThroughALinkOrDotDot(@TempDir Path dir) throws Exception {
    Path a = jarListing(dir.resolve("a.jar"), "x.jar");
    Path b = jarListing(dir.resolve("real/b.jar"), "x.jar");
    Path link = Files.createSymbolicLink(dir.resolve("link.jar"), b);
    Path dotted = dir.resolve("real/../real/b.jar");

    for (Path entry : List.of(link, dotted)) {
      var choice = choose(a, () -> List.of(copyIn(a), copyIn(b)), classPath(a, entry));
      assertThat(choice.jar()).as(entry.toString()).isNull();
      assertThat(choice.lines())
          .as(entry.toString())
          .singleElement()
          .asString()
          .startsWith(WARN_NOT_TRACING);
    }
  }

  /** {@code -cp agent.jar} with {@code -javaagent:agent.jar}: the copy running is the jar. */
  @Test
  void lastCopyOnTheClassPathThatIsTheRunningOneIsChosenSilently(@TempDir Path dir)
      throws Exception {
    Path agent = jarListing(dir.resolve("agent.jar"), "x.jar");
    Path link = Files.createSymbolicLink(dir.resolve("link.jar"), agent);
    var choice = choose(link, () -> List.of(copyIn(agent)), classPath(link));

    assertThat(choice.jar()).isEqualTo(link);
    assertThat(choice.lines()).isEmpty();
  }

  @Test
  void listingThatFailsOrGivesNothingUsableKeepsTheRunningJarSilently(@TempDir Path dir)
      throws Exception {
    Path running = jarListing(dir.resolve("agent.jar"), "x.jar");
    List<Callable<List<URL>>> listings =
        List.of(
            () -> {
              throw new java.io.IOException("cannot list");
            },
            () -> {
              throw new LinkageError("cannot list either");
            },
            () -> null,
            List::of,
            () ->
                List.of(
                    copyIn(running), URI.create("jrt:/java.base/java/lang/Object.class").toURL()),
            () -> List.of(copyIn(running), dir.resolve("classes/X.class").toUri().toURL()),
            () ->
                List.of(
                    copyIn(running), URI.create("jar:https://example.invalid/a.jar!/X").toURL()),
            () -> List.of(copyIn(running), URI.create("jar:file:/no-separator.jar").toURL()),
            () -> java.util.Arrays.asList(copyIn(running), null));
    for (var listing : listings) {
      var choice = choose(running, listing, classPath(running));
      assertThat(choice.jar()).isEqualTo(running);
      assertThat(choice.lines()).isEmpty();
    }
  }

  @Test
  void nullClassPathMeansTheLaterCopyIsNotOnIt(@TempDir Path dir) throws Exception {
    Path thin = jarListing(dir.resolve("thin.jar"), "x.jar");
    Path agent = jarListing(dir.resolve("agent.jar"), "x.jar");
    var choice = choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), null);

    assertThat(choice.jar()).isEqualTo(agent.toRealPath());
    assertThat(choice.lines()).singleElement().asString().startsWith("[scope-tracer] ignoring ");
  }

  /**
   * Through {@code enter}: the code source is the {@code -cp} jar, the jar appended and named in
   * the {@code added} line is the {@code -javaagent} jar, after the {@code ignoring} line.
   */
  @Test
  void enterAppendsTheJavaagentJarAndNotTheRunningOne(@TempDir Path dir) throws Exception {
    Path thin = jarListing(dir.resolve("thin.jar"), "unused.jar");
    Path agent = jarListing(dir.resolve("agent.jar"), "expected-name.jar");
    var inst = new FakeInstrumentation();
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      BootstrapEntry.enter(
          "record",
          inst.proxy(),
          new CodeSource(thin.toUri().toURL(), (Certificate[]) null),
          () -> List.of(copyIn(thin), copyIn(agent)),
          classPath(thin));
    } finally {
      System.setErr(original);
    }
    List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();

    assertThat(inst.appended).containsExactly(agent.toRealPath().toString());
    assertThat(lines).hasSize(3);
    assertThat(lines.get(0))
        .isEqualTo(
            "[scope-tracer] ignoring "
                + thin
                + " on the class path: the -javaagent jar is "
                + agent.toRealPath());
    assertThat(lines.get(1))
        .isEqualTo(
            "[scope-tracer] added "
                + agent.toRealPath()
                + " to the bootstrap class path, because the jar is not named expected-name.jar");
    assertThat(lines.get(2)).startsWith(WARN_NOT_TRACING).contains("ClassNotFoundException");
  }

  @Test
  void enterRefusesAnAmbiguousLayoutAndAppendsNothing(@TempDir Path dir) throws Exception {
    Path a = jarListing(dir.resolve("a.jar"), "expected-name.jar");
    Path b = jarListing(dir.resolve("b.jar"), "expected-name.jar");
    var inst = new FakeInstrumentation();
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      BootstrapEntry.enter(
          "record",
          inst.proxy(),
          new CodeSource(a.toUri().toURL(), (Certificate[]) null),
          () -> List.of(copyIn(a), copyIn(b)),
          classPath(a, b));
    } finally {
      System.setErr(original);
    }

    assertThat(inst.appended).isEmpty();
    assertThat(bytes.toString(StandardCharsets.UTF_8).lines())
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains(FIX_RENAME + "expected-name.jar", FIX_FLAG);
  }
}
