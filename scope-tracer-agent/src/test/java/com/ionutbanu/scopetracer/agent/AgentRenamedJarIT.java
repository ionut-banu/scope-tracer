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
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
        assertThat(fromRenamed.output()).doesNotContain("cut short").doesNotContain("JfrParser");
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
