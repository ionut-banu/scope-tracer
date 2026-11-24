package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The plugin's half of the JSON contract with the analyzer (#94, spec §3 "Plugin"). The golden
 * files are the analyzer's real {@code TraceModelJson} output for its fixture recordings; the
 * analyzer's {@code TraceModelJsonContractTest} checks them byte for byte in {@code mvn verify},
 * and this test parses the same files, found through the {@value #GOLDEN_DIR_PROPERTY} system
 * property that {@code model/build.gradle.kts} sets to {@code
 * scope-tracer-analyzer/src/test/resources/contract}. One copy only.
 *
 * <p>A key renamed by the analyzer parses silently as {@code null} or {@code 0} (Gson ignores keys
 * it does not know), so the values are asserted field by field against what the golden holds, and
 * every key path in a golden must be one the parser maps or one listed as ignored.
 */
class AnalyzerJsonContractTest {

  static final String GOLDEN_DIR_PROPERTY = "scopetracer.contractGoldens";

  /**
   * Every key path {@link TraceModelJsonParser} maps onto a plugin record. {@code []} marks an
   * array element.
   */
  private static final Set<String> MAPPED_PATHS =
      Set.of(
          "scopes",
          "scopes[].scopeId",
          "scopes[].name",
          "scopes[].ownerThreadName",
          "scopes[].ownerThreadId",
          "scopes[].openTime",
          "scopes[].closeTime",
          "scopes[].parent",
          "scopes[].parent.parentScopeId",
          "scopes[].parent.scopeName",
          "scopes[].parent.taskId",
          "scopes[].tasks",
          "scopes[].tasks[].taskId",
          "scopes[].tasks[].taskName",
          "scopes[].tasks[].threadName",
          "scopes[].tasks[].threadId",
          "scopes[].tasks[].forkTime",
          "scopes[].tasks[].completionTime",
          "scopes[].tasks[].outcome",
          "scopes[].tasks[].outcome.type",
          "scopes[].tasks[].outcome.exceptionType",
          "scopes[].tasks[].outcome.exceptionMessage",
          "scopes[].tasks[].outcome.stackTrace",
          "scopes[].tasks[].callSite",
          "scopes[].tasks[].callSite.className",
          "scopes[].tasks[].callSite.methodName",
          "scopes[].tasks[].callSite.line");

  /**
   * Key paths the plugin deliberately does not read, each with the reason. A new analyzer key goes
   * either into the parser and {@link #MAPPED_PATHS}, or here.
   */
  private static final Map<String, String> IGNORED_PATHS = Map.of();

  @Test
  void v010GoldenParsesToItsValues() throws IOException {
    var model = parse("v0.1.0-nested-failed-cancelled");

    var expected =
        new PluginTraceModel(
            List.of(
                new PluginScopeRecord(
                    1L,
                    "v010-outer",
                    "main",
                    3L,
                    Instant.parse("2026-11-13T06:22:08.061760541Z"),
                    Instant.parse("2026-11-13T06:22:08.066060291Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "inner-runner",
                            "Thread-0",
                            34L,
                            Instant.parse("2026-11-13T06:22:08.062903916Z"),
                            Instant.parse("2026-11-13T06:22:08.066020708Z"),
                            new PluginTaskOutcome.Success(),
                            null))),
                new PluginScopeRecord(
                    2L,
                    "v010-inner",
                    "Thread-0",
                    34L,
                    Instant.parse("2026-11-13T06:22:08.063809750Z"),
                    Instant.parse("2026-11-13T06:22:08.065998Z"),
                    new PluginParentRef(1L, "v010-outer", 1L),
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ok",
                            "Thread-1",
                            35L,
                            Instant.parse("2026-11-13T06:22:08.063922875Z"),
                            Instant.parse("2026-11-13T06:22:08.064990583Z"),
                            new PluginTaskOutcome.Success(),
                            null))),
                new PluginScopeRecord(
                    3L,
                    "v010-failing",
                    "main",
                    3L,
                    Instant.parse("2026-11-13T06:22:08.066170708Z"),
                    Instant.parse("2026-11-13T06:22:08.069614083Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "slow",
                            "Thread-2",
                            36L,
                            Instant.parse("2026-11-13T06:22:08.066334166Z"),
                            Instant.parse("2026-11-13T06:22:08.069573375Z"),
                            new PluginTaskOutcome.Cancelled(),
                            null),
                        new PluginTaskRecord(
                            2L,
                            "boom",
                            "Thread-3",
                            37L,
                            Instant.parse("2026-11-13T06:22:08.066485958Z"),
                            Instant.parse("2026-11-13T06:22:08.068230541Z"),
                            new PluginTaskOutcome.Failed(
                                "java.lang.IllegalStateException",
                                "v0.1.0 failure",
                                "java.lang.IllegalStateException: v0.1.0 failure\n"
                                    + "\tat Record010.lambda$main$3(Record010.java:40)\n"
                                    + "\tat com.ionutbanu.scopetracer.core.TracedScope"
                                    + ".lambda$fork$0(TracedScope.java:267)\n"
                                    + "\tat java.base/java.util.concurrent.StructuredTaskScopeImpl"
                                    + "$SubtaskImpl.run(StructuredTaskScopeImpl.java:350)\n"
                                    + "\tat java.base/java.lang.Thread.run(Thread.java:1516)\n"),
                            null)))));

    assertSameModel(model, expected);
  }

  @Test
  void v020GoldenParsesToItsValues() throws IOException {
    var model = parse("v0.2.0-nested-and-failed");

    var expected =
        new PluginTraceModel(
            List.of(
                new PluginScopeRecord(
                    1L,
                    "legacy-outer",
                    "main",
                    3L,
                    Instant.parse("2026-11-08T10:15:20.517371209Z"),
                    Instant.parse("2026-11-08T10:15:20.521800917Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "inner-runner",
                            "Thread-0",
                            34L,
                            Instant.parse("2026-11-08T10:15:20.518561625Z"),
                            Instant.parse("2026-11-08T10:15:20.521708792Z"),
                            new PluginTaskOutcome.Success(),
                            null))),
                new PluginScopeRecord(
                    2L,
                    "legacy-inner",
                    "Thread-0",
                    34L,
                    Instant.parse("2026-11-08T10:15:20.519401125Z"),
                    Instant.parse("2026-11-08T10:15:20.521685334Z"),
                    new PluginParentRef(1L, "legacy-outer", 1L),
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ok",
                            "Thread-1",
                            35L,
                            Instant.parse("2026-11-08T10:15:20.519618334Z"),
                            Instant.parse("2026-11-08T10:15:20.520695459Z"),
                            new PluginTaskOutcome.Success(),
                            null))),
                new PluginScopeRecord(
                    3L,
                    "legacy-failing",
                    "main",
                    3L,
                    Instant.parse("2026-11-08T10:15:20.521837542Z"),
                    Instant.parse("2026-11-08T10:15:20.524468375Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "boom",
                            "Thread-2",
                            36L,
                            Instant.parse("2026-11-08T10:15:20.522003250Z"),
                            Instant.parse("2026-11-08T10:15:20.524035250Z"),
                            new PluginTaskOutcome.Failed(
                                "java.lang.IllegalStateException",
                                "boom",
                                "java.lang.IllegalStateException: boom\n"
                                    + "\tat Gen020.lambda$main$2(Gen020.java:22)\n"
                                    + "\tat com.ionutbanu.scopetracer.core.TracedScope"
                                    + ".lambda$fork$0(TracedScope.java:267)\n"
                                    + "\tat java.base/java.util.concurrent.StructuredTaskScopeImpl"
                                    + "$SubtaskImpl.run(StructuredTaskScopeImpl.java:350)\n"
                                    + "\tat java.base/java.lang.Thread.run(Thread.java:1516)\n"),
                            null)))));

    assertSameModel(model, expected);
  }

  /**
   * The fixture made by tag v0.6.0 with the agent; the program is {@code
   * recordings/v0.6.0-contract.java.txt} in the analyzer's test resources, and the call-site lines
   * are that file's.
   */
  @Test
  void v060GoldenParsesToItsValues() throws IOException {
    var model = parse("v0.6.0-contract");

    String fixture = "com.acme.contract.ContractFixture";
    var expected =
        new PluginTraceModel(
            List.of(
                new PluginScopeRecord(
                    1L,
                    "contract-root",
                    "main",
                    3L,
                    Instant.parse("2026-11-24T08:56:02.867539625Z"),
                    Instant.parse("2026-11-24T08:56:02.881623625Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#forkProfile:12",
                            "",
                            38L,
                            Instant.parse("2026-11-24T08:56:02.872770125Z"),
                            Instant.parse("2026-11-24T08:56:02.877176875Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture + "$Forks", "forkProfile", 12)),
                        new PluginTaskRecord(
                            2L,
                            "Inventory",
                            "",
                            40L,
                            Instant.parse("2026-11-24T08:56:02.877020Z"),
                            Instant.parse("2026-11-24T08:56:02.877287584Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture + "$Inventory", null, null)),
                        new PluginTaskRecord(
                            3L,
                            "ContractFixture#main:29",
                            "",
                            42L,
                            Instant.parse("2026-11-24T08:56:02.877952792Z"),
                            Instant.parse("2026-11-24T08:56:02.881507875Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture, "main", 29)))),
                new PluginScopeRecord(
                    2L,
                    "contract-child",
                    "",
                    42L,
                    Instant.parse("2026-11-24T08:56:02.878468417Z"),
                    Instant.parse("2026-11-24T08:56:02.881496167Z"),
                    new PluginParentRef(1L, "contract-root", 3L),
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#main:32",
                            "",
                            43L,
                            Instant.parse("2026-11-24T08:56:02.878853042Z"),
                            Instant.parse("2026-11-24T08:56:02.881418667Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture, "main", 32)))),
                new PluginScopeRecord(
                    3L,
                    "contract-grandchild",
                    "",
                    43L,
                    Instant.parse("2026-11-24T08:56:02.879216667Z"),
                    Instant.parse("2026-11-24T08:56:02.881386084Z"),
                    new PluginParentRef(2L, "contract-child", 1L),
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#main:36",
                            "",
                            44L,
                            Instant.parse("2026-11-24T08:56:02.879471084Z"),
                            Instant.parse("2026-11-24T08:56:02.879547625Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture, "main", 36)))),
                new PluginScopeRecord(
                    4L,
                    "contract-failing",
                    "main",
                    3L,
                    Instant.parse("2026-11-24T08:56:02.882105792Z"),
                    Instant.parse("2026-11-24T08:56:02.887363167Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#main:51",
                            "",
                            45L,
                            Instant.parse("2026-11-24T08:56:02.882418084Z"),
                            Instant.parse("2026-11-24T08:56:02.886506667Z"),
                            new PluginTaskOutcome.Cancelled(),
                            CallSite.exact(fixture, "main", 51)),
                        new PluginTaskRecord(
                            2L,
                            "ContractFixture#main:56",
                            "",
                            46L,
                            Instant.parse("2026-11-24T08:56:02.882668750Z"),
                            Instant.parse("2026-11-24T08:56:02.884684167Z"),
                            new PluginTaskOutcome.Failed(
                                "java.lang.IllegalStateException",
                                "contract failure",
                                "java.lang.IllegalStateException: contract failure\n"
                                    + "\tat com.acme.contract.ContractFixture"
                                    + ".lambda$main$8(ContractFixture.java:58)\n"
                                    + "\tat com.ionutbanu.scopetracer.agent.TracingCallable"
                                    + ".call(TracingCallable.java:50)\n"
                                    + "\tat java.base/java.util.concurrent.StructuredTaskScopeImpl"
                                    + "$SubtaskImpl.run(StructuredTaskScopeImpl.java:345)\n"
                                    + "\tat java.base/java.lang.VirtualThread"
                                    + ".run(VirtualThread.java:470)\n"),
                            CallSite.exact(fixture, "main", 56)))),
                new PluginScopeRecord(
                    5L,
                    "quote\" back\\slash /path\nnext-line ünïcødé €",
                    "main",
                    3L,
                    Instant.parse("2026-11-24T08:56:02.887876584Z"),
                    Instant.parse("2026-11-24T08:56:02.888552500Z"),
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#main:68",
                            "",
                            47L,
                            Instant.parse("2026-11-24T08:56:02.888314875Z"),
                            Instant.parse("2026-11-24T08:56:02.888456167Z"),
                            new PluginTaskOutcome.Success(),
                            CallSite.exact(fixture, "main", 68)))),
                // Dumped while open: no close, and the running task has no completion and no
                // outcome. Its thread is the fork-time placeholder (the owner, id -1): the task
                // never reported from its own thread.
                new PluginScopeRecord(
                    6L,
                    "contract-open",
                    "contract-open-owner",
                    48L,
                    Instant.parse("2026-11-24T08:56:02.890810542Z"),
                    null,
                    null,
                    List.of(
                        new PluginTaskRecord(
                            1L,
                            "ContractFixture#main:76",
                            "contract-open-owner",
                            -1L,
                            Instant.parse("2026-11-24T08:56:02.890995584Z"),
                            null,
                            null,
                            CallSite.exact(fixture, "main", 76))))));

    assertSameModel(model, expected);
  }

  /** The structured call sites are exact; the nested and named-Callable forms are both present. */
  @Test
  void v060CallSitesAreExact() throws IOException {
    var tasks =
        parse("v0.6.0-contract").scopes().stream().flatMap(s -> s.tasks().stream()).toList();

    assertThat(tasks)
        .hasSize(9)
        .allSatisfy(
            t -> {
              assertThat(t.callSite()).isNotNull();
              assertThat(t.callSite().exact()).isTrue();
            });
    var nested = tasks.get(0).callSite();
    assertThat(nested.className()).isEqualTo("com.acme.contract.ContractFixture$Forks");
    assertThat(nested.methodName()).isEqualTo("forkProfile");
    assertThat(nested.line()).isEqualTo(12);
    var namedCallable = tasks.get(1).callSite();
    assertThat(namedCallable.className()).isEqualTo("com.acme.contract.ContractFixture$Inventory");
    assertThat(namedCallable.methodName()).isNull();
    assertThat(namedCallable.line()).isNull();
  }

  /**
   * Old recordings: 0.1.0 and 0.2.0 have no {@code callSite*} fields, so every task's {@code
   * callSite} is null and the plugin falls back to parsing {@code taskName} (spec §3, Plugin).
   */
  @ParameterizedTest
  @ValueSource(strings = {"v0.1.0-nested-failed-cancelled", "v0.2.0-nested-and-failed"})
  void recordingsBeforeCallSiteHaveNoCallSite(String golden) throws IOException {
    var tasks = parse(golden).scopes().stream().flatMap(s -> s.tasks().stream()).toList();

    assertThat(tasks).isNotEmpty().allSatisfy(t -> assertThat(t.callSite()).isNull());
  }

  /** Missing events: a scope still open and a task still running when the recording was dumped. */
  @Test
  void openScopeAndRunningTaskHaveNoEnd() throws IOException {
    var open =
        parse("v0.6.0-contract").scopes().stream()
            .filter(s -> s.name().equals("contract-open"))
            .findFirst()
            .orElseThrow();

    assertThat(open.closeTime()).isNull();
    assertThat(open.tasks()).hasSize(1);
    assertThat(open.tasks().get(0).completionTime()).isNull();
    assertThat(open.tasks().get(0).outcome()).isNull();
  }

  /** Every key in the analyzer's JSON is one the parser maps, or one listed as ignored. */
  @ParameterizedTest
  @ValueSource(
      strings = {"v0.1.0-nested-failed-cancelled", "v0.2.0-nested-and-failed", "v0.6.0-contract"})
  void everyKeyInTheGoldenIsMappedOrIgnored(String golden) throws IOException {
    var paths = new TreeSet<String>();
    collectKeyPaths(JsonParser.parseString(read(golden)), "", paths);

    assertThat(paths)
        .as(
            "key paths in %s.json that TraceModelJsonParser does not map: map them, or list them"
                + " in IGNORED_PATHS with the reason",
            golden)
        .isNotEmpty()
        .allSatisfy(
            path ->
                assertThat(MAPPED_PATHS.contains(path) || IGNORED_PATHS.containsKey(path))
                    .as("unknown key path %s", path)
                    .isTrue());
  }

  // --- harness ---

  private static void assertSameModel(PluginTraceModel actual, PluginTraceModel expected) {
    // Strict types: Success and Cancelled have no fields, so only their type tells them apart.
    assertThat(actual).usingRecursiveComparison().withStrictTypeChecking().isEqualTo(expected);
    assertThat(actual).isEqualTo(expected);
  }

  private static void collectKeyPaths(JsonElement element, String path, Set<String> paths) {
    if (element.isJsonObject()) {
      for (var entry : element.getAsJsonObject().entrySet()) {
        String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
        paths.add(child);
        collectKeyPaths(entry.getValue(), child, paths);
      }
    } else if (element.isJsonArray()) {
      for (var item : element.getAsJsonArray()) {
        collectKeyPaths(item, path + "[]", paths);
      }
    }
  }

  private static PluginTraceModel parse(String golden) throws IOException {
    return TraceModelJsonParser.parse(read(golden));
  }

  private static String read(String golden) throws IOException {
    String dir = System.getProperty(GOLDEN_DIR_PROPERTY);
    assertThat(dir)
        .as("system property %s, set by model/build.gradle.kts", GOLDEN_DIR_PROPERTY)
        .isNotNull();
    return Files.readString(Path.of(dir, golden + ".json"), StandardCharsets.UTF_8);
  }
}
