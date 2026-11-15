package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where the advice bytes come from (issue #123). Under a renamed agent jar the advice ByteBuddy
 * inlines into {@code StructuredTaskScope} and {@code StructuredTaskScopeImpl} must be read from
 * the agent's own jar, never from the application class path. Each test builds a "modified advice
 * jar" at run time: a jar whose {@code com/ionutbanu/scopetracer/agent/advice/<Name>.class} is not
 * the agent's, put on {@code -cp} ahead of {@link ExitSubject}.
 *
 * <p>The modified classes cannot live in {@code src/test/java}: their names are the real advice
 * classes' names. They need no ByteBuddy annotation, because ByteBuddy takes the annotations from
 * the loaded (real) class and only the method code from the located bytes.
 */
class AgentAdviceSourceIT {

  private static final String ADVICE_PACKAGE = "com.ionutbanu.scopetracer.agent.advice";
  private static final String MARKER = "MODIFIED ADVICE RAN";
  private static final String RECORDING_TO = ChildJvm.AGENT_PREFIX + " recording to ";
  private static final String APPENDED = " to the bootstrap class path, because the jar is not";
  private static final String PREFIX = "com.ionutbanu.scopetracer.";

  /** Each advice class's real method, with the same name and descriptor, printing the marker. */
  private static final Map<String, String> SAME_SIGNATURE =
      Map.of(
          "ScopeOpenAdvice", "public static void onOpen(Object scope)",
          "ScopeConstructorAdvice", "public static void onConstruct(String name)",
          "ForkAdvice",
              "public static void onFork(Object scope, java.util.concurrent.Callable task)",
          "ScopeCloseAdvice", "public static void onClose(Object scope)");

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

  /**
   * QA's reproduction: a same-signature {@code ScopeOpenAdvice} on {@code -cp} that only prints.
   */
  @Test
  void sameSignatureOpenAdviceOnTheClassPathIsNotInlined(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path modified =
        adviceJar(dir, "open", Map.of("ScopeOpenAdvice", markerClass("ScopeOpenAdvice")));
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(jar, "record=" + jfr)), List.of(modified.toString()), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).exists();
  }

  // --- criterion 2 ---

  /**
   * One advice class on {@code -cp} whose advice method is {@code x()}: ByteBuddy would take the
   * real method from the loaded class and code that does not contain it from these bytes, and the
   * JDK would reject the transformed class.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"ScopeOpenAdvice", "ScopeConstructorAdvice", "ForkAdvice", "ScopeCloseAdvice"})
  void mismatchedAdviceClassOnTheClassPathCannotBreakTheProgram(String name, @TempDir Path dir)
      throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    String source =
        "package "
            + ADVICE_PACKAGE
            + "; public final class "
            + name
            + " { public static void x() {} }";
    Path modified = adviceJar(dir, "mismatched", Map.of(name, source));
    Path jfr = dir.resolve("t.jfr");
    List<String> cp = List.of(modified.toString());
    var with = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), cp, "mixed");
    var without = ChildJvm.run(dir, List.of(), cp, "mixed");

    assertSameProgram(with, without);
    assertThat(with.stderr()).doesNotContain("ClassFormatError");
    assertMixedScopeFullyTraced(jfr);
  }

  // --- criterion 3 and 12 ---

  @Test
  void renamedJarWithAllFourOnTheClassPathGivesTheOriginalNamesTrace(@TempDir Path dir)
      throws Exception {
    Path original = copyAs(dir, "original/" + originalName);
    Path renamed = copyAs(dir, "renamed/agent.jar");
    Path modified = fourClassJar(dir);
    Path originalJfr = dir.resolve("original.jfr");
    Path renamedJfr = dir.resolve("renamed.jfr");
    var originalRun =
        ChildJvm.run(
            dir,
            List.of(javaagent(original, "record=" + originalJfr + ",html=false")),
            "mixed-tree");
    var renamedRun =
        ChildJvm.run(
            dir,
            List.of(javaagent(renamed, "record=" + renamedJfr + ",html=false")),
            List.of(modified.toString()),
            "mixed-tree");
    assertThat(originalRun.exitCode()).as(originalRun.stderr()).isZero();
    assertThat(renamedRun.exitCode()).as(renamedRun.stderr()).isZero();
    assertThat(renamedRun.stdout()).doesNotContain(MARKER);

    List<String> fromOriginal = AgentRecordIT.shape(JfrParser.parse(originalJfr));
    assertThat(fromOriginal).isNotEmpty();
    assertThat(AgentRecordIT.shape(JfrParser.parse(renamedJfr))).isEqualTo(fromOriginal);
    assertThat(callSites(originalJfr)).hasSize(9);
    assertThat(callSites(renamedJfr)).isEqualTo(callSites(originalJfr));

    // criterion 12: both recordings parse with the analyzer's CLI
    for (Path jfr : List.of(originalJfr, renamedJfr)) {
      Path json = dir.resolve(jfr.getFileName() + ".json");
      assertThat(analyzerJson(jfr, json)).as("AnalyzerMain exit code for " + jfr).isZero();
      assertThat(Files.readString(json)).contains(ExitSubject.MIXED_SCOPE);
    }
  }

  // --- criterion 4 ---

  @Test
  void startFlightRecordingRouteIgnoresTheClassPathAdvice(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("u.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(
                javaagent(jar, ""),
                "-XX:StartFlightRecording=filename=" + jfr + ",dumponexit=true"),
            List.of(fourClassJar(dir).toString()),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    assertMixedScopeFullyTraced(jfr);
    assertThat(Files.readString(dir.resolve("u.html"))).contains(ExitSubject.MIXED_SCOPE);
  }

  @Test
  void pathWithASpaceAndANonAsciiCharacterIgnoresTheClassPathAdvice(@TempDir Path dir)
      throws Exception {
    Path jar = copyAs(dir, "dir with space/agent ü.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "record=" + jfr)),
            List.of(fourClassJar(dir).toString()),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    assertMixedScopeFullyTraced(jfr);
    assertThat(Files.readString(dir.resolve("t.html"))).contains(ExitSubject.MIXED_SCOPE);
    assertThat(run.agentLines()).contains(appendedLine(jar));
  }

  // --- criterion 5 ---

  @Test
  void originalNameIgnoresTheClassPathAdviceAndPrintsNothingNew(@TempDir Path dir)
      throws Exception {
    Path jar = copyAs(dir, "elsewhere/" + originalName);
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "record=" + jfr)),
            List.of(fourClassJar(dir).toString()),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).exists();
    assertThat(run.agentLines())
        .containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(run.stderr()).doesNotContain(AgentRenamedJarIT.CDS_WARNING);
  }

  // --- criterion 6 ---

  @Test
  void bootclasspathAppendIgnoresTheClassPathAdvice(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of("-Xbootclasspath/a:" + jar, javaagent(jar, "record=" + jfr)),
            List.of(fourClassJar(dir).toString()),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).exists();
    assertThat(run.agentLines()).noneMatch(l -> l.contains(APPENDED)).hasSize(2);
    assertThat(run.stderr()).doesNotContain(AgentRenamedJarIT.CDS_WARNING);
  }

  // --- criterion 7 ---

  @Test
  void renamedJarPrintsTheSameLinesAsBeforeEachOnce(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "record=" + jfr)),
            List.of(fourClassJar(dir).toString()),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.agentLines())
        .containsExactly(
            appendedLine(jar), RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(run.stderr().lines().filter(l -> l.contains(AgentRenamedJarIT.CDS_WARNING)))
        .hasSize(1);
    assertThat(
            run.stderr()
                .lines()
                .filter(l -> !l.startsWith(ChildJvm.AGENT_PREFIX))
                .filter(l -> !l.contains(AgentRenamedJarIT.CDS_WARNING)))
        .as("stderr apart from the agent's lines and the CDS line")
        .isEmpty();
  }

  @Test
  void verboseTransformsEachClassOnceWithTheClassPathAdvice(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "verbose")),
            List.of(fourClassJar(dir).toString()),
            "scopes",
            "v");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).doesNotContain(MARKER);
    for (String type :
        List.of(
            "java.util.concurrent.StructuredTaskScope",
            "java.util.concurrent.StructuredTaskScopeImpl")) {
      assertThat(run.stderr().lines().filter(l -> l.contains("TRANSFORM " + type + " ")))
          .as("TRANSFORM lines for %s in%n%s", type, run.stderr())
          .hasSize(1);
    }
    assertThat(run.stderr()).doesNotContain(" ERROR ");
  }

  // --- criterion 10 ---

  @Test
  void applicationSeesTheSameSystemPropertyKeysUnderBothNames(@TempDir Path dir) throws Exception {
    Path original = copyAs(dir, "original/" + originalName);
    Path renamed = copyAs(dir, "renamed/agent.jar");
    var fromOriginal = ChildJvm.run(dir, List.of(javaagent(original, "html=false")), "properties");
    var fromRenamed = ChildJvm.run(dir, List.of(javaagent(renamed, "html=false")), "properties");

    assertThat(fromOriginal.exitCode()).as(fromOriginal.stderr()).isZero();
    assertThat(fromRenamed.exitCode()).as(fromRenamed.stderr()).isZero();
    List<String> keys = propertyKeys(fromOriginal.stdout());
    assertThat(keys).contains("java.home");
    assertThat(propertyKeys(fromRenamed.stdout())).isEqualTo(keys);
  }

  @Test
  void premainIsStillTheOnlyPublicMember() {
    assertThat(
            Arrays.stream(ScopeTracerAgent.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::toGenericString))
        .containsExactly(
            "public static void com.ionutbanu.scopetracer.agent.ScopeTracerAgent.premain("
                + "java.lang.String,java.lang.instrument.Instrumentation)");
    assertThat(ScopeTracerAgent.class.getFields()).isEmpty();
    assertThat(ScopeTracerAgent.class.getConstructors()).isEmpty();
    assertThat(ScopeTracerAgent.class.getClasses()).isEmpty();
  }

  // --- helpers ---

  private static List<String> propertyKeys(String stdout) {
    return stdout.lines().filter(l -> l.startsWith("property ")).map(l -> l.substring(9)).toList();
  }

  /** Copies the fat-jar to {@code dir/relative}, creating directories; the absolute path. */
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
        + APPENDED
        + " named "
        + originalName;
  }

  /** The source of {@code name} with its real advice method's signature, printing the marker. */
  private static String markerClass(String name) {
    return "package "
        + ADVICE_PACKAGE
        + "; public final class "
        + name
        + " { "
        + SAME_SIGNATURE.get(name)
        + " { System.out.println(\""
        + MARKER
        + "\"); } }";
  }

  /** All four advice classes, each with its real signature and a body that only prints. */
  private static Path fourClassJar(Path dir) throws Exception {
    var sources = new java.util.TreeMap<String, String>();
    for (String name : SAME_SIGNATURE.keySet()) {
      sources.put(name, markerClass(name));
    }
    return adviceJar(dir, "four", sources);
  }

  /** Compiles {@code sources} (simple class name to source) into {@code dir/<id>.jar}. */
  private static Path adviceJar(Path dir, String id, Map<String, String> sources) throws Exception {
    Path src = Files.createDirectories(dir.resolve(id + "-src"));
    Path classes = Files.createDirectories(dir.resolve(id + "-classes"));
    var args = new ArrayList<>(List.of("-d", classes.toString()));
    for (var e : sources.entrySet()) {
      Path file = src.resolve(e.getKey() + ".java");
      Files.writeString(file, e.getValue());
      args.add(file.toString());
    }
    var err = new ByteArrayOutputStream();
    int rc = ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new));
    assertThat(rc).as(err.toString(StandardCharsets.UTF_8)).isZero();

    Path jar = dir.resolve(id + ".jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String name : sources.keySet()) {
        String entry = ADVICE_PACKAGE.replace('.', '/') + "/" + name + ".class";
        out.putNextEntry(new JarEntry(entry));
        out.write(Files.readAllBytes(classes.resolve(entry)));
        out.closeEntry();
      }
    }
    return jar;
  }

  /** Exactly the six events of {@link ExitSubject}'s {@code mixed} scope. */
  private static void assertMixedScopeFullyTraced(Path jfr) throws Exception {
    var events = ScopeEvents.read(jfr);
    assertThat(events.opened(ExitSubject.MIXED_SCOPE)).hasSize(1);
    long id = events.opened(ExitSubject.MIXED_SCOPE).get(0).scopeId();
    assertThat(events.scopeIdsNamed(ExitSubject.MIXED_SCOPE)).containsExactly(id);
    assertThat(events.events().stream().filter(e -> e.scopeId() == id).map(e -> e.type()))
        .containsExactlyInAnyOrder(
            "ScopeOpened",
            "TaskForked",
            "TaskForked",
            "TaskSucceeded",
            "TaskFailed",
            "ScopeClosed");
  }

  /** {@code scopeName taskName class#method:line} of every {@code TaskForked}, sorted. */
  private static List<String> callSites(Path jfr) throws Exception {
    var sites = new ArrayList<String>();
    for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
      if (e.getEventType().getName().equals(PREFIX + "TaskForked")) {
        sites.add(
            e.getString("scopeName")
                + " "
                + e.getString("taskName")
                + " "
                + e.getString("callSiteClassName")
                + "#"
                + e.getString("callSiteMethodName")
                + ":"
                + e.getInt("callSiteLine"));
      }
    }
    sites.sort(null);
    return sites;
  }

  /** {@code AnalyzerMain jfr json --format=json}, in process; its exit code. */
  private static int analyzerJson(Path jfr, Path json) throws Exception {
    Class<?> main = Class.forName("com.ionutbanu.scopetracer.analyzer.AnalyzerMain");
    Method run =
        main.getDeclaredMethod("run", String[].class, PrintStream.class, PrintStream.class);
    run.setAccessible(true);
    var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    return (int)
        run.invoke(
            null, new String[] {jfr.toString(), json.toString(), "--format=json"}, sink, sink);
  }

  /**
   * Same exit code, byte-identical stdout, and the same stderr once the agent's lines and HotSpot's
   * CDS line are removed.
   */
  private static void assertSameProgram(ChildJvm.Result with, ChildJvm.Result without) {
    assertThat(with.exitCode())
        .as("exit code; stderr:%n%s", with.stderr())
        .isEqualTo(without.exitCode());
    assertThat(with.stdout()).as("stdout").isEqualTo(without.stdout());
    assertThat(withoutAgentOrCds(with.stderr()))
        .as("stderr without [scope-tracer] and CDS lines")
        .isEqualTo(withoutAgentOrCds(without.stderr()));
  }

  private static List<String> withoutAgentOrCds(String stderr) {
    return stderr
        .lines()
        .filter(
            l -> !l.startsWith(ChildJvm.AGENT_PREFIX) && !l.contains(AgentRenamedJarIT.CDS_WARNING))
        .toList();
  }
}
