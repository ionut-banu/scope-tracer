package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  /** A jar with a manifest and no {@code Boot-Class-Path}, as the thin jar is. */
  private static Path jarWithoutBootClassPath(Path path) throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    Files.createDirectories(path.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
      out.flush();
    }
    return path;
  }

  /**
   * {@code -cp agent.jar:thin.jar} with {@code agent.jar} the {@code -javaagent} jar: the running
   * copy is the fat-jar and the last copy is the thin jar. The rename hint is the fat-jar's own
   * name, read from the running jar, not the generic one the thin jar's manifest would give.
   */
  @Test
  void refusalNamesTheFileNameFromTheRunningJar(@TempDir Path dir) throws Exception {
    Path agent = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(agent, thin));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines())
        .singleElement()
        .asString()
        .contains(FIX_RENAME + "scope-tracer-agent-9.9.9-agent.jar, " + FIX_FLAG);
  }

  /** {@code -cp thin.jar:agent.jar}: the running thin jar lists no name, the last copy does. */
  @Test
  void refusalFallsBackToTheLastCopyForTheFileName(@TempDir Path dir) throws Exception {
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path agent = jarListing(dir.resolve("agent.jar"), "scope-tracer-agent-9.9.9-agent.jar");
    var choice = choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), classPath(thin, agent));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines())
        .singleElement()
        .asString()
        .contains(FIX_RENAME + "scope-tracer-agent-9.9.9-agent.jar, " + FIX_FLAG);
  }

  // --- issue #158: a scope-tracer jar reached through a manifest Class-Path ---

  private static final String CANNOT_TELL =
      "the agent jar is not on the bootstrap class path, and the -javaagent jar cannot be told"
          + " apart from the class path, which holds the agent at ";

  /** A jar whose manifest has {@code Class-Path: classPath} and no {@code Boot-Class-Path}. */
  private static Path jarClassPath(Path path, String classPath) throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
    Files.createDirectories(path.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
      out.flush();
    }
    return path;
  }

  /** A fat-jar stand-in: lists {@code scope-tracer-agent-9.9.9-agent.jar} as its file name. */
  private static Path fat(Path path) throws Exception {
    return jarListing(path, "scope-tracer-agent-9.9.9-agent.jar");
  }

  private static Path real(Path path) throws Exception {
    return path.toRealPath();
  }

  /** The refusal for a last copy listed by {@code listedBy}'s manifest, as one exact line. */
  private static String listedRefusal(Path running, Path last, Path listedBy) throws Exception {
    return WARN_NOT_TRACING
        + CANNOT_TELL
        + running
        + " and at "
        + real(last)
        + " (listed by the Class-Path of "
        + real(listedBy)
        + "); rename the jar to scope-tracer-agent-9.9.9-agent.jar, "
        + FIX_FLAG
        + "<path to the jar>";
  }

  private static String ignoring(Path running, Path last) throws Exception {
    return "[scope-tracer] ignoring "
        + running
        + " on the class path: the -javaagent jar is "
        + real(last);
  }

  /** {@code -cp app.jar}, {@code Class-Path: thin.jar}: the -javaagent jar is chosen. Unchanged. */
  @Test
  void classPathListingOnlyTheThinJarChoosesTheJavaagentJar(@TempDir Path dir) throws Exception {
    Path thin = jarWithoutBootClassPath(dir.resolve("cp/thin.jar"));
    Path app = jarClassPath(dir.resolve("cp/app.jar"), "thin.jar");
    Path agent = fat(dir.resolve("agent.jar"));
    var choice = choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), classPath(app));

    assertThat(choice.jar()).isEqualTo(real(agent));
    assertThat(choice.lines()).containsExactly(ignoring(thin, agent));
  }

  /**
   * {@code Class-Path: agent.jar thin.jar}: the JVM appended nothing, so the last copy is the thin
   * jar. It is reachable from the class path, so the agent refuses, naming the listing jar, and
   * never prints the {@code ignoring} line the wrong way round.
   */
  @Test
  void classPathListingTheJavaagentJarThenTheThinJarRefuses(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "agent.jar thin.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(app));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(agent, thin, app));
  }

  @Test
  void classPathListingTheJavaagentJarThenAnotherFatJarRefuses(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path fat2 = fat(dir.resolve("fat2.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "agent.jar fat2.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(fat2)), classPath(app));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(agent, fat2, app));
  }

  /** {@code -cp l.jar}, {@code l.jar} lists {@code m.jar}, which lists both: names {@code m}. */
  @Test
  void chainOfManifestsRefusesNamingTheLastListingJar(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path l = jarClassPath(dir.resolve("l.jar"), "m.jar");
    Path m = jarClassPath(dir.resolve("m.jar"), "agent.jar thin.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(l));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(agent, thin, m));
  }

  /** {@code Class-Path: agent.jar} alone: one copy, the running one. Unchanged. */
  @Test
  void classPathListingOnlyTheJavaagentJarIsChosenSilently(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "agent.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent)), classPath(app));

    assertThat(choice.jar()).isEqualTo(agent);
    assertThat(choice.lines()).isEmpty();
  }

  /**
   * {@code Class-Path: thin.jar agent.jar}: the last copy is the {@code -javaagent} jar, but it is
   * reachable, so it looks the same as {@code agent.jar thin.jar} and the agent refuses.
   */
  @Test
  void classPathListingTheThinJarThenTheJavaagentJarRefuses(@TempDir Path dir) throws Exception {
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path agent = fat(dir.resolve("agent.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "thin.jar agent.jar");
    var choice = choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), classPath(app));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines())
        .singleElement()
        .asString()
        .isEqualTo(listedRefusal(thin, agent, app))
        .contains("thin.jar", "agent.jar");
  }

  /**
   * {@code -cp "lib/*"}: {@code java.class.path} holds the expanded {@code lib/a.jar}, whose
   * manifest lists {@code ../x/agent.jar ../x/thin.jar}, or only {@code ../x/thin.jar}.
   */
  @Test
  void wildcardJarListingBothRefusesAndListingOnlyTheThinJarChoosesTheJavaagentJar(
      @TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("x/agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("x/thin.jar"));
    Path both = jarClassPath(dir.resolve("both/lib/a.jar"), "../../x/agent.jar ../../x/thin.jar");
    var refused = choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(both));
    assertThat(refused.jar()).isNull();
    assertThat(refused.lines()).containsExactly(listedRefusal(agent, thin, both));

    Path onlyThin = jarClassPath(dir.resolve("thin/lib/a.jar"), "../../x/thin.jar");
    var chosen = choose(thin, () -> List.of(copyIn(thin), copyIn(agent)), classPath(onlyThin));
    assertThat(chosen.jar()).isEqualTo(real(agent));
    assertThat(chosen.lines()).containsExactly(ignoring(thin, agent));
  }

  /**
   * Whether {@code target} counts as reachable when {@code dir/app/app.jar} lists {@code entry}:
   * the running copy is {@code dir/running.jar}, the last copy {@code target}. Asserts the run
   * printed exactly the refusal (reachable) or the {@code ignoring} line (not reachable).
   */
  private static boolean reachable(Path dir, String entry, Path target, Path... alsoOnClassPath)
      throws Exception {
    Path running = fat(dir.resolve("running.jar"));
    Path app = jarClassPath(dir.resolve("app/app.jar"), entry);
    var entries = new ArrayList<>(List.of(alsoOnClassPath));
    entries.add(app);
    var choice =
        choose(
            running,
            () -> List.of(copyIn(running), copyIn(target)),
            classPath(entries.toArray(Path[]::new)));
    if (choice.jar() == null) {
      assertThat(choice.lines()).as(entry).containsExactly(listedRefusal(running, target, app));
      return true;
    }
    assertThat(choice.jar()).as(entry).isEqualTo(real(target));
    assertThat(choice.lines()).as(entry).containsExactly(ignoring(running, target));
    return false;
  }

  @Test
  void classPathEntriesTheJvmResolvesAreFollowed(@TempDir Path dir) throws Exception {
    assertThat(reachable(dir.resolve("1"), "sub/t.jar", fat(dir.resolve("1/app/sub/t.jar"))))
        .as("relative")
        .isTrue();
    assertThat(reachable(dir.resolve("2"), "./t.jar", fat(dir.resolve("2/app/t.jar"))))
        .as("./")
        .isTrue();
    assertThat(reachable(dir.resolve("3"), "../t.jar", fat(dir.resolve("3/t.jar"))))
        .as("../")
        .isTrue();
    Path absolute = fat(dir.resolve("4/abs/t.jar"));
    assertThat(reachable(dir.resolve("4"), absolute.toString(), absolute)).as("absolute").isTrue();
    Path uri = fat(dir.resolve("5/abs/t.jar"));
    assertThat(reachable(dir.resolve("5"), uri.toUri().toString(), uri)).as("file:").isTrue();
    assertThat(
            reachable(
                dir.resolve("6"),
                "dir%20with%20space/t.jar",
                fat(dir.resolve("6/app/dir with space/t.jar"))))
        .as("%20")
        .isTrue();
  }

  /**
   * Entries the JVM resolves to no jar (measured on Temurin 27.0.1 with a probe listing {@code
   * getSystemResources}): skipped, nothing printed.
   */
  @Test
  void classPathEntriesThatNameNoJarAreSkippedSilently(@TempDir Path dir) throws Exception {
    assertThat(
            reachable(
                dir.resolve("1"),
                "dir with space/t.jar",
                fat(dir.resolve("1/app/dir with space/t.jar"))))
        .as("raw space")
        .isFalse();
    assertThat(reachable(dir.resolve("2"), "sub\\t.jar", fat(dir.resolve("2/app/sub/t.jar"))))
        .as("backslash")
        .isFalse();
    assertThat(reachable(dir.resolve("3"), "sub/ sub", fat(dir.resolve("3/app/sub/t.jar"))))
        .as("directory")
        .isFalse();
    assertThat(reachable(dir.resolve("4"), "sub/* *", fat(dir.resolve("4/app/sub/t.jar"))))
        .as("*")
        .isFalse();
    assertThat(
            reachable(
                dir.resolve("5"), "http://localhost:1/t.jar", fat(dir.resolve("5/app/t.jar"))))
        .as("http:")
        .isFalse();
    Path other = fat(dir.resolve("6/app/t.jar"));
    assertThat(reachable(dir.resolve("6"), "file://otherhost" + real(other), other))
        .as("file: on another host")
        .isFalse();
    Path inJar = fat(dir.resolve("7/app/t.jar"));
    assertThat(reachable(dir.resolve("7"), "jar:" + real(inJar).toUri() + "!/", inJar))
        .as("jar:")
        .isFalse();
  }

  /**
   * Entries {@code java.net.URI} rejects or reads differently, which the JVM follows (measured the
   * same way): {@code ^ [ ] { }}, {@code file:} relative to the listing jar in any case, a
   * fragment, {@code %5E}, and {@code file://localhost}.
   */
  @Test
  void classPathEntriesTheJvmFollowsThoughUriRejectsThem(@TempDir Path dir) throws Exception {
    assertThat(reachable(dir.resolve("1"), "odd/a^b.jar", fat(dir.resolve("1/app/odd/a^b.jar"))))
        .as("^")
        .isTrue();
    assertThat(reachable(dir.resolve("2"), "odd/k[1].jar", fat(dir.resolve("2/app/odd/k[1].jar"))))
        .as("[ ]")
        .isTrue();
    assertThat(reachable(dir.resolve("3"), "odd/c{d}.jar", fat(dir.resolve("3/app/odd/c{d}.jar"))))
        .as("{ }")
        .isTrue();
    assertThat(reachable(dir.resolve("4"), "file:t.jar", fat(dir.resolve("4/app/t.jar"))))
        .as("file:relative")
        .isTrue();
    assertThat(reachable(dir.resolve("5"), "FILE:t.jar", fat(dir.resolve("5/app/t.jar"))))
        .as("FILE:relative")
        .isTrue();
    assertThat(reachable(dir.resolve("6"), "file:./sub/t.jar", fat(dir.resolve("6/app/sub/t.jar"))))
        .as("file:./")
        .isTrue();
    assertThat(reachable(dir.resolve("7"), "t.jar#frag", fat(dir.resolve("7/app/t.jar"))))
        .as("fragment")
        .isTrue();
    assertThat(reachable(dir.resolve("8"), "odd/a%5Eb.jar", fat(dir.resolve("8/app/odd/a^b.jar"))))
        .as("%5E")
        .isTrue();
    Path local = fat(dir.resolve("9/abs/t.jar"));
    assertThat(reachable(dir.resolve("9"), "file://localhost" + real(local), local))
        .as("file://localhost")
        .isTrue();
  }

  /** {@code Class-Path: agent.jar file:thin.jar}, or {@code file:fat2.jar}: both refuse. */
  @Test
  void fileSchemeRelativeEntryAfterTheJavaagentJarRefuses(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path fat2 = fat(dir.resolve("fat2.jar"));
    for (Path other : List.of(thin, fat2)) {
      Path app =
          jarClassPath(
              dir.resolve("app-" + other.getFileName()), "agent.jar file:" + other.getFileName());
      var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(other)), classPath(app));
      assertThat(choice.jar()).as(other.toString()).isNull();
      assertThat(choice.lines()).containsExactly(listedRefusal(agent, other, app));
    }
  }

  /**
   * The URL the system class loader gives for a copy whose jar was reached through a {@code
   * Class-Path} entry: the path as written, a {@code ^} or {@code [} left raw.
   */
  @SuppressWarnings("deprecation")
  private static URL rawCopyIn(Path jar) throws Exception {
    return new URL("jar:file:" + real(jar) + CLASS_ENTRY);
  }

  /** {@code -cp l.jar}, {@code l} lists {@code odd/k[1].jar}, which lists both copies. */
  @Test
  void chainThroughAJarNameUriRejectsRefuses(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path fat2 = fat(dir.resolve("fat2.jar"));
    Path l = jarClassPath(dir.resolve("l.jar"), "odd/k[1].jar");
    Path k = jarClassPath(dir.resolve("odd/k[1].jar"), "../agent.jar ../fat2.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent), rawCopyIn(fat2)), classPath(l));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(agent, fat2, k));
  }

  /** A last copy whose URL holds a {@code ^} is read, not taken as "no copy" (#158, QA). */
  @Test
  void lastCopyWithACharacterUriRejectsIsRead(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path odd = fat(dir.resolve("odd/f^at.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "agent.jar odd/f^at.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent), rawCopyIn(odd)), classPath(app));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(agent, odd, app));
  }

  /**
   * An entry that cannot be resolved, here because {@code Path.of} rejects it ({@code %00}) or the
   * escape is malformed ({@code %zz}), counts as reachable: the agent refuses, naming the jar whose
   * {@code Class-Path} holds it.
   */
  @Test
  void entryThatCannotBeResolvedRefuses(@TempDir Path dir) throws Exception {
    for (String entry : List.of("a%00b.jar", "a%zz.jar", "a%4.jar")) {
      Path sub = dir.resolve(entry.replace('%', '_'));
      Path running = fat(sub.resolve("running.jar"));
      Path target = fat(sub.resolve("t.jar"));
      Path app = jarClassPath(sub.resolve("app.jar"), entry + " missing.jar");
      var choice = choose(running, () -> List.of(copyIn(running), copyIn(target)), classPath(app));

      assertThat(choice.jar()).as(entry).isNull();
      assertThat(choice.lines())
          .as(entry)
          .containsExactly(
              WARN_NOT_TRACING
                  + CANNOT_TELL
                  + running
                  + " and at "
                  + real(target)
                  + " (the Class-Path of "
                  + real(app)
                  + " has an entry that cannot be resolved); rename the jar to"
                  + " scope-tracer-agent-9.9.9-agent.jar, "
                  + FIX_FLAG
                  + "<path to the jar>");
    }
  }

  /** A jar that lists the copy for certain is named over one with an unresolvable entry. */
  @Test
  void jarThatListsTheCopyIsNamedOverOneWithAnUnresolvableEntry(@TempDir Path dir)
      throws Exception {
    Path running = fat(dir.resolve("running.jar"));
    Path target = fat(dir.resolve("t.jar"));
    Path bad = jarClassPath(dir.resolve("bad.jar"), "a%00b.jar");
    Path good = jarClassPath(dir.resolve("good.jar"), "t.jar");
    var choice =
        choose(running, () -> List.of(copyIn(running), copyIn(target)), classPath(bad, good));

    assertThat(choice.lines()).containsExactly(listedRefusal(running, target, good));
  }

  /**
   * #128, found by QA on #158: a running jar whose path holds a {@code ^}, as a {@code
   * Class-Path}-made code source gives it, is appended and named, not refused with a {@code
   * URISyntaxException} and the generic file name.
   */
  @Test
  @SuppressWarnings("deprecation")
  void runningJarWithACharacterUriRejectsIsAppended(@TempDir Path dir) throws Exception {
    Path jar = jarListing(dir.resolve("odd/a^b[1].jar"), "expected-name.jar");
    var inst = new FakeInstrumentation();
    var source = new CodeSource(new URL("file:" + real(jar)), (Certificate[]) null);
    List<String> lines = enter(source, inst);

    assertThat(inst.appended).containsExactly(real(jar).toString());
    assertThat(lines.get(0))
        .isEqualTo(
            "[scope-tracer] added "
                + real(jar)
                + " to the bootstrap class path, because the jar is not named expected-name.jar");
    assertThat(String.join("\n", lines)).doesNotContain("URISyntaxException");
  }

  /** The same running jar in a refusal: the rename hint is read from it, not the generic one. */
  @Test
  @SuppressWarnings("deprecation")
  void refusalFromARunningJarWithACharacterUriRejectsNamesItsFileName(@TempDir Path dir)
      throws Exception {
    Path odd = fat(dir.resolve("odd/a^b.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    var inst = new FakeInstrumentation();
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      BootstrapEntry.enter(
          "record",
          inst.proxy(),
          new CodeSource(new URL("file:" + real(odd)), (Certificate[]) null),
          () -> List.of(rawCopyIn(odd), copyIn(thin)),
          classPath(odd, thin));
    } finally {
      System.setErr(original);
    }

    assertThat(inst.appended).isEmpty();
    assertThat(bytes.toString(StandardCharsets.UTF_8).lines())
        .singleElement()
        .asString()
        .contains(" at " + real(odd) + " and at " + real(thin) + ";")
        .contains(FIX_RENAME + "scope-tracer-agent-9.9.9-agent.jar, " + FIX_FLAG)
        .doesNotContain("URISyntaxException");
  }

  /** {@code fileOf} decodes as the JVM does. */
  @Test
  @SuppressWarnings("deprecation")
  void fileOfDecodesAsTheJvmDoes() throws Exception {
    assertThat(BootstrapEntry.fileOf(new URL("file:/a/b%20c/d%C3%BC.jar")))
        .isEqualTo(Path.of("/a/b c/dü.jar"));
    assertThat(BootstrapEntry.fileOf(new URL("file:/a/b^c[1]{2}.jar")))
        .isEqualTo(Path.of("/a/b^c[1]{2}.jar"));
    assertThat(BootstrapEntry.fileOf(new URL("file:/a/t.jar?q#frag")))
        .isEqualTo(Path.of("/a/t.jar?q"));
    assertThat(BootstrapEntry.fileOf(new URL("file://localhost/a/t.jar")))
        .isEqualTo(Path.of("/a/t.jar"));
    assertThat(BootstrapEntry.fileOf(new URL("file://otherhost/a/t.jar"))).isNull();
    assertThat(BootstrapEntry.fileOf(new URL("http://example.invalid/t.jar"))).isNull();
    assertThat(BootstrapEntry.fileOf(null)).isNull();
    assertThatThrownBy(() -> BootstrapEntry.fileOf(new URL("file:/a/b%zz.jar")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BootstrapEntry.fileOf(new URL("file:/a/b%4")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BootstrapEntry.fileOf(new URL("file:/a/b%00.jar")))
        .isInstanceOf(java.nio.file.InvalidPathException.class);
  }

  /** A missing entry is skipped and the entries after it are still read. */
  @Test
  void missingClassPathEntryIsSkippedAndTheRestIsRead(@TempDir Path dir) throws Exception {
    Path target = fat(dir.resolve("app/t.jar"));
    assertThat(reachable(dir, "missing.jar t.jar", target)).isTrue();
  }

  /** A {@code -cp} entry that is not a zip, or has a malformed manifest, is skipped silently. */
  @Test
  void unreadableJarsOnTheClassPathAreSkippedAndTheRestIsRead(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("cp"));
    Path notZip = Files.writeString(dir.resolve("cp/not-a-zip.jar"), "not a zip");
    Path malformed = dir.resolve("cp/malformed.jar");
    try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(malformed))) {
      out.putNextEntry(new java.util.zip.ZipEntry(JarFile.MANIFEST_NAME));
      out.write("Manifest-Version: 1.0\nthis line has no colon\n".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    try (var file = new JarFile(malformed.toFile())) {
      assertThatThrownBy(file::getManifest)
          .as("the manifest really is malformed")
          .isInstanceOf(java.io.IOException.class);
    }
    Path noClassPath = jarWithoutBootClassPath(dir.resolve("cp/no-class-path.jar"));
    Path classes = Files.createDirectories(dir.resolve("cp/classes"));
    Path target = fat(dir.resolve("app/t.jar"));

    assertThat(reachable(dir, "t.jar", target, notZip, malformed, noClassPath, classes)).isTrue();
  }

  /** A listed jar whose manifest has no {@code Class-Path} ends the chain there. */
  @Test
  void jarWithoutClassPathEndsTheChain(@TempDir Path dir) throws Exception {
    Path target = fat(dir.resolve("app/t.jar"));
    jarWithoutBootClassPath(dir.resolve("app/plain.jar"));
    assertThat(reachable(dir, "plain.jar", target)).isFalse();
  }

  /** {@code a} lists {@code b}, {@code b} lists {@code a}: it ends, and finds what they list. */
  @Test
  void cycleEnds(@TempDir Path dir) throws Exception {
    Path target = fat(dir.resolve("app/t.jar"));
    jarClassPath(dir.resolve("app/b.jar"), "app.jar");
    assertThat(reachable(dir, "b.jar", target)).as("cycle alone").isFalse();

    // a lists b, b lists a and the jar
    Path running = fat(dir.resolve("2/running.jar"));
    Path target2 = fat(dir.resolve("2/t.jar"));
    Path a = jarClassPath(dir.resolve("2/a.jar"), "b.jar");
    Path b = jarClassPath(dir.resolve("2/b.jar"), "a.jar t.jar");
    var choice = choose(running, () -> List.of(copyIn(running), copyIn(target2)), classPath(a));
    assertThat(choice.lines())
        .as("cycle listing the jar")
        .containsExactly(listedRefusal(running, target2, b));
  }

  /** {@code app} lists {@code a}, {@code a} lists {@code b}, {@code b} lists the jar. */
  @Test
  void chainOfThreeIsFollowed(@TempDir Path dir) throws Exception {
    Path running = fat(dir.resolve("running.jar"));
    Path target = fat(dir.resolve("t.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "a.jar");
    jarClassPath(dir.resolve("a.jar"), "b.jar");
    jarClassPath(dir.resolve("b.jar"), "c.jar");
    Path c = jarClassPath(dir.resolve("c.jar"), "t.jar");
    var choice = choose(running, () -> List.of(copyIn(running), copyIn(target)), classPath(app));

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).containsExactly(listedRefusal(running, target, c));
  }

  /** A symbolic link, or {@code x/../}, in a {@code Class-Path} reaches the same real file. */
  @Test
  void linkOrDotDotInAClassPathReachesTheSameJar(@TempDir Path dir) throws Exception {
    Path target = fat(dir.resolve("1/app/real/t.jar"));
    Files.createSymbolicLink(dir.resolve("1/app/link.jar"), target);
    assertThat(reachable(dir.resolve("1"), "link.jar", target)).as("link").isTrue();

    Path target2 = fat(dir.resolve("2/app/real/t.jar"));
    assertThat(reachable(dir.resolve("2"), "real/../real/t.jar", target2)).as("../").isTrue();
  }

  /** A link to the running jar is the running jar: one copy, chosen silently. */
  @Test
  void classPathLinkToTheRunningJarIsTheRunningJar(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Files.createSymbolicLink(dir.resolve("link.jar"), agent);
    Path app = jarClassPath(dir.resolve("app.jar"), "link.jar");
    var choice = choose(agent, () -> List.of(copyIn(agent)), classPath(app));

    assertThat(choice.jar()).isEqualTo(agent);
    assertThat(choice.lines()).isEmpty();
  }

  /** A {@code -cp} entry keeps its refusal wording: no {@code listed by} part. */
  @Test
  void classPathEntryRefusalWordingIsUnchanged(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    var choice = choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(agent, thin));

    assertThat(choice.lines())
        .containsExactly(
            WARN_NOT_TRACING
                + CANNOT_TELL
                + agent
                + " and at "
                + real(thin)
                + "; rename the jar to scope-tracer-agent-9.9.9-agent.jar, "
                + FIX_FLAG
                + "<path to the jar>");
  }

  /** A manifest reader that records every jar it is asked about, then reads it. */
  private static final class RecordingReader implements BootstrapEntry.ClassPathAttribute {
    final List<Path> read = new ArrayList<>();
    final Path failOn;

    RecordingReader(Path failOn) {
      this.failOn = failOn;
    }

    @Override
    public String of(Path jar) throws Exception {
      read.add(jar);
      if (failOn != null && jar.equals(failOn)) {
        throw new LinkageError("cannot read " + jar);
      }
      try (var file = new JarFile(jar.toFile())) {
        var manifest = file.getManifest();
        return manifest == null
            ? null
            : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      }
    }
  }

  private static Choice choose(
      Path running, Callable<List<URL>> copies, String classPath, RecordingReader reader) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    Path[] chosen = new Path[1];
    try {
      assertThatCode(() -> chosen[0] = BootstrapEntry.chooseJar(running, copies, classPath, reader))
          .doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return new Choice(chosen[0], bytes.toString(StandardCharsets.UTF_8).lines().toList());
  }

  /**
   * The common case, the running copy is the last copy: no manifest is opened, not even one whose
   * {@code Class-Path} lists another scope-tracer jar and would refuse if it were read.
   */
  @Test
  void runningCopyThatIsTheLastCopyReadsNoManifest(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "thin.jar agent.jar");
    var reader = new RecordingReader(null);
    var choice = choose(agent, () -> List.of(copyIn(agent)), classPath(app, thin), reader);

    assertThat(choice.jar()).isEqualTo(agent);
    assertThat(choice.lines()).isEmpty();
    assertThat(reader.read).isEmpty();
  }

  /** A last copy that is a {@code -cp} entry refuses before any manifest is read. */
  @Test
  void lastCopyThatIsAClassPathEntryReadsNoManifest(@TempDir Path dir) throws Exception {
    Path agent = fat(dir.resolve("agent.jar"));
    Path thin = jarWithoutBootClassPath(dir.resolve("thin.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "agent.jar");
    var reader = new RecordingReader(null);
    var choice =
        choose(agent, () -> List.of(copyIn(agent), copyIn(thin)), classPath(app, thin), reader);

    assertThat(choice.jar()).isNull();
    assertThat(choice.lines()).singleElement().asString().doesNotContain("listed by");
    assertThat(reader.read).isEmpty();
  }

  /**
   * Each jar is read once, a cycle included, and a jar whose reader throws does not stop the rest.
   */
  @Test
  void eachManifestIsReadOnceAndAFailingOneIsSkipped(@TempDir Path dir) throws Exception {
    Path running = fat(dir.resolve("running.jar"));
    Path target = fat(dir.resolve("t.jar"));
    Path a = jarClassPath(dir.resolve("a.jar"), "b.jar a.jar");
    Path b = jarClassPath(dir.resolve("b.jar"), "a.jar b.jar");
    Path failing = jarClassPath(dir.resolve("failing.jar"), "t.jar");
    Path c = jarClassPath(dir.resolve("c.jar"), "t.jar");
    var reader = new RecordingReader(real(failing));
    var choice =
        choose(
            running,
            () -> List.of(copyIn(running), copyIn(target)),
            classPath(a, b, a, failing, c),
            reader);

    assertThat(choice.lines()).containsExactly(listedRefusal(running, target, c));
    assertThat(reader.read).containsExactly(real(a), real(b), real(failing), real(c));
  }

  /** When every manifest read fails, the last copy is chosen, as before #158. */
  @Test
  void manifestsThatCannotBeReadKeepTheLastCopy(@TempDir Path dir) throws Exception {
    Path running = fat(dir.resolve("running.jar"));
    Path target = fat(dir.resolve("t.jar"));
    Path app = jarClassPath(dir.resolve("app.jar"), "t.jar");
    var reader = new RecordingReader(real(app));
    var choice =
        choose(running, () -> List.of(copyIn(running), copyIn(target)), classPath(app), reader);

    assertThat(choice.jar()).isEqualTo(real(target));
    assertThat(choice.lines()).containsExactly(ignoring(running, target));
  }
}
