package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent fat-jar sits on the bootstrap class path, which every class loader asks first. These
 * tests show that what it puts there does not take the place of the application's own SLF4J or
 * ByteBuddy, and that the agent adds nothing to the application's output.
 *
 * <p>Each layout of {@link IsolationTestSubject} runs once with the agent and once without; the
 * tests compare the two runs. The logging and ByteBuddy jars are the ones already on this test's
 * class path, found through their {@code CodeSource}.
 *
 * <p>Requires the agent fat-jar ({@code mvn verify}); skipped when the {@code agentJar} property is
 * absent.
 */
class AgentIsolationIT {

  private static final String SHADED = "com.ionutbanu.scopetracer.shaded.";
  private static final String OWN_PACKAGE_PATH = "com/ionutbanu/scopetracer/";

  @TempDir static Path tempDir;

  private static Path agentJar;
  private static Run childWithAgent;
  private static Run childWithoutAgent;
  private static Run plainWithAgent;
  private static Run plainWithoutAgent;
  private static Run providerPropertyWithAgent;
  private static Run providerPropertyWithoutAgent;
  private static String experimentalProperty;

  @BeforeAll
  static void runSubjectInEveryLayout() throws Exception {
    String agentJarProperty = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJarProperty.isBlank() && Files.exists(Path.of(agentJarProperty)),
        "Skipping agent IT: run 'mvn verify' to build the fat-jar.");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(
        !testClassesDir.isBlank(), "Skipping agent IT: testClassesDir system property not set.");
    agentJar = Path.of(agentJarProperty);

    experimentalProperty = experimentalPropertyReadByAgentByteBuddy();

    List<String> loggingJars =
        List.of(
            jarOf(org.slf4j.LoggerFactory.class),
            jarOf(ch.qos.logback.classic.LoggerContext.class),
            jarOf(ch.qos.logback.core.Context.class));
    String byteBuddyJar = jarOf(net.bytebuddy.ByteBuddy.class);

    // child: only the test classes on the class path; logging jars loaded by a child loader.
    var child = new Layout("child", List.of(testClassesDir), loggingJars, List.of());
    // plain: SLF4J 2.x and Logback on the class path.
    var plainClassPath = new ArrayList<String>();
    plainClassPath.add(testClassesDir);
    plainClassPath.addAll(loggingJars);
    var plain = new Layout("plain", plainClassPath, List.of(), List.of());
    // plain, with the application naming its provider through SLF4J's own system property.
    var providerProperty =
        new Layout(
            "plain",
            plainClassPath,
            List.of(),
            List.of("-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"));

