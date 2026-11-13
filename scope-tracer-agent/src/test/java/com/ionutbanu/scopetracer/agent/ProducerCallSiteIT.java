package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ionutbanu.scopetracer.analyzer.JfrParser;
import com.ionutbanu.scopetracer.analyzer.TraceModelJson;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import com.ionutbanu.scopetracer.core.TracedScope;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The agent and {@code TracedScope} stamp the same call site on every {@code TaskForked} (#86,
 * #100; spec §1, "The agent and TracedScope emit the same events").
 *
 * <p>Runs {@link ProducerCallSiteSubject} twice, each in its own child JVM recording to its own
 * {@code .jfr}: once with {@code -javaagent} on plain {@code StructuredTaskScope}s, once with
 * {@code TracedScope} and no agent. Forks are matched by {@code taskName}, which is the same in
 * both (the label is unchanged by this work), and {@code callSiteClassName}, {@code
 * callSiteMethodName} and {@code callSiteLine} must be equal. Some forks are also checked against
 * fixed values, so the test fails if both producers drift the same way.
 */
class ProducerCallSiteIT {

  private static final String SUBJECT = ProducerCallSiteSubject.class.getName();
  private static final String FORKED = "com.ionutbanu.scopetracer.TaskForked";
  private static final String PREFIX = "com.ionutbanu.scopetracer.";

  /** Forks both producers make: every fork of the subject except the explicitly named one. */
  private static final int COMMON_FORKS = 15;

  private static Recorded agent;
  private static Recorded traced;
  private static int explicitLine;
  private static TraceModel agentModel;

  /** One fork as recorded: the four fork-time fields and how the task ended. */
  record Fork(
      String taskName, String className, String methodName, int line, String scope, String end) {}

  /** The forks of one recording, by {@code taskName}. */
  record Recorded(Map<String, Fork> byName, int forkCount) {}

  @BeforeAll
  static void runBothProducers() throws Exception {
    String agentJar = System.getProperty("agentJar", "");
    assumeTrue(
        !agentJar.isBlank() && Files.exists(Path.of(agentJar)),
        "Skipping agent IT: run 'mvn verify' to build the fat-jar and execute these tests.");
    String testClassesDir = System.getProperty("testClassesDir", "");
    assumeTrue(
        !testClassesDir.isBlank(), "Skipping agent IT: testClassesDir system property not set.");

    // TracedScope comes from the agent fat-jar on the bootstrap path in the agent run, and from
    // core's own classes or jar - whichever this JVM loaded it from - in the TracedScope run.
    String core =
        Path.of(TracedScope.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString();

    Path agentJfr = Files.createTempFile("producer-call-site-agent-", ".jfr");
    Path tracedJfr = Files.createTempFile("producer-call-site-traced-", ".jfr");
    try {
      run(List.of("-javaagent:" + agentJar, "-cp", testClassesDir), agentJfr, "agent");
      String out =
          run(List.of("-cp", testClassesDir + File.pathSeparator + core), tracedJfr, "traced");
      var m = Pattern.compile("explicitLine=(\\d+)").matcher(out);
      assertThat(m.find()).as("subject output: %s", out).isTrue();
      explicitLine = Integer.parseInt(m.group(1));
      agent = read(agentJfr);
      traced = read(tracedJfr);
      agentModel = JfrParser.parse(agentJfr);
    } finally {
      Files.deleteIfExists(agentJfr);
      Files.deleteIfExists(tracedJfr);
    }
  }

  @Test
  void bothProducersRecordEveryForkOnceWithAUniqueName() {
    assertThat(agent.forkCount()).isEqualTo(COMMON_FORKS);
    assertThat(agent.byName()).hasSize(COMMON_FORKS);
    assertThat(traced.forkCount()).isEqualTo(COMMON_FORKS + 1);
    assertThat(traced.byName()).hasSize(COMMON_FORKS + 1);
  }

  @Test
  void agentAndTracedScopeStampTheSameCallSiteOnEveryFork() {
    var tracedCommon = new LinkedHashMap<>(traced.byName());
    tracedCommon.remove(ProducerCallSiteSubject.EXPLICIT);
    assertThat(agent.byName().keySet()).isEqualTo(tracedCommon.keySet());
    for (var name : agent.byName().keySet()) {
      var a = agent.byName().get(name);
      var t = tracedCommon.get(name);
      assertThat(a.className()).as("callSiteClassName of %s", name).isEqualTo(t.className());
      assertThat(a.methodName()).as("callSiteMethodName of %s", name).isEqualTo(t.methodName());
      assertThat(a.line()).as("callSiteLine of %s", name).isEqualTo(t.line());
    }
  }

  @Test
  void theAgentStampsTheCallSiteOnEveryFork() {
    assertThat(agent.byName().values()).allSatisfy(f -> assertThat(f.className()).isNotNull());
  }

  @Test
  void lambdaAndMethodReferenceForksRecordTheCallingFrame() {
    for (var recording : List.of(agent, traced)) {
      var frameForks =
          recording.byName().values().stream().filter(f -> f.methodName() != null).toList();
      assertThat(frameForks).isNotEmpty();
      assertThat(frameForks)
          .allSatisfy(
              f -> {
                assertThat(f.className()).startsWith(SUBJECT);
                assertThat(f.line()).isPositive();
                assertThat(f.taskName())
                    .isIn(
                        "ProducerCallSiteSubject#" + f.methodName() + ":" + f.line(),
                        ProducerCallSiteSubject.EXPLICIT);
              });
      var methods = frameForks.stream().map(Fork::methodName).distinct().toList();
      assertThat(methods)
          .contains("forkEveryKind", "failFast", "anySuccessful", "nested", "middle", "inner");
    }
  }

  @Test
  void namedNestedCallableRecordsItsBinaryNameWithNoMethodOrLine() {
    for (var recording : List.of(agent, traced)) {
      var named = recording.byName().get("Named");
      assertThat(named).isNotNull();
      assertThat(named.className()).isEqualTo(SUBJECT + "$Named");
      assertThat(named.methodName()).isNull();
      assertThat(named.line()).isZero();
    }
  }

  @Test
  void localCallableRecordsItsBinaryName() {
    for (var recording : List.of(agent, traced)) {
      var local = recording.byName().get("Local");
      assertThat(local).isNotNull();
      assertThat(local.className()).isEqualTo(SUBJECT + "$1Local");
      assertThat(local.methodName()).isNull();
      assertThat(local.line()).isZero();
    }
  }

  @Test
  void forksInNestedAndAnonymousClassesRecordThoseClasses() {
    for (var recording : List.of(agent, traced)) {
      var classes = recording.byName().values().stream().map(Fork::className).toList();
      assertThat(classes).contains(SUBJECT + "$Inner", SUBJECT + "$1", SUBJECT);
      var inner =
          recording.byName().values().stream()
              .filter(f -> f.className().equals(SUBJECT + "$Inner"))
              .findFirst()
              .orElseThrow();
      assertThat(inner.methodName()).isEqualTo("fork");
      // The label keeps the outer simple name, as it always has.
      assertThat(inner.taskName()).isEqualTo("ProducerCallSiteSubject#fork:" + inner.line());
      var anonymous =
          recording.byName().values().stream()
              .filter(f -> f.className().equals(SUBJECT + "$1"))
              .findFirst()
              .orElseThrow();
      assertThat(anonymous.methodName()).isEqualTo("call");
    }
  }

  @Test
  void explicitlyNamedTracedScopeForkStillRecordsItsCallSite() {
    var explicit = traced.byName().get(ProducerCallSiteSubject.EXPLICIT);
    assertThat(explicit).isNotNull();
    assertThat(explicit.className()).isEqualTo(SUBJECT);
    assertThat(explicit.methodName()).isEqualTo("forkEveryKind");
    assertThat(explicit.line()).isEqualTo(explicitLine).isPositive();
  }

  /**
   * The outcomes are observed in both recordings, not assumed: the fail-fast scope has one failed
   * and one cancelled task and the {@code anySuccessfulOrThrow} scope one success and two
   * cancellations, and those forks still carry the call site (checked against each other above).
   */
  @Test
  void failedCancelledAndLosingTasksEndAsTheScenarioSays() {
    for (var recording : List.of(agent, traced)) {
      var failFast = forksOfScope(recording, "failFast");
      assertThat(failFast).extracting(Fork::end).containsExactlyInAnyOrder("Failed", "Cancelled");
      var any = forksOfScope(recording, "anySuccessful");
      assertThat(any)
          .extracting(Fork::end)
          .containsExactlyInAnyOrder("Succeeded", "Cancelled", "Cancelled");
      assertThat(recording.byName().values())
          .filteredOn(f -> !f.scope().equals("failFast") && !f.scope().equals("anySuccessful"))
          .allSatisfy(f -> assertThat(f.end()).isEqualTo("Succeeded"));
    }
  }

  /** The analyzer's JSON for an agent recording carries the call site of every task. */
  @Test
  void analyzerJsonOfAnAgentRecordingHasACallSiteForEveryTask() {
    var tasks = agentModel.scopes().stream().flatMap(s -> s.tasks().stream()).toList();
    assertThat(tasks).hasSize(COMMON_FORKS);
    assertThat(tasks).allSatisfy(t -> assertThat(t.callSite()).isNotNull());
    var json = TraceModelJson.toJson(agentModel);
    assertThat(json).doesNotContain("\"callSite\":null");
    assertThat(json)
        .contains("\"callSite\":{\"className\":\"" + SUBJECT + "$Named\",\"methodName\":null");
  }

  private static List<Fork> forksOfScope(Recorded recording, String method) {
    return recording.byName().values().stream().filter(f -> f.scope().equals(method)).toList();
  }

  /**
   * Reads the forks of a recording. Each fork's {@code scope} is the method of its scope's first
   * frame-tier fork, which names the scenario whichever producer made it; its {@code end} is the
   * completion event's type.
   */
  private static Recorded read(Path jfr) throws IOException {
    var forks = new ArrayList<RecordedEvent>();
    var ends = new HashMap<String, String>();
    for (var e : RecordingFile.readAllEvents(jfr)) {
      String type = e.getEventType().getName();
      if (!type.startsWith(PREFIX)) continue;
      String key = e.getLong("scopeId") + "/" + e.getLong("taskId");
      switch (type.substring(PREFIX.length())) {
        case "TaskForked" -> forks.add(e);
        case "TaskSucceeded" -> ends.put(key, "Succeeded");
        case "TaskFailed" -> ends.put(key, "Failed");
        case "TaskCancelled" -> ends.put(key, "Cancelled");
        default -> {}
      }
    }
    var scopeMethod = new HashMap<Long, String>();
    for (var e : forks) {
      String method = e.getString("callSiteMethodName");
      if (method != null) scopeMethod.putIfAbsent(e.getLong("scopeId"), method);
    }
    var byName = new LinkedHashMap<String, Fork>();
    for (var e : forks) {
      assertThat(e.getEventType().getName()).isEqualTo(FORKED);
      String key = e.getLong("scopeId") + "/" + e.getLong("taskId");
      var fork =
          new Fork(
              e.getString("taskName"),
              e.getString("callSiteClassName"),
              e.getString("callSiteMethodName"),
              e.getInt("callSiteLine"),
              scopeMethod.getOrDefault(e.getLong("scopeId"), "?"),
              ends.get(key));
      byName.put(fork.taskName(), fork);
    }
    return new Recorded(byName, forks.size());
  }

  /** Runs the subject in a child JVM with {@code jvmArgs}; returns its output. */
  private static String run(List<String> jvmArgs, Path jfrOut, String mode)
      throws IOException, InterruptedException {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("--enable-preview");
    command.addAll(jvmArgs);
    command.add(SUBJECT);
    command.add(jfrOut.toString());
    command.add(mode);
    var proc = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(proc.getInputStream().readAllBytes());
    int exitCode = proc.waitFor();
    if (exitCode != 0) {
      fail("ProducerCallSiteSubject (" + mode + ") exited with " + exitCode + ":\n" + output);
    }
    return output;
  }
}
