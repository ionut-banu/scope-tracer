package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.agent.JdkPatch.Change;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A JDK whose {@code StructuredTaskScope} classes still load but no longer match one of the agent's
 * advices prints one {@code [scope-tracer] WARN cannot instrument <class>, the trace will be
 * incomplete: ...} line per class, at most once per JVM (issue #130). Before, ByteBuddy reported
 * the transformation as a success and the trace was quietly partial or empty.
 *
 * <p>The changed JDK is the real one under test with its {@code StructuredTaskScope*} class bytes
 * rewritten by {@link JdkPatch} and put in front with {@code --patch-module java.base=<dir>}. The
 * subject is {@link ExitSubject}'s {@code lab} mode: 10 scopes, one named {@value
 * ExitSubject#LAB_SCOPE}, and 8 of them opened from 8 threads at once.
 */
class AgentMatcherMissIT {

  private static final String SCOPE = "java.util.concurrent.StructuredTaskScope";
  private static final String SCOPE_IMPL = "java.util.concurrent.StructuredTaskScopeImpl";
  private static final String RENAMED_IMPL = SCOPE_IMPL + "2";
  private static final String CANNOT = ChildJvm.AGENT_PREFIX + " WARN cannot instrument ";
  private static final String INCOMPLETE = ", the trace will be incomplete: ";
  private static final String RECORDING_TO = ChildJvm.AGENT_PREFIX + " recording to ";
  private static final String CONSTRUCTOR_LINE =
      CANNOT
          + SCOPE_IMPL
          + INCOMPLETE
          + "no method matched the constructor (Joiner, ThreadFactory, String)";
  private static final String RENAMED_LINE =
      CANNOT
          + RENAMED_IMPL
          + INCOMPLETE
          + "StructuredTaskScope.open returned it, and the agent instruments only "
          + SCOPE_IMPL;
  private static final String BYTE_BUDDY_TRANSFORM = "[Byte Buddy] TRANSFORM ";
  private static final String SUBJECT = "lab";
  private static final int SCOPES = 2 + ExitSubject.LAB_THREADS;
  private static final int TASKS = 5 + ExitSubject.LAB_THREADS;

  /** An agent whose {@code premain} loads both scope classes. */
  private static final String LOADER_AGENT =
      """
      public final class LoadsScopeClasses {
        public static void premain(String args, java.lang.instrument.Instrumentation inst)
            throws Exception {
          Class.forName("java.util.concurrent.StructuredTaskScope");
          Class.forName("java.util.concurrent.StructuredTaskScopeImpl");
        }
      }
      """;

  /** An agent whose {@code premain} retransforms both scope classes, twice. */
  private static final String RETRANSFORMING_AGENT =
      """
      public final class RetransformsScopeClasses {
        public static void premain(String args, java.lang.instrument.Instrumentation inst)
            throws Exception {
          Class<?> scope = Class.forName("java.util.concurrent.StructuredTaskScope");
          Class<?> impl = Class.forName("java.util.concurrent.StructuredTaskScopeImpl");
          inst.retransformClasses(scope, impl);
          inst.retransformClasses(scope, impl);
        }
      }
      """;

  /** #121's crafted constructor advice: one parameter ByteBuddy cannot bind. */
  private static final String CRAFTED_CONSTRUCTOR_ADVICE =
      """
      package com.ionutbanu.scopetracer.agent.advice;
      import com.ionutbanu.scopetracer.shaded.net.bytebuddy.asm.Advice;
      public final class ScopeConstructorAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onConstruct(@Advice.Argument(7) String name) {}
      }
      """;

  private static Path fatJar;
  private static String originalName;

  @BeforeAll
  static void requireFatJar() {
    String agent = ChildJvm.agentJar();
    assumeTrue(
        agent != null && ChildJvm.testClassesDir() != null,
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
    fatJar = Path.of(agent);
    originalName = fatJar.getFileName().toString();
  }

  // --- criterion 1 ---

  @Test
  void aConstructorThatNoLongerMatchesNamesTheImplOnce(@TempDir Path dir) throws Exception {
    String patch = patch(dir, Change.CONSTRUCTOR);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(patch, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(patch)));
    assertThat(with.agentLines())
        .containsExactly(
            RECORDING_TO + jfr, CONSTRUCTOR_LINE, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(linesFor(with, SCOPE)).isEmpty();

    // the trace main records on this JDK: every event, but the configured name is lost
    var events = ScopeEvents.read(jfr);
    assertThat(events.ofType(ScopeEvents.SCOPE_OPENED)).hasSize(SCOPES);
    assertThat(events.ofType(ScopeEvents.SCOPE_CLOSED)).hasSize(SCOPES);
    assertThat(events.ofType(ScopeEvents.TASK_FORKED)).hasSize(TASKS);
    assertThat(events.ofType("TaskSucceeded")).hasSize(TASKS - 1);
    assertThat(events.ofType("TaskFailed")).hasSize(1);
    assertThat(events.opened(ExitSubject.LAB_SCOPE)).isEmpty();
    assertThat(events.opened(ExitSubject.LAB_PARALLEL)).isEmpty();
    // the named scope and its unnamed child: both derived from the frame in lab()
    assertThat(events.opened("ExitSubject#lab")).hasSize(2);
  }

  // --- criteria 2, 3 and 12 ---

  @Test
  void aRenamedImplIsNamedOnceOnTheFirstOpen(@TempDir Path dir) throws Exception {
    String patch = patch(dir, Change.RENAMED);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(patch, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(patch)));
    // 10 scopes, 8 of them from 8 threads at once: still one line, after the recording started
    assertThat(with.agentLines())
        .containsExactly(
            RECORDING_TO + jfr, RENAMED_LINE, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(with.stderr()).doesNotContain(CANNOT + SCOPE + INCOMPLETE);
    assertThat(with.stderr()).doesNotContain(CANNOT + SCOPE_IMPL + INCOMPLETE);

    var events = ScopeEvents.read(jfr);
    assertThat(events.events())
        .hasSize(SCOPES)
        .allMatch(e -> e.type().equals(ScopeEvents.SCOPE_OPENED));

    // criterion 12: scopes opened, never closed, no forks, go through the analyzer's CLI
    assertThat(analyzer(jfr, dir.resolve("t.json"), "--format=json")).isZero();
    assertThat(Files.readString(dir.resolve("t.json"))).contains("ExitSubject#lab");
    assertThat(analyzer(jfr, dir.resolve("cli.html"))).isZero();
    assertThat(Files.readString(dir.resolve("cli.html"))).contains("ExitSubject#lab");
  }

  // --- criterion 3, the retransformation path ---

  @Test
  void aClassRetransformedTwiceIsNamedOnce(@TempDir Path dir) throws Exception {
    String patch = patch(dir, Change.CONSTRUCTOR);
    String retransformer =
        "-javaagent:" + helperAgent(dir, "RetransformsScopeClasses", RETRANSFORMING_AGENT);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(patch, javaagent(fatJar, "record=" + jfr), retransformer));
    var withVerbose =
        run(dir, List.of(patch, javaagent(fatJar, "verbose,html=false"), retransformer));

    assertSameProgram(with, run(dir, List.of(patch, retransformer)));
    assertThat(with.agentLines())
        .containsExactly(
            RECORDING_TO + jfr, CONSTRUCTOR_LINE, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(
            withVerbose
                .stderr()
                .lines()
                .filter(l -> l.startsWith(BYTE_BUDDY_TRANSFORM + SCOPE_IMPL + " ")))
        .as("ByteBuddy's own transformations in%n%s", withVerbose.stderr())
        .hasSize(3);
    assertThat(withVerbose.agentLines()).containsExactly(CONSTRUCTOR_LINE);
  }

  // --- criterion 4 ---

  @Test
  void anAlreadyLoadedClassIsNamedBeforeTheRecordingStarts(@TempDir Path dir) throws Exception {
    String patch = patch(dir, Change.CONSTRUCTOR);
    String loader = "-javaagent:" + helperAgent(dir, "LoadsScopeClasses", LOADER_AGENT);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(patch, loader, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(patch, loader)));
    assertThat(with.agentLines())
        .containsExactly(
            CONSTRUCTOR_LINE, RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
  }

  // --- criterion 5 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"exclude.name=**", "sample.rate=0.0"})
  void filtersDoNotHideTheRenamedImpl(String filter, @TempDir Path dir) throws Exception {
    String patch = patch(dir, Change.RENAMED);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(patch, javaagent(fatJar, "record=" + jfr + "," + filter)));

    assertSameProgram(with, run(dir, List.of(patch)));
    assertThat(with.agentLines()).containsExactly(RECORDING_TO + jfr, RENAMED_LINE);
    assertThat(ScopeEvents.read(jfr).events()).as("scope-tracer events").isEmpty();
  }

  // --- criterion 6 ---

  /** The other ways to run the agent. */
  enum Route {
    RENAMED_JAR,
    START_FLIGHT_RECORDING,
    HTML_FALSE,
    VERBOSE,
    VERBOSE_FALSE
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Route.class)
  void otherRoutesPrintTheSameSingleLineForTheConstructor(Route route, @TempDir Path dir)
      throws Exception {
    otherRoute(route, dir, Change.CONSTRUCTOR, CONSTRUCTOR_LINE);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Route.class)
  void otherRoutesPrintTheSameSingleLineForTheRenamedImpl(Route route, @TempDir Path dir)
      throws Exception {
    otherRoute(route, dir, Change.RENAMED, RENAMED_LINE);
  }

  private static void otherRoute(Route route, Path dir, Change change, String line)
      throws Exception {
    String patch = patch(dir, change);
    Path jfr = dir.resolve("t.jfr");
    var args = new ArrayList<>(List.of(patch));
    List<String> expected;
    switch (route) {
      case RENAMED_JAR -> {
        Path jar = copyAs(dir, "agent.jar");
        args.add(javaagent(jar, "html=false"));
        expected = List.of(appendedLine(jar), line);
      }
      case START_FLIGHT_RECORDING -> {
        args.add(javaagent(fatJar, ""));
        args.add("-XX:StartFlightRecording=filename=" + jfr + ",dumponexit=true");
        expected = List.of(line, ChildJvm.WRITTEN + dir.resolve("t.html"));
      }
      case HTML_FALSE -> {
        args.add(javaagent(fatJar, "record=" + jfr + ",html=false"));
        expected = List.of(RECORDING_TO + jfr, line);
      }
      case VERBOSE -> {
        args.add(javaagent(fatJar, "verbose,html=false"));
        expected = List.of(line);
      }
      case VERBOSE_FALSE -> {
        args.add(javaagent(fatJar, "verbose=false,html=false"));
        expected = List.of(line);
      }
      default -> throw new AssertionError(route);
    }
    var with = run(dir, args);

    var without = new ArrayList<>(args);
    without.removeIf(a -> a.startsWith("-javaagent:"));
    assertSameProgram(with, run(dir, without));
    assertThat(with.agentLines()).containsExactlyElementsOf(expected);
    if (route == Route.VERBOSE) {
      assertThat(with.stderr()).contains(BYTE_BUDDY_TRANSFORM + SCOPE + " ");
      assertThat(with.stderr().contains(BYTE_BUDDY_TRANSFORM + SCOPE_IMPL + " "))
          .as("TRANSFORM for the impl in%n%s", with.stderr())
          .isEqualTo(change == Change.CONSTRUCTOR);
    } else {
      assertThat(with.stderr()).doesNotContain("[Byte Buddy]");
    }
  }

  // --- criterion 7 ---

  @Test
  void aByteBuddyErrorIsTheOnlyLineForItsClass(@TempDir Path dir) throws Exception {
    Path crafted =
        compile(
            dir,
            "crafted",
            Map.of(
                "com/ionutbanu/scopetracer/agent/advice/ScopeConstructorAdvice",
                CRAFTED_CONSTRUCTOR_ADVICE),
            fatJar.toString());
    String boot = "-Xbootclasspath/a:" + crafted;
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines().stream().filter(l -> l.startsWith(CANNOT)))
        .singleElement()
        .asString()
        .startsWith(CANNOT + SCOPE_IMPL + INCOMPLETE + "IllegalStateException: ")
        .doesNotContain("no method matched");
  }

  // --- criteria 8 and 9 ---

  /** The JDKs on which nothing is wrong. */
  enum Good {
    STOCK,
    CONTROL_PATCH
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Good.class)
  void nothingWrongPrintsNoLine(Good jdk, @TempDir Path dir) throws Exception {
    List<String> patch = jdk == Good.STOCK ? List.of() : List.of(patch(dir, Change.CONTROL));
    Path renamed = copyAs(dir, "renamed/agent.jar");
    String loader = "-javaagent:" + helperAgent(dir, "LoadsScopeClasses", LOADER_AGENT);
    String retransformer =
        "-javaagent:" + helperAgent(dir, "RetransformsScopeClasses", RETRANSFORMING_AGENT);
    Path jfr = dir.resolve("t.jfr");

    var original = run(dir, with(patch, javaagent(fatJar, "record=" + jfr)));
    assertThat(original.agentLines())
        .containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertSameProgram(original, run(dir, patch));
    var events = ScopeEvents.read(jfr);
    assertThat(events.ofType(ScopeEvents.TASK_FORKED)).hasSize(TASKS);
    assertThat(events.opened(ExitSubject.LAB_SCOPE)).hasSize(1);
    assertThat(events.opened(ExitSubject.LAB_PARALLEL)).hasSize(ExitSubject.LAB_THREADS);

    var underRenamed = run(dir, with(patch, javaagent(renamed, "html=false")));
    assertThat(underRenamed.agentLines()).containsExactly(appendedLine(renamed));

    var verbose = run(dir, with(patch, javaagent(fatJar, "verbose,html=false")));
    assertThat(verbose.agentLines()).isEmpty();
    assertThat(verbose.stderr())
        .contains(BYTE_BUDDY_TRANSFORM + SCOPE + " ")
        .contains(BYTE_BUDDY_TRANSFORM + SCOPE_IMPL + " ");

    var preloaded = run(dir, with(patch, loader, javaagent(fatJar, "html=false")));
    assertThat(preloaded.agentLines()).isEmpty();
    assertSameProgram(preloaded, run(dir, with(patch, loader)));

    var retransformed = run(dir, with(patch, javaagent(fatJar, "html=false"), retransformer));
    assertThat(retransformed.agentLines()).isEmpty();
    assertSameProgram(retransformed, run(dir, with(patch, retransformer)));

    // the analyzer's executable jar: thousands of classes, no scope class
    String analyzerJar = System.getProperty("analyzerExecutableJar", "");
    assertThat(Path.of(analyzerJar)).as("the analyzer's -executable jar").exists();
    var command =
        new ArrayList<>(
            ChildJvm.javaCommand(
                ChildJvm.javaHome().resolve("bin").resolve("java").toString(),
                "-Xlog:jfr+startup=off"));
    command.addAll(patch);
    command.addAll(
        List.of(
            javaagent(fatJar, ""),
            "-jar",
            analyzerJar,
            jfr.toString(),
            dir.resolve("analyzer.html").toString()));
    var process =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(dir.resolve("analyzer.out").toFile())
            .redirectError(dir.resolve("analyzer.err").toFile())
            .start();
    assertThat(process.waitFor(ChildJvm.TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    String analyzerErr = Files.readString(dir.resolve("analyzer.err"));
    assertThat(process.exitValue()).as(analyzerErr).isZero();
    assertThat(analyzerErr).doesNotContain("cannot instrument").doesNotContain("WARN");
    assertThat(dir.resolve("analyzer.html")).exists();

    for (var run : List.of(original, underRenamed, verbose, preloaded, retransformed)) {
      assertThat(run.stderr()).doesNotContain("cannot instrument");
      assertThat(run.warnings()).isEmpty();
    }
  }

  // --- criterion 10 ---

  @Test
  void premainLoadsNoScopeClass(@TempDir Path dir) throws Exception {
    var with =
        ChildJvm.run(dir, List.of(javaagent(fatJar, "html=false"), "-Xlog:class+load"), "none");

    assertThat(with.exitCode()).as(with.stderr()).isZero();
    List<String> loaded = with.stdout().lines().filter(l -> l.contains("[class,load]")).toList();
    assertThat(loaded).as("the class+load log").isNotEmpty();
    assertThat(loaded)
        .noneMatch(l -> l.contains(" java.util.concurrent.StructuredTaskScope"))
        .noneMatch(l -> l.contains(" com.ionutbanu.scopetracer.agent.FirstOpenCheck "))
        .anyMatch(l -> l.contains(" com.ionutbanu.scopetracer.agent.MatcherMissListener "));
    assertThat(with.agentLines()).isEmpty();
  }

  // --- helpers ---

  /** {@link ExitSubject} {@value #SUBJECT} in a child JVM with {@code jvmArgs}. */
  private static ChildJvm.Result run(Path dir, List<String> jvmArgs) throws Exception {
    return ChildJvm.run(dir, jvmArgs, SUBJECT);
  }

  private static List<String> with(List<String> first, String... more) {
    var all = new ArrayList<>(first);
    all.addAll(List.of(more));
    return all;
  }

  /** {@code --patch-module java.base=<dir>} for {@code change}, written under {@code dir}. */
  private static String patch(Path dir, Change change) throws Exception {
    Path classes = JdkPatch.write(Files.createDirectories(dir.resolve("patch-" + change)), change);
    return "--patch-module=java.base=" + classes;
  }

  private static List<String> linesFor(ChildJvm.Result run, String type) {
    return run.agentLines().stream().filter(l -> l.startsWith(CANNOT + type + ",")).toList();
  }

  /**
   * Criterion 9: same exit code, byte-identical stdout, and stderr equal once the agent's lines,
   * ByteBuddy's lines (under {@code verbose}) and HotSpot's CDS line are removed.
   */
  private static void assertSameProgram(ChildJvm.Result with, ChildJvm.Result without) {
    assertThat(with.exitCode())
        .as("exit code; stderr:%n%s", with.stderr())
        .isEqualTo(without.exitCode())
        .isZero();
    assertThat(with.stdout()).as("stdout").isEqualTo(without.stdout());
    assertThat(programStderr(with.stderr()))
        .as("stderr without the agent's, ByteBuddy's and the CDS lines")
        .isEqualTo(programStderr(without.stderr()));
  }

  private static List<String> programStderr(String stderr) {
    return stderr
        .lines()
        .filter(l -> !l.startsWith(ChildJvm.AGENT_PREFIX))
        .filter(l -> !l.startsWith("[Byte Buddy]"))
        .filter(l -> !l.contains(AgentRenamedJarIT.CDS_WARNING))
        .toList();
  }

  /** Compiles {@code sources} (path without {@code .java} to source) into {@code dir/<id>}. */
  private static Path compile(Path dir, String id, Map<String, String> sources, String classPath)
      throws Exception {
    Path src = dir.resolve(id + "-src");
    Path classes = Files.createDirectories(dir.resolve(id));
    var args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", classPath));
    for (var e : new TreeMap<>(sources).entrySet()) {
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

  /** A {@code -javaagent} jar holding {@code className}, with retransformation allowed. */
  private static Path helperAgent(Path dir, String className, String source) throws Exception {
    Path classes = compile(dir, className, Map.of(className, source), fatJar.toString());
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", className);
    manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
    Path jar = dir.resolve(className + ".jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      out.putNextEntry(new JarEntry(className + ".class"));
      out.write(Files.readAllBytes(classes.resolve(className + ".class")));
      out.closeEntry();
    }
    return jar;
  }

  private static Path copyAs(Path dir, String relative) throws Exception {
    Path target = dir.resolve(relative).toAbsolutePath();
    Files.createDirectories(target.getParent());
    Files.copy(fatJar, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  private static String javaagent(Path jar, String args) {
    return "-javaagent:" + jar + (args.isEmpty() ? "" : "=" + args);
  }

  private static String appendedLine(Path jar) throws Exception {
    return ChildJvm.AGENT_PREFIX
        + " added "
        + jar.toRealPath()
        + " to the bootstrap class path, because the jar is not named "
        + originalName;
  }

  /** {@code AnalyzerMain jfr out [option]}, in process; its exit code. */
  private static int analyzer(Path jfr, Path out, String... options) throws Exception {
    Class<?> main = Class.forName("com.ionutbanu.scopetracer.analyzer.AnalyzerMain");
    Method run =
        main.getDeclaredMethod("run", String[].class, PrintStream.class, PrintStream.class);
    run.setAccessible(true);
    var args = new ArrayList<>(List.of(jfr.toString(), out.toString()));
    args.addAll(List.of(options));
    var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    return (int) run.invoke(null, args.toArray(String[]::new), sink, sink);
  }
}
