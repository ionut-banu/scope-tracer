package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import java.io.File;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The agent fat-jar under a file name other than the one its manifest's {@code Boot-Class-Path}
 * lists (issue #113). Each test copies the fat-jar from {@code target/} into a fresh temporary
 * directory that holds no file with the original name, runs {@link ExitSubject} (or {@link
 * IsolationTestSubject}) under it in a child JVM, and reads the {@code .jfr} it left back.
 */
class AgentRenamedJarIT {

  /** The line HotSpot prints once a jar has been appended to the bootstrap class path. */
  static final String CDS_WARNING =
      "Sharing is only supported for boot loader classes because bootstrap classpath has been"
          + " appended";

  private static final String RECORDING_TO = ChildJvm.AGENT_PREFIX + " recording to ";
  private static final String APPENDED = " to the bootstrap class path, because the jar is not";
  private static final String PREFIX = "com.ionutbanu.scopetracer.";

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

  /**
   * Copies the fat-jar to {@code dir/relative}, creating directories; returns the absolute path.
   */
  private static Path copyAs(Path dir, String relative) throws Exception {
    Path target = dir.resolve(relative).toAbsolutePath();
    Files.createDirectories(target.getParent());
    Files.copy(fatJar, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  private static String javaagent(Path jar, String args) {
    return "-javaagent:" + jar + (args.isEmpty() ? "" : "=" + args);
  }

  /**
   * The info line of criterion 8 for {@code jar}. It names the jar's real path: the JVM resolves
   * the {@code -javaagent} path, symbolic links included, before it becomes the code source.
   */
  private static String appendedLine(Path jar) throws Exception {
    return ChildJvm.AGENT_PREFIX
        + " added "
        + jar.toRealPath()
        + APPENDED
        + " named "
        + originalName;
  }

  // --- criterion 1 ---

  @Test
  void renamedJarRecordsEveryEventAndWritesTheReport(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(Files.readString(dir.resolve("t.html"))).contains(ExitSubject.MIXED_SCOPE);
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

  // --- criterion 2 ---

  /**
   * One program, once under the original file name and once under {@code agent.jar}: the analyzer
   * models have the same scopes, parents, tasks and outcomes, and every {@code TaskForked} the same
   * call site.
   */
  @Test
  void renamedJarGivesTheSameTraceAsTheOriginalName(@TempDir Path dir) throws Exception {
    Path original = copyAs(dir, "original/" + originalName);
    Path renamed = copyAs(dir, "renamed/agent.jar");
    Path originalJfr = dir.resolve("original.jfr");
    Path renamedJfr = dir.resolve("renamed.jfr");
    var originalRun =
        ChildJvm.run(
            dir,
            List.of(javaagent(original, "record=" + originalJfr + ",html=false")),
            "mixed-tree");
    var renamedRun =
        ChildJvm.run(
            dir, List.of(javaagent(renamed, "record=" + renamedJfr + ",html=false")), "mixed-tree");
    assertThat(originalRun.exitCode()).as(originalRun.stderr()).isZero();
    assertThat(renamedRun.exitCode()).as(renamedRun.stderr()).isZero();

    List<String> fromOriginal = AgentRecordIT.shape(JfrParser.parse(originalJfr));
    assertThat(AgentRecordIT.shape(JfrParser.parse(renamedJfr))).isEqualTo(fromOriginal);
    assertThat(callSites(renamedJfr)).isEqualTo(callSites(originalJfr));
    // and the trace is the one the subject was written to produce, not two empty ones
    assertThat(fromOriginal)
        .anyMatch(
            s ->
                s.startsWith(ExitSubject.MIXED_SCOPE + " <- root")
                    && s.contains("Failed(java.lang.IllegalStateException)")
                    && s.contains("Success"))
        .anyMatch(
            s ->
                s.startsWith(ExitSubject.TREE_FAIL + " <- root")
                    && s.contains("Failed(java.lang.IllegalStateException)")
                    && s.contains("Cancelled"))
        .anyMatch(
            s -> s.startsWith(ExitSubject.TREE_LEVEL_2 + " <- " + ExitSubject.TREE_LEVEL_1 + "/"));
    assertThat(callSites(originalJfr)).hasSize(9).noneMatch(s -> s.contains("null#null:0"));
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

  // --- criterion 3 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"return", "exit"})
  void programIsUnchangedUnderTheRenamedJar(String mode, @TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var with = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), mode);
    var without = ChildJvm.run(dir, List.of(), mode);

    assertSameProgram(with, without);
    assertThat(ScopeEvents.read(jfr).opened(ExitSubject.EXIT_SCOPE)).hasSize(1);
    assertThat(dir.resolve("t.html")).exists();
  }

  // --- criterion 4 ---

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"scope-tracer-agent.jar", "dir with space/agent ü.jar"})
  void otherNamesAndPathsTrace(String relative, @TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, relative);
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(Files.readString(dir.resolve("t.html"))).contains(ExitSubject.MIXED_SCOPE);
    assertThat(run.agentLines()).contains(appendedLine(jar));
  }

  @Test
  void relativeJavaagentPathTraces(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of("-javaagent:agent.jar=record=" + jfr), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).exists();
    // the absolute path, as the child resolved it from its working directory
    assertThat(run.agentLines()).contains(appendedLine(jar));
  }

  // --- criterion 5 ---

  @Test
  void includeNameFilterAppliesUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "record=" + jfr + ",include.name=keep-*")),
            "scopes",
            "keep-1,drop-1");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    var events = ScopeEvents.read(jfr);
    long id = events.opened("keep-1").get(0).scopeId();
    assertThat(events.events().stream().filter(e -> e.scopeId() == id).map(e -> e.type()))
        .containsExactlyInAnyOrder("ScopeOpened", "TaskForked", "TaskSucceeded", "ScopeClosed");
    assertThat(events.events()).noneMatch(e -> "drop-1".equals(e.scopeName()));
  }

  @Test
  void htmlFalseAppliesUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr + ",html=false")), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).doesNotExist();
    assertThat(run.writtenReports()).isEmpty();
  }

  @Test
  void verboseTransformsEachClassOnceUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "verbose")), "scopes", "v");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    for (String type :
        List.of(
            "java.util.concurrent.StructuredTaskScope",
            "java.util.concurrent.StructuredTaskScopeImpl")) {
      assertThat(run.stderr().lines().filter(l -> l.contains("TRANSFORM " + type + " ")))
          .as("TRANSFORM lines for %s in%n%s", type, run.stderr())
          .hasSize(1);
    }
  }

  @Test
  void debugLineFollowsVerboseUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    String skip = ChildJvm.AGENT_PREFIX + " DEBUG skipping auto-HTML:";
    String record = "record=" + dir.resolve("t.jfr") + ",min.scopes=99";
    var verbose = ChildJvm.run(dir, List.of(javaagent(jar, record + ",verbose")), "mixed");
    var off =
        ChildJvm.run(
            dir,
            List.of("-Dscopetracer.agent.verbose=true", javaagent(jar, record + ",verbose=false")),
            "mixed");

    assertThat(verbose.stderr().lines().filter(l -> l.startsWith(skip)))
        .containsExactly(skip + " 1 scope(s) is below min.scopes=99");
    assertThat(off.stderr().lines().filter(l -> l.startsWith(skip))).isEmpty();
    assertThat(off.stderr()).doesNotContain("[Byte Buddy]");
  }

  // --- criterion 6 ---

  @Test
  void startFlightRecordingRouteWorksUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("u.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(
                javaagent(jar, ""),
                "-XX:StartFlightRecording=filename=" + jfr + ",dumponexit=true"),
            "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(Files.readString(dir.resolve("u.html"))).contains(ExitSubject.MIXED_SCOPE);
  }

  // --- criterion 7 ---

  /**
   * {@link IsolationTestSubject} in both of {@link AgentIsolationIT}'s class-path layouts, under
   * the original name and under {@code agent.jar}: the application sees the same SLF4J, Logback and
   * ByteBuddy, prints the same SLF4J lines, and the agent still traces and writes its report.
   */
  @Test
  void isolationHoldsUnderTheRenamedJar(@TempDir Path dir) throws Exception {
    Path original = copyAs(dir, "original/" + originalName);
    Path renamed = copyAs(dir, "renamed/agent.jar");
    for (String layout : List.of("plain", "child")) {
      for (String provider : layout.equals("plain") ? List.of("", "logback") : List.of("")) {
        var fromOriginal = isolation(dir, "orig-" + layout + provider, original, layout, provider);
        var fromRenamed = isolation(dir, "ren-" + layout + provider, renamed, layout, provider);

        assertThat(fromRenamed.exitCode()).as(fromRenamed.output()).isZero();
        assertThat(fromOriginal.exitCode()).as(fromOriginal.output()).isZero();
        assertThat(fromRenamed.facts()).as(layout + provider).isEqualTo(fromOriginal.facts());
        assertThat(fromRenamed.facts().getProperty("slf4j.loader")).isEqualTo("expected");
        assertThat(fromRenamed.facts().getProperty("bytebuddy.loader")).isEqualTo("expected");
        assertThat(fromRenamed.linesContaining("SLF4J("))
            .isEqualTo(fromOriginal.linesContaining("SLF4J("));
        assertThat(fromRenamed.linesContaining(IsolationTestSubject.LOG_LINE)).hasSize(1);
        assertThat(
                fromRenamed
                    .output()
                    .lines()
                    .filter(l -> !l.startsWith(ChildJvm.AGENT_PREFIX))
                    .filter(l -> !l.contains(CDS_WARNING))
                    .filter(l -> !l.contains(IsolationTestSubject.LOG_LINE))
                    .filter(l -> !l.contains("SLF4J(")))
            .as("output lines the subject does not print")
            .isEmpty();
        assertThat(JfrParser.parse(fromRenamed.jfr()).scopes())
            .extracting(ScopeRecord::name)
            .containsExactlyInAnyOrder(
                IsolationTestSubject.UNNAMED_SCOPE, IsolationTestSubject.TRUNCATED_SCOPE);
        assertThat(fromRenamed.html()).exists();
      }
    }
  }

  private record Isolation(int exitCode, String output, Properties facts, Path jfr, Path html) {
    List<String> linesContaining(String text) {
      return output.lines().filter(l -> l.contains(text)).toList();
    }
  }

  private static Isolation isolation(
      Path dir, String id, Path agentJar, String layout, String provider) throws Exception {
    Path runDir = Files.createDirectories(dir.resolve(id));
    Path jfr = runDir.resolve("isolation.jfr");
    Path facts = runDir.resolve("facts.properties");
    List<String> loggingJars =
        List.of(
            jarOf(org.slf4j.LoggerFactory.class),
            jarOf(ch.qos.logback.classic.LoggerContext.class),
            jarOf(ch.qos.logback.core.Context.class));
    var classPath = new ArrayList<String>();
    classPath.add(ChildJvm.testClassesDir());
    if (layout.equals("plain")) classPath.addAll(loggingJars);

    var command = new ArrayList<String>();
    command.add(ChildJvm.javaHome().resolve("bin").resolve("java").toString());
    command.add("--enable-preview");
    command.add("-javaagent:" + agentJar);
    if (provider.equals("logback")) {
      command.add("-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider");
    }
    command.add("-cp");
    command.add(String.join(File.pathSeparator, classPath));
    command.add(IsolationTestSubject.class.getName());
    command.add(jfr.toString());
    command.add(facts.toString());
    command.add(layout);
    command.add(jarOf(net.bytebuddy.ByteBuddy.class));
    if (layout.equals("child")) command.addAll(loggingJars);

    Process process =
        new ProcessBuilder(command).directory(runDir.toFile()).redirectErrorStream(true).start();
    try {
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor(ChildJvm.TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      var properties = new Properties();
      if (Files.exists(facts)) {
        try (Reader in = Files.newBufferedReader(facts)) {
          properties.load(in);
        }
      }
      return new Isolation(
          process.exitValue(), output, properties, jfr, runDir.resolve("isolation.html"));
    } finally {
      process.destroyForcibly();
    }
  }

  private static String jarOf(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }

  // --- criterion 8 ---

  @Test
  void renamedJarPrintsOneInfoLineThenTheUsualTwo(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.agentLines())
        .containsExactly(
            appendedLine(jar), RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(appendedLine(jar))
        .contains(jar.toRealPath().toString(), "bootstrap class path", originalName);
    assertThat(run.stderr().lines().filter(l -> l.contains(CDS_WARNING))).hasSizeLessThan(2);
  }

  @Test
  void renamedJarPrintsAnArgumentWarningOnce(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(jar, "record=" + dir.resolve("t.jfr") + ",min.scopes=abc")),
            "scopes",
            "q");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.warnings())
        .containsExactly("[scope-tracer] WARN ignoring malformed min.scopes value: abc");
    assertThat(run.agentLines().stream().filter(l -> l.contains(APPENDED))).hasSize(1);
    assertThat(run.agentLines()).doesNotHaveDuplicates();
  }

  // --- criterion 9 ---

  @Test
  void originalNameInAnotherDirectoryPrintsNothingNew(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "elsewhere/" + originalName);
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(run.agentLines())
        .containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(run.stderr()).doesNotContain(CDS_WARNING);
  }

  // --- criterion 10 ---

  @Test
  void jarAlreadyOnTheBootstrapPathIsNotAppendedAgain(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of("-Xbootclasspath/a:" + jar, javaagent(jar, "record=" + jfr)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(dir.resolve("t.html")).exists();
    assertThat(run.agentLines()).noneMatch(l -> l.contains(APPENDED)).hasSize(2);
    assertThat(run.stderr()).doesNotContain(CDS_WARNING);
  }

  // --- issue #128: a scope-tracer jar on -cp under a renamed -javaagent jar ---

  /** What else is on {@code -cp}, ahead of the test classes. */
  enum OnClassPath {
    /** The thin {@code scope-tracer-agent-<v>.jar}: the agent's classes, no ByteBuddy. */
    THIN,
    /** The thin jar, then the ByteBuddy jar the test JVM loaded {@code ByteBuddy} from. */
    THIN_AND_BYTEBUDDY,
    /** Another copy of the fat-jar, in another directory under another file name. */
    ANOTHER_FAT_JAR
  }

  /**
   * Copies the jars {@code what} names into {@code dir/cp/} and returns them in {@code -cp} order.
   */
  private static List<Path> classPathJars(OnClassPath what, Path dir) throws Exception {
    Path thin = fatJar.resolveSibling(originalName.replace("-agent.jar", ".jar"));
    assertThat(thin).as("the thin jar beside the fat-jar").exists();
    Path cp = Files.createDirectories(dir.resolve("cp"));
    return switch (what) {
      case THIN -> List.of(Files.copy(thin, cp.resolve(thin.getFileName())));
      case THIN_AND_BYTEBUDDY ->
          List.of(
              Files.copy(thin, cp.resolve(thin.getFileName())),
              Files.copy(Path.of(jarOf(net.bytebuddy.ByteBuddy.class)), cp.resolve("bb.jar")));
      case ANOTHER_FAT_JAR -> List.of(copyAs(dir, "cp/other-agent.jar"));
    };
  }

  private static List<String> strings(List<Path> paths) {
    return paths.stream().map(Path::toString).toList();
  }

  /**
   * The {@code ignoring} line for the jar the running class came from and the agent jar. Both by
   * real path: the JVM resolves a {@code -cp} jar's path, as it does the {@code -javaagent} one,
   * before it becomes the code source.
   */
  private static String ignoringLine(Path classPathJar, Path agentJar) throws Exception {
    return ChildJvm.AGENT_PREFIX
        + " ignoring "
        + classPathJar.toRealPath()
        + " on the class path: the -javaagent jar is "
        + agentJar.toRealPath();
  }

  /**
   * {@code -javaagent:agent.jar}, absolute, with {@code what} on {@code -cp}: exit 0, the six
   * events of the {@code mixed} scope, and the agent lines name the {@code -javaagent} jar.
   */
  @ParameterizedTest(name = "{0}")
  @EnumSource(OnClassPath.class)
  void renamedJarIsTheOneThatTracesWithAScopeTracerJarOnTheClassPath(
      OnClassPath what, @TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    List<Path> cp = classPathJars(what, dir);
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), strings(cp), "mixed");

    assertTracedByTheJavaagentJar(run, cp.get(0), jar, jfr, dir);
  }

  /** The same through a symbolic link to the jar: the lines name the real path. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(OnClassPath.class)
  void symbolicLinkToTheRenamedJarIsTheOneThatTraces(OnClassPath what, @TempDir Path dir)
      throws Exception {
    Path jar = copyAs(dir, "real/agent.jar");
    Path link =
        Files.createSymbolicLink(
            Files.createDirectories(dir.resolve("links")).resolve("linked.jar"), jar);
    List<Path> cp = classPathJars(what, dir);
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(link, "record=" + jfr)), strings(cp), "mixed");

    assertTracedByTheJavaagentJar(run, cp.get(0), jar, jfr, dir);
    assertThat(run.agentLines()).noneMatch(l -> l.contains("linked.jar"));
  }

  /** The same with {@code -javaagent:agent.jar} relative to the working directory. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(OnClassPath.class)
  void relativeRenamedJarIsTheOneThatTraces(OnClassPath what, @TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    List<Path> cp = classPathJars(what, dir);
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(dir, List.of("-javaagent:agent.jar=record=" + jfr), strings(cp), "mixed");

    assertTracedByTheJavaagentJar(run, cp.get(0), jar, jfr, dir);
  }

  /** {@code JAVA_TOOL_OPTIONS=-javaagent:...} with the thin jar on {@code -cp}. */
  @Test
  void javaToolOptionsRenamedJarIsTheOneThatTraces(@TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "agent.jar");
    List<Path> cp = classPathJars(OnClassPath.THIN, dir);
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            Map.of("JAVA_TOOL_OPTIONS", javaagent(jar, "record=" + jfr)),
            List.of(),
            strings(cp),
            "mixed");

    assertThat(run.stderr()).contains("Picked up JAVA_TOOL_OPTIONS");
    assertTracedByTheJavaagentJar(run, cp.get(0), jar, jfr, dir);
  }

  private static void assertTracedByTheJavaagentJar(
      ChildJvm.Result run, Path classPathJar, Path agentJar, Path jfr, Path dir) throws Exception {
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).as(run.stderr()).isEqualTo(ExitSubject.DONE + System.lineSeparator());
    assertMixedScopeFullyTraced(jfr);
    assertThat(run.agentLines())
        .as(run.stderr())
        .containsExactly(
            ignoringLine(classPathJar, agentJar),
            appendedLine(agentJar),
            RECORDING_TO + jfr,
            ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(run.stderr()).doesNotContain("FATAL").doesNotContain("NoClassDefFoundError");
  }

  /** Under the original file name nothing changes: no {@code added}, no {@code ignoring} line. */
  @ParameterizedTest(name = "{0}")
  @EnumSource(OnClassPath.class)
  void originalNameWithAScopeTracerJarOnTheClassPathPrintsNothingNew(
      OnClassPath what, @TempDir Path dir) throws Exception {
    Path jar = copyAs(dir, "orig/" + originalName);
    List<Path> cp = classPathJars(what, dir);
    Path jfr = dir.resolve("t.jfr");
    var run = ChildJvm.run(dir, List.of(javaagent(jar, "record=" + jfr)), strings(cp), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(run.agentLines())
        .containsExactly(RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
    assertThat(run.stderr()).doesNotContain(CDS_WARNING);
  }

  /**
   * {@code -cp a.jar:agent.jar:b.jar}, {@code agent.jar} also the {@code -javaagent} jar: the last
   * copy is {@code b.jar}, a class-path entry, so the agent cannot tell which jar is the agent's.
   * It refuses with one warning naming both fixes, records nothing, and the program is unchanged.
   */
  @Test
  void javaagentJarAlsoOnTheClassPathBetweenTwoCopiesRefuses(@TempDir Path dir) throws Exception {
    Path a = copyAs(dir, "a.jar");
    Path agent = copyAs(dir, "agent.jar");
    Path b = copyAs(dir, "b.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir,
            List.of(javaagent(agent, "record=" + jfr)),
            strings(List.of(a, agent, b)),
            "mixed");
    var without = ChildJvm.run(dir, List.of(), strings(List.of(a, agent, b)), "mixed");

    assertSameProgram(run, without);
    assertThat(run.agentLines()).as(run.stderr()).hasSize(1);
    assertThat(run.warnings())
        .singleElement()
        .asString()
        .startsWith(ChildJvm.AGENT_PREFIX + " WARN not tracing: ")
        .contains("the -javaagent jar cannot be told apart from the class path")
        .contains("rename the jar to " + originalName)
        .contains("or add -Xbootclasspath/a:");
    assertThat(jfr).doesNotExist();
    assertThat(dir.resolve("t.html")).doesNotExist();
  }

  /**
   * The {@code -javaagent} jar on {@code -cp} with the thin jar, in either order: the agent
   * refuses, and the rename hint is the fat-jar's exact file name in both.
   */
  @ParameterizedTest(name = "javaagent jar first: {0}")
  @ValueSource(booleans = {true, false})
  void javaagentJarOnTheClassPathWithTheThinJarRefusesNamingTheExactFileName(
      boolean agentFirst, @TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = classPathJars(OnClassPath.THIN, dir).get(0);
    Path jfr = dir.resolve("t.jfr");
    List<Path> cp = agentFirst ? List.of(agent, thin) : List.of(thin, agent);
    var run = ChildJvm.run(dir, List.of(javaagent(agent, "record=" + jfr)), strings(cp), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).isEqualTo(ExitSubject.DONE + System.lineSeparator());
    assertThat(run.agentLines()).as(run.stderr()).hasSize(1);
    assertThat(run.warnings())
        .singleElement()
        .asString()
        .contains("the -javaagent jar cannot be told apart from the class path")
        .contains("rename the jar to " + originalName + ", or add -Xbootclasspath/a:");
    assertThat(jfr).doesNotExist();
  }

  // --- issue #158: a scope-tracer jar reached through a manifest Class-Path ---

  /** Writes an empty jar at {@code dir/relative} whose manifest has {@code Class-Path: value}. */
  private static Path classPathJar(Path dir, String relative, String value) throws Exception {
    Path jar = dir.resolve(relative).toAbsolutePath();
    Files.createDirectories(jar.getParent());
    var manifest = new java.util.jar.Manifest();
    manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, value);
    try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
      out.flush();
    }
    return jar;
  }

  /** Copies the thin jar beside the fat-jar to {@code dir/relative}. */
  private static Path thinAs(Path dir, String relative) throws Exception {
    Path thin = fatJar.resolveSibling(originalName.replace("-agent.jar", ".jar"));
    assertThat(thin).as("the thin jar beside the fat-jar").exists();
    Path target = dir.resolve(relative).toAbsolutePath();
    Files.createDirectories(target.getParent());
    return Files.copy(thin, target);
  }

  /** The refusal for a last copy that {@code listedBy}'s manifest {@code Class-Path} lists. */
  private static String listedRefusal(Path running, Path last, Path listedBy) throws Exception {
    return ChildJvm.AGENT_PREFIX
        + " WARN not tracing: the agent jar is not on the bootstrap class path, and the"
        + " -javaagent jar cannot be told apart from the class path, which holds the agent at "
        + running.toRealPath()
        + " and at "
        + last.toRealPath()
        + " (listed by the Class-Path of "
        + listedBy.toRealPath()
        + "); rename the jar to "
        + originalName
        + ", or add -Xbootclasspath/a:<path to the jar>";
  }

  /**
   * A refusal: exit 0, the program's own output, exactly {@code warning} as the only agent line,
   * nothing recorded, and no {@code ignoring}, {@code added} or {@code NoClassDefFoundError}.
   */
  private static void assertRefused(ChildJvm.Result run, String warning, Path jfr) {
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stdout()).as(run.stderr()).isEqualTo(ExitSubject.DONE + System.lineSeparator());
    assertThat(run.agentLines()).as(run.stderr()).containsExactly(warning);
    assertThat(run.stderr())
        .doesNotContain(" ignoring ")
        .doesNotContain(APPENDED)
        .doesNotContain("NoClassDefFoundError")
        .doesNotContain(CDS_WARNING);
    assertThat(jfr).doesNotExist();
  }

  /** {@code -cp app.jar} with {@code Class-Path: thin.jar}: traces with agent.jar. Unchanged. */
  @Test
  void manifestListingOnlyTheThinJarTracesWithTheJavaagentJar(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = thinAs(dir, "cp/thin.jar");
    Path app = classPathJar(dir, "cp/app.jar", "thin.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertTracedByTheJavaagentJar(run, thin, agent, jfr, dir);
    assertThat(run.warnings()).isEmpty();
  }

  /** {@code Class-Path: agent.jar thin.jar}, or {@code agent.jar fat2.jar}: refuses. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"thin", "fat2"})
  void manifestListingTheJavaagentJarThenAnotherScopeTracerJarRefuses(
      String other, @TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path second = other.equals("thin") ? thinAs(dir, "thin.jar") : copyAs(dir, "fat2.jar");
    Path app = classPathJar(dir, "app.jar", "agent.jar " + second.getFileName());
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertRefused(run, listedRefusal(agent, second, app), jfr);
  }

  /** {@code -cp l.jar}, {@code l} lists {@code m}, {@code m} lists both: names {@code m.jar}. */
  @Test
  void chainOfTwoManifestsListingBothRefuses(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = thinAs(dir, "thin.jar");
    Path l = classPathJar(dir, "l.jar", "m.jar");
    Path m = classPathJar(dir, "m.jar", "agent.jar thin.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(l)), "mixed");

    assertRefused(run, listedRefusal(agent, thin, m), jfr);
  }

  /** {@code Class-Path: agent.jar} alone: traces, no {@code ignoring} line. Unchanged. */
  @Test
  void manifestListingOnlyTheJavaagentJarTraces(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path app = classPathJar(dir, "app.jar", "agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertMixedScopeFullyTraced(jfr);
    assertThat(run.agentLines())
        .as(run.stderr())
        .containsExactly(
            appendedLine(agent), RECORDING_TO + jfr, ChildJvm.WRITTEN + dir.resolve("t.html"));
  }

  /**
   * {@code Class-Path: thin.jar agent.jar}: refuses, as {@code -cp thin.jar:agent.jar} does. It
   * traced by luck before #158, and cannot be told apart from {@code agent.jar thin.jar}.
   */
  @Test
  void manifestListingTheThinJarThenTheJavaagentJarRefuses(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = thinAs(dir, "thin.jar");
    Path app = classPathJar(dir, "app.jar", "thin.jar agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertRefused(run, listedRefusal(thin, agent, app), jfr);
  }

  /**
   * {@code -cp "lib/*"} and {@code -javaagent:x/agent.jar}, relative to the working directory: the
   * one jar in {@code lib/} lists {@code ../x/agent.jar ../x/thin.jar} and the agent refuses; with
   * only {@code ../x/thin.jar} listed it traces with the {@code -javaagent} jar.
   */
  @ParameterizedTest(name = "javaagent jar listed: {0}")
  @ValueSource(booleans = {true, false})
  void wildcardJarListingScopeTracerJars(boolean agentListed, @TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "x/agent.jar");
    Path thin = thinAs(dir, "x/thin.jar");
    Path lib =
        classPathJar(
            dir, "lib/a.jar", agentListed ? "../x/agent.jar ../x/thin.jar" : "../x/thin.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of("-javaagent:x/agent.jar=record=" + jfr), List.of("lib/*"), "mixed");

    if (agentListed) {
      assertRefused(run, listedRefusal(agent, thin, lib), jfr);
    } else {
      assertTracedByTheJavaagentJar(run, thin, agent, jfr, dir);
    }
  }

  // --- issue #158, QA: entries java.net.URI rejects or reads differently ---

  /**
   * {@code Class-Path: agent.jar file:thin.jar}, or {@code file:fat2.jar}: the JVM follows both.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"thin", "fat2"})
  void fileSchemeRelativeEntryAfterTheJavaagentJarRefuses(String other, @TempDir Path dir)
      throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path second = other.equals("thin") ? thinAs(dir, "thin.jar") : copyAs(dir, "fat2.jar");
    Path app = classPathJar(dir, "app.jar", "agent.jar file:" + second.getFileName());
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertRefused(run, listedRefusal(agent, second, app), jfr);
  }

  /** {@code -cp l.jar}, {@code l} lists {@code odd/k[1].jar}, which lists both copies. */
  @Test
  void chainThroughAJarNameUriRejectsRefuses(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path fat2 = copyAs(dir, "fat2.jar");
    Path l = classPathJar(dir, "l.jar", "odd/k[1].jar");
    Path k = classPathJar(dir, "odd/k[1].jar", "../agent.jar ../fat2.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(l)), "mixed");

    assertRefused(run, listedRefusal(agent, fat2, k), jfr);
  }

  /**
   * #128, found by QA on #158: {@code Class-Path: odd/a^b.jar agent.jar}, {@code a^b.jar} the thin
   * jar. The running copy's code source holds a {@code ^}; the refusal names it and the fat-jar's
   * exact file name, with no {@code URISyntaxException}.
   */
  @Test
  void runningCopyWithACharacterUriRejectsRefusesNamingTheFileName(@TempDir Path dir)
      throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = thinAs(dir, "odd/a^b.jar");
    Path app = classPathJar(dir, "app.jar", "odd/a^b.jar agent.jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertRefused(run, listedRefusal(thin, agent, app), jfr);
    assertThat(run.stderr()).doesNotContain("URISyntaxException");
  }

  /** {@code Class-Path: odd/k[1].jar}, the thin jar there, only: traces with the agent jar. */
  @Test
  void thinJarUnderANameUriRejectsTracesWithTheJavaagentJar(@TempDir Path dir) throws Exception {
    Path agent = copyAs(dir, "agent.jar");
    Path thin = thinAs(dir, "odd/k[1].jar");
    Path app = classPathJar(dir, "app.jar", "odd/k[1].jar");
    Path jfr = dir.resolve("t.jfr");
    var run =
        ChildJvm.run(
            dir, List.of(javaagent(agent, "record=" + jfr)), strings(List.of(app)), "mixed");

    assertTracedByTheJavaagentJar(run, thin, agent, jfr, dir);
  }

  // --- helpers ---

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
        .filter(l -> !l.startsWith(ChildJvm.AGENT_PREFIX) && !l.contains(CDS_WARNING))
        .toList();
  }
}
