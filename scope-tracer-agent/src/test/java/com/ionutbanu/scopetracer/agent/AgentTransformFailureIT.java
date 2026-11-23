package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A transformation of {@code StructuredTaskScope} or {@code StructuredTaskScopeImpl} that ByteBuddy
 * cannot apply prints one {@code [scope-tracer] WARN cannot instrument <class>, ...} line per
 * class, at most once per JVM, with or without {@code verbose} (issue #121). Before, it was silent
 * and the recording came out empty or held only {@code ScopeOpened}.
 *
 * <p>The failure is made with a crafted advice class on {@code -Xbootclasspath/a:}: the bootstrap
 * loader finds it ahead of the agent jar's copy. It has the real class name and advice method plus
 * one parameter ByteBuddy cannot bind, the kind of failure a JDK that changes these classes would
 * cause. Each is compiled at run time against the fat-jar, for its relocated {@code Advice}
 * annotations, and cannot live in {@code src/test/java} under the advice classes' real names.
 */
class AgentTransformFailureIT {

  private static final String SCOPE = "java.util.concurrent.StructuredTaskScope";
  private static final String SCOPE_IMPL = "java.util.concurrent.StructuredTaskScopeImpl";
  private static final String CANNOT = ChildJvm.AGENT_PREFIX + " WARN cannot instrument ";
  private static final String INCOMPLETE = ", the trace will be incomplete: ";
  private static final String RECORDING_TO = ChildJvm.AGENT_PREFIX + " recording to ";
  private static final String ADVICE_PACKAGE = "com.ionutbanu.scopetracer.agent.advice";
  private static final String SHADED_ADVICE =
      "com.ionutbanu.scopetracer.shaded.net.bytebuddy.asm.Advice";
  private static final String BYTE_BUDDY_ERROR = "[Byte Buddy] ERROR ";
  private static final String SUBJECT = "mixed-tree";

  /** The crafted advice classes: the real method, with one parameter that cannot be bound. */
  private static final Map<String, String> CRAFTED =
      Map.of(
          "ScopeOpenAdvice",
          "@Advice.OnMethodExit(suppress = Throwable.class) public static void onOpen("
              + "@Advice.Return Object scope, @Advice.Argument(9) Object none) {}",
          "ScopeConstructorAdvice",
          "@Advice.OnMethodEnter(suppress = Throwable.class) public static void onConstruct("
              + "@Advice.Argument(7) String name) {}",
          "ScopeCloseAdvice",
          "@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)"
              + " public static void onClose(@Advice.This Object scope, @Advice.Return String r)"
              + " {}");

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
  void openAdviceThatCannotBeAppliedNamesStructuredTaskScopeOnce(@TempDir Path dir)
      throws Exception {
    String boot = bootPath(crafted(dir, "ScopeOpenAdvice"));
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines()).containsExactly(RECORDING_TO + jfr, lineFor(with, SCOPE));
    assertThat(lineFor(with, SCOPE))
        .startsWith(CANNOT + SCOPE + INCOMPLETE + "IllegalStateException: ")
        .contains("does not define an index 9");
    assertThat(linesFor(with, SCOPE_IMPL)).isEmpty();
    assertThat(ScopeEvents.read(jfr).events()).as("scope-tracer events").isEmpty();
  }

  // --- criteria 2 and 13 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"ScopeConstructorAdvice", "ScopeCloseAdvice"})
  void implAdviceThatCannotBeAppliedNamesStructuredTaskScopeImplOnce(
      String crafted, @TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, crafted));
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines())
        .containsExactly(
            RECORDING_TO + jfr,
            lineFor(with, SCOPE_IMPL),
            ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(linesFor(with, SCOPE)).isEmpty();
    var events = ScopeEvents.read(jfr);
    assertThat(events.ofType(ScopeEvents.SCOPE_OPENED)).isNotEmpty();
    assertThat(events.ofType(ScopeEvents.TASK_FORKED)).isEmpty();
    assertThat(events.ofType(ScopeEvents.SCOPE_CLOSED)).isEmpty();
    assertThat(dir.resolve("t.html")).exists();

    // criterion 13: scopes opened and never closed, no forks, go through the analyzer's CLI
    assertThat(analyzer(jfr, dir.resolve("t.json"), "--format=json")).isZero();
    assertThat(Files.readString(dir.resolve("t.json"))).contains(ExitSubject.MIXED_SCOPE);
    assertThat(analyzer(jfr, dir.resolve("cli.html"))).isZero();
    assertThat(Files.readString(dir.resolve("cli.html"))).contains(ExitSubject.MIXED_SCOPE);
  }

  // --- criterion 3 ---

  @Test
  void bothClassesFailingGiveOneLineEach(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeOpenAdvice", "ScopeConstructorAdvice"));
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(linesFor(with, SCOPE)).hasSize(1);
    assertThat(linesFor(with, SCOPE_IMPL)).hasSize(1);
    assertThat(with.agentLines().stream().filter(l -> l.startsWith(CANNOT))).hasSize(2);
  }

  // --- criterion 4 ---

  @Test
  void aClassReportedThreeTimesIsNamedOnce(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeConstructorAdvice"));
    String retransformer =
        "-javaagent:" + helperAgent(dir, "RetransformsScopeClasses", RETRANSFORMING_AGENT);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr), retransformer));
    var withVerbose =
        run(dir, List.of(boot, javaagent(fatJar, "verbose,html=false"), retransformer));

    assertSameProgram(with, run(dir, List.of(boot, retransformer)));
    assertThat(linesFor(with, SCOPE_IMPL)).hasSize(1);
    assertThat(linesFor(with, SCOPE)).isEmpty();
    assertThat(
            withVerbose
                .stderr()
                .lines()
                .filter(l -> l.startsWith(BYTE_BUDDY_ERROR + SCOPE_IMPL + " ")))
        .as("ByteBuddy's own reports of the failure in%n%s", withVerbose.stderr())
        .hasSize(3);
    assertThat(linesFor(withVerbose, SCOPE_IMPL)).hasSize(1);
  }

  // --- criterion 5 ---

  @Test
  void alreadyLoadedClassIsNamedBeforeTheRecordingStarts(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeOpenAdvice"));
    String loader = "-javaagent:" + helperAgent(dir, "LoadsScopeClasses", LOADER_AGENT);
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, loader, javaagent(fatJar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot, loader)));
    assertThat(with.agentLines()).containsExactly(lineFor(with, SCOPE), RECORDING_TO + jfr);
    assertThat(lineFor(with, SCOPE)).contains("does not define an index 9");
  }

  // --- criterion 6 ---

  @Test
  void renamedJarPrintsTheSameSingleLine(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeConstructorAdvice"));
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(jar, "record=" + jfr)));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines())
        .containsExactly(
            appendedLine(jar),
            RECORDING_TO + jfr,
            lineFor(with, SCOPE_IMPL),
            ChildJvm.WRITTEN + dir.resolve("t.html"));
  }

  @Test
  void startFlightRecordingWithoutRecordPrintsTheSameSingleLine(@TempDir Path dir)
      throws Exception {
    String boot = bootPath(crafted(dir, "ScopeConstructorAdvice"));
    Path jfr = dir.resolve("u.jfr");
    var with =
        run(
            dir,
            List.of(
                boot,
                javaagent(fatJar, ""),
                "-XX:StartFlightRecording=filename=" + jfr + ",dumponexit=true"));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines())
        .containsExactly(lineFor(with, SCOPE_IMPL), ChildJvm.WRITTEN + dir.resolve("u.html"));
  }

  @Test
  void htmlFalsePrintsTheSameSingleLine(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeConstructorAdvice"));
    Path jfr = dir.resolve("t.jfr");
    var with = run(dir, List.of(boot, javaagent(fatJar, "record=" + jfr + ",html=false")));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines()).containsExactly(RECORDING_TO + jfr, lineFor(with, SCOPE_IMPL));
  }

  // --- criterion 7 ---

  @Test
  void verboseKeepsByteBuddysReportAndAddsTheLine(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeOpenAdvice"));
    var with = run(dir, List.of(boot, javaagent(fatJar, "verbose,html=false")));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.stderr().lines().filter(l -> l.startsWith(BYTE_BUDDY_ERROR + SCOPE + " ")))
        .as("ByteBuddy's report in%n%s", with.stderr())
        .hasSize(1);
    assertThat(with.stderr()).contains("\tat ");
    assertThat(linesFor(with, SCOPE)).hasSize(1);
    assertThat(lineFor(with, SCOPE)).contains("does not define an index 9");
  }

  @Test
  void verboseFalsePrintsTheLineAndNothingOfByteBuddys(@TempDir Path dir) throws Exception {
    String boot = bootPath(crafted(dir, "ScopeOpenAdvice"));
    var with = run(dir, List.of(boot, javaagent(fatJar, "verbose=false,html=false")));

    assertSameProgram(with, run(dir, List.of(boot)));
    assertThat(with.agentLines()).containsExactly(lineFor(with, SCOPE));
    assertThat(with.stderr()).doesNotContain("[Byte Buddy]");
  }

  // --- criterion 8 ---

  @Test
  void nothingFailingPrintsNoLine(@TempDir Path dir) throws Exception {
    Path renamed = copyAs(dir, "renamed/agent.jar");
    String retransformer =
        "-javaagent:" + helperAgent(dir, "RetransformsScopeClasses", RETRANSFORMING_AGENT);
    Path jfr = dir.resolve("t.jfr");

    var original = run(dir, List.of(javaagent(fatJar, "record=" + jfr)));
    assertThat(original.agentLines())
        .containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertSameProgram(original, run(dir, List.of()));

    var underRenamed = run(dir, List.of(javaagent(renamed, "html=false")));
    assertThat(underRenamed.agentLines()).containsExactly(appendedLine(renamed));

    var verbose = run(dir, List.of(javaagent(fatJar, "verbose,html=false")));
    assertThat(verbose.agentLines()).isEmpty();
    assertThat(verbose.stderr()).doesNotContain(BYTE_BUDDY_ERROR).contains("TRANSFORM " + SCOPE);

    var retransformed = run(dir, List.of(javaagent(fatJar, "html=false"), retransformer));
    assertThat(retransformed.agentLines()).isEmpty();
    assertSameProgram(retransformed, run(dir, List.of(retransformer)));

    // the analyzer's executable jar: thousands of classes, no scope class
    String analyzerJar = System.getProperty("analyzerExecutableJar", "");
    assertThat(Path.of(analyzerJar)).as("the analyzer's -executable jar").exists();
    var command =
        ChildJvm.javaCommand(
            ChildJvm.javaHome().resolve("bin").resolve("java").toString(),
            "-Xlog:jfr+startup=off",
            javaagent(fatJar, ""),
            "-jar",
            analyzerJar,
            jfr.toString(),
            dir.resolve("analyzer.html").toString());
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

    for (var run : List.of(original, underRenamed, verbose, retransformed)) {
      assertThat(run.stderr()).doesNotContain("cannot instrument");
      assertThat(run.warnings()).isEmpty();
    }
  }

  // --- helpers ---

  /** {@link ExitSubject} {@value #SUBJECT} in a child JVM with {@code jvmArgs}. */
  private static ChildJvm.Result run(Path dir, List<String> jvmArgs) throws Exception {
    return ChildJvm.run(dir, jvmArgs, SUBJECT);
  }

  private static List<String> linesFor(ChildJvm.Result run, String type) {
    return run.agentLines().stream().filter(l -> l.startsWith(CANNOT + type + ",")).toList();
  }

  /** The one line for {@code type}; fails when there is not exactly one. */
  private static String lineFor(ChildJvm.Result run, String type) {
    List<String> lines = linesFor(run, type);
    assertThat(lines).as("lines for %s in%n%s", type, run.stderr()).hasSize(1);
    return lines.get(0);
  }

  /**
   * Criterion 9: same exit code, byte-identical stdout, and stderr equal once the agent's lines,
   * ByteBuddy's report with its stack trace (under {@code verbose}) and HotSpot's CDS line are
   * removed.
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
    var kept = new ArrayList<String>();
    boolean inByteBuddyReport = false;
    for (String line : stderr.lines().toList()) {
      if (line.startsWith("[Byte Buddy]")) {
        inByteBuddyReport = line.startsWith(BYTE_BUDDY_ERROR);
        continue;
      }
      if (inByteBuddyReport
          && (line.startsWith("\t") || line.startsWith("Caused by: ") || isThrowableLine(line))) {
        continue;
      }
      inByteBuddyReport = false;
      if (line.startsWith(ChildJvm.AGENT_PREFIX) || line.contains(AgentRenamedJarIT.CDS_WARNING)) {
        continue;
      }
      kept.add(line);
    }
    return kept;
  }

  /** The first line of a printed stack trace: {@code java.lang.IllegalStateException: ...}. */
  private static boolean isThrowableLine(String line) {
    return line.matches("[\\w.$]+(Exception|Error|Throwable)(: .*)?");
  }

  /** Compiles the crafted advice classes {@code names} into a directory; that directory. */
  private static Path crafted(Path dir, String... names) throws Exception {
    var sources = new TreeMap<String, String>();
    for (String name : names) {
      sources.put(
          ADVICE_PACKAGE.replace('.', '/') + "/" + name,
          "package "
              + ADVICE_PACKAGE
              + "; import "
              + SHADED_ADVICE
              + "; public final class "
              + name
              + " { "
              + CRAFTED.get(name)
              + " }");
    }
    return compile(dir, "crafted", sources, fatJar.toString());
  }

  /** Compiles {@code sources} (path without {@code .java} to source) into {@code dir/<id>}. */
  private static Path compile(Path dir, String id, Map<String, String> sources, String classPath)
      throws Exception {
    Path src = dir.resolve(id + "-src");
    Path classes = Files.createDirectories(dir.resolve(id));
    var args = new ArrayList<>(List.of("-d", classes.toString(), "-cp", classPath));
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

  private static String bootPath(Path classes) {
    return "-Xbootclasspath/a:" + classes;
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