    childWithAgent = run("child-agent", child, byteBuddyJar, true);
    childWithoutAgent = run("child-none", child, byteBuddyJar, false);
    plainWithAgent = run("plain-agent", plain, byteBuddyJar, true);
    plainWithoutAgent = run("plain-none", plain, byteBuddyJar, false);
    providerPropertyWithAgent = run("provider-agent", providerProperty, byteBuddyJar, true);
    providerPropertyWithoutAgent = run("provider-none", providerProperty, byteBuddyJar, false);
  }

  @Test
  void everyRunExitsNormally() {
    for (Run run :
        List.of(
            childWithAgent,
            childWithoutAgent,
            plainWithAgent,
            plainWithoutAgent,
            providerPropertyWithAgent,
            providerPropertyWithoutAgent)) {
      assertThat(run.exitCode()).as("%s exit code, output:%n%s", run.id(), run.output()).isZero();
    }
  }

  // --- what the fat-jar exposes on the bootstrap class path ---

  @Test
  void agentJarContainsNoSlf4jEntry() throws IOException {
    assertThat(jarEntries()).noneMatch(name -> name.startsWith("org/slf4j/"));
    assertThat(jarEntries()).noneMatch(name -> name.endsWith("module-info.class"));
  }

  @Test
  void agentJarExposesOnlyItsOwnPackages() throws IOException {
    // Strip a multi-release prefix: META-INF/versions/24/net/... exposes net/... as much as net/...
    var foreign = new TreeSet<String>();
    for (String name : jarEntries()) {
      String path = name.replaceFirst("^META-INF/versions/\\d+/", "");
      boolean javaRoot =
          path.startsWith("net/")
              || path.startsWith("org/")
              || path.startsWith("com/")
              || path.startsWith("ch/");
      if (javaRoot && path.indexOf('/') < path.length() - 1 && !isOwn(path)) {
        foreign.add(topLevelPackage(path));
      }
    }
    assertThat(foreign).as("top-level packages outside %s", OWN_PACKAGE_PATH).isEmpty();
  }

  @Test
  void agentJarRelocatesRatherThanDropsSlf4jAndByteBuddy() throws IOException {
    assertThat(jarEntries())
        .contains(
            "com/ionutbanu/scopetracer/shaded/org/slf4j/LoggerFactory.class",
            "com/ionutbanu/scopetracer/shaded/net/bytebuddy/ByteBuddy.class");
  }

  @Test
  void agentJarCarriesNoMavenMetadataOfRelocatedArtifacts() throws IOException {
    assertThat(jarEntries())
        .noneMatch(name -> name.startsWith("META-INF/maven/org.slf4j/"))
        .noneMatch(name -> name.startsWith("META-INF/maven/net.bytebuddy/"));
  }

  // --- the application's SLF4J, in a child class loader ---

  @Test
  void childLoaderLoggingPrintsTheLineUnderTheAgent() {
    assertThat(childWithoutAgent.logLinePrinted()).as("%s", childWithoutAgent.output()).isTrue();
    assertThat(childWithAgent.logLinePrinted()).as("%s", childWithAgent.output()).isTrue();
  }

  @Test
  void childLoaderLoggingUsesTheApplicationsOwnLoggerFactory() {
    assertThat(childWithoutAgent.fact("slf4j.loader")).isEqualTo("expected");
    assertThat(childWithAgent.fact("slf4j.loader")).isEqualTo("expected");
    assertThat(childWithAgent.fact("slf4j.factory"))
        .isEqualTo(childWithoutAgent.fact("slf4j.factory"))
        .isEqualTo("ch.qos.logback.classic.LoggerContext");
  }

  @Test
  void childLoaderOutputHasNoSlf4jLine() {
    assertThat(childWithAgent.slf4jLines()).as("%s", childWithAgent.output()).isEmpty();
  }

  // --- the application's SLF4J 2.x, on the plain class path ---

  @Test
  void plainClassPathLoggingPrintsTheLineUnderTheAgent() {
    assertThat(plainWithoutAgent.logLinePrinted()).as("%s", plainWithoutAgent.output()).isTrue();
    assertThat(plainWithAgent.logLinePrinted()).as("%s", plainWithAgent.output()).isTrue();
  }

  @Test
  void plainClassPathLoggingUsesTheApplicationClassLoadersLoggerFactory() {
    assertThat(plainWithoutAgent.fact("slf4j.loader")).isEqualTo("expected");
    assertThat(plainWithAgent.fact("slf4j.loader")).isEqualTo("expected");
    assertThat(plainWithAgent.fact("slf4j.factory"))
        .isEqualTo("ch.qos.logback.classic.LoggerContext");
  }

  @Test
  void plainClassPathOutputHasNoSlf4jLine() {
    assertThat(plainWithAgent.slf4jLines()).as("%s", plainWithAgent.output()).isEmpty();
  }

  /**
   * The relocated SLF4J must not read the application's {@code slf4j.provider}: on the bootstrap
   * path it cannot load the named class, and would print an error and fail auto-HTML.
   */
  @Test
  void applicationsSlf4jProviderPropertyIsLeftToTheApplication() {
    assertThat(providerPropertyWithAgent.logLinePrinted())
        .as("%s", providerPropertyWithAgent.output())
        .isTrue();
    assertThat(providerPropertyWithAgent.slf4jLines())
        .isEqualTo(providerPropertyWithoutAgent.slf4jLines());
    assertThat(providerPropertyWithAgent.html()).exists();
  }

  // --- auto-HTML and the analyzer's own log lines ---

  @Test
  void autoHtmlIsWrittenInEveryLayout() throws IOException {
    for (Run run : List.of(childWithAgent, plainWithAgent)) {
      assertThat(run.html()).as("%s", run.output()).exists();
      assertThat(Files.readString(run.html())).contains(IsolationTestSubject.TRUNCATED_SCOPE);
    }
    assertThat(childWithoutAgent.html()).doesNotExist();
  }

  @Test
  void analyzerLogLinesDoNotReachTheApplicationsOutput() throws Exception {
    // The recording is truncated, so JfrParser logged its warning while the agent parsed it.
    assertThat(scope(childWithAgent.model(), IsolationTestSubject.TRUNCATED_SCOPE))
        .hasValueSatisfying(s -> assertThat(s.closeTime()).isNull());
    for (Run run : List.of(childWithAgent, plainWithAgent)) {
      assertThat(run.output())
          .doesNotContain("cut short")
          .doesNotContain("JfrParser")
          .doesNotContain("SLF4J(");
    }
  }

  // --- ByteBuddy ---

  @Test
  void applicationGetsItsOwnByteBuddyAndIsStillTraced() throws Exception {
    assertThat(childWithAgent.fact("bytebuddy.loader")).isEqualTo("expected");
    assertThat(plainWithAgent.fact("bytebuddy.loader")).isEqualTo("expected");
    assertThat(Integer.parseInt(plainWithAgent.fact("bytebuddy.generatedBytes"))).isPositive();
    assertThat(scope(plainWithAgent.model(), IsolationTestSubject.TRUNCATED_SCOPE)).isPresent();
    assertThat(scope(childWithAgent.model(), IsolationTestSubject.UNNAMED_SCOPE)).isPresent();
  }

  /** {@code StackFilter} still skips the agent's own frames once ByteBuddy is relocated. */
  @Test
  void unnamedScopeIsStillNamedFromItsCallSite() throws Exception {
    assertThat(plainWithAgent.model().scopes())
        .extracting(ScopeRecord::name)
        .containsExactlyInAnyOrder(
            IsolationTestSubject.UNNAMED_SCOPE, IsolationTestSubject.TRUNCATED_SCOPE);
  }

  /**
   * The agent sets the experimental property under the name its relocated ByteBuddy reads, and
   * leaves the name the application's own ByteBuddy reads alone.
   */
  @Test
  void agentSetsTheExperimentalPropertyItsOwnByteBuddyReads() {
    assertThat(experimentalProperty).isEqualTo(SHADED + "net.bytebuddy.experimental");
    assertThat(plainWithAgent.fact("experimental")).isEqualTo("true");
    assertThat(plainWithAgent.fact("unrelocatedExperimental")).isEqualTo("null");
    assertThat(plainWithoutAgent.fact("experimental")).isEqualTo("null");
  }

  // --- helpers ---

  private record Layout(
      String name, List<String> classPath, List<String> childJars, List<String> jvmArgs) {}

  private record Run(
      String id, int exitCode, String output, Properties facts, Path jfr, Path html) {

    String fact(String key) {
      return facts.getProperty(key);
    }

    boolean logLinePrinted() {
      return output
          .lines()
          .anyMatch(
              line ->
                  line.contains(" INFO ")
                      && line.contains(IsolationTestSubject.LOGGER_NAME)
                      && line.endsWith(IsolationTestSubject.LOG_LINE));
    }

    List<String> slf4jLines() {
      return output.lines().filter(line -> line.contains("SLF4J(")).toList();
    }

    TraceModel model() throws Exception {
      return JfrParser.parse(jfr);
    }
  }

  private static Run run(String id, Layout layout, String byteBuddyJar, boolean withAgent)
      throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve(id));
    Path jfr = dir.resolve("isolation.jfr");
    Path facts = dir.resolve("facts.properties");

    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("--enable-preview");
    if (withAgent) {
      command.add("-javaagent:" + agentJar);
    }
    command.add("-D" + IsolationTestSubject.EXPERIMENTAL_PROPERTY_KEY + "=" + experimentalProperty);
    command.addAll(layout.jvmArgs());
    command.add("-cp");
    command.add(String.join(File.pathSeparator, layout.classPath()));
    command.add(IsolationTestSubject.class.getName());
    command.add(jfr.toString());
    command.add(facts.toString());
    command.add(layout.name());
    command.add(byteBuddyJar);
    command.addAll(layout.childJars());

    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();
    var properties = new Properties();
    if (Files.exists(facts)) {
      try (Reader in = Files.newBufferedReader(facts)) {
        properties.load(in);
      }
    }
    return new Run(id, exitCode, output, properties, jfr, dir.resolve("isolation.html"));
  }

  /** Reads ByteBuddy's own constant out of the fat-jar, without the agent attached. */
  private static String experimentalPropertyReadByAgentByteBuddy() throws Exception {
    try (var loader =
        new URLClassLoader(
            new URL[] {agentJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> reader =
          Class.forName(SHADED + "net.bytebuddy.utility.OpenedClassReader", false, loader);
      return (String) reader.getField("EXPERIMENTAL_PROPERTY").get(null);
    } catch (ClassNotFoundException e) {
      // ByteBuddy not relocated: the agent and the application share the unrelocated name.
      return "net.bytebuddy.experimental";
    }
  }

  private static List<String> jarEntries() throws IOException {
    try (var jar = new ZipFile(agentJar.toFile())) {
      return Collections.list(jar.entries()).stream().map(ZipEntry::getName).toList();
    }
  }

  private static boolean isOwn(String path) {
    return path.startsWith(OWN_PACKAGE_PATH) || OWN_PACKAGE_PATH.startsWith(path);
  }

  /** {@code org/slf4j/helpers/X.class} → {@code org/slf4j}. */
  private static String topLevelPackage(String path) {
    String[] parts = path.split("/");
    return parts.length > 2 ? parts[0] + "/" + parts[1] : parts[0];
  }

  private static Optional<ScopeRecord> scope(TraceModel model, String name) {
    return model.scopes().stream().filter(s -> name.equals(s.name())).findFirst();
  }

  private static String jarOf(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }
}
