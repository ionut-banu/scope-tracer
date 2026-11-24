package com.ionutbanu.scopetracer.analyzer;

import static com.ionutbanu.scopetracer.analyzer.TestRecordings.resource;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The analyzer's half of the JSON contract with the plugin (#94, spec §3 "Plugin"): for each
 * fixture recording, {@code TraceModelJson.toJson(JfrParser.parse(fixture))} is byte for byte the
 * checked-in golden file {@code src/test/resources/contract/<fixture>.json}. The plugin's {@code
 * :model} test ({@code AnalyzerJsonContractTest}) parses the same files, so a change to the JSON
 * fails here until the goldens are regenerated, and then fails there until the parser follows.
 *
 * <p>The goldens are written only by regenerating, from {@code scope-tracer-analyzer}:
 *
 * <pre>{@code
 * mvn -pl scope-tracer-analyzer -am -Dtest=TraceModelJsonContractTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dscopetracer.regenerateGoldens=true test
 * }</pre>
 *
 * (run from the repository root). Without {@value #REGENERATE} the test only reads them.
 *
 * <p>The fixtures:
 *
 * <ul>
 *   <li>{@code recordings/v0.1.0-nested-failed-cancelled.jfr} and {@code
 *       recordings/v0.2.0-nested-and-failed.jfr}: made by those tags, described on {@code
 *       JfrParserEventOrderTest}. They predate the {@code callSite*} fields, so every task's {@code
 *       callSite} is {@code null}.
 *   <li>{@code recordings/v0.6.0-contract.jfr}: made by tag {@code v0.6.0} with the agent, as "Run
 *       with Scope Tracer" does. The program is checked in verbatim as {@code
 *       recordings/v0.6.0-contract.java.txt} (class {@code com.acme.contract.ContractFixture}; the
 *       call-site lines in the recording are that file's lines). It opens, in order: {@code
 *       contract-root}, whose three tasks are a lambda forked from the nested class {@code
 *       ContractFixture$Forks} (call site with class, method and line), a named {@code Callable}
 *       class {@code ContractFixture$Inventory} (call site with the class only), and a task that
 *       opens {@code contract-child}, whose task opens {@code contract-grandchild} (two levels of
 *       nesting); {@code contract-failing}, whose second task throws {@code
 *       IllegalStateException("contract failure")} once the first is running, which cancels the
 *       first; a scope named {@code quote" back\slash /path}, a newline, {@code next-line ünïcødé
 *       €}; and {@code contract-open}, opened on a daemon thread whose task is still blocked when
 *       {@code main} returns, so the scope has no close and the task no completion. It was recorded
 *       on Temurin 27.0.1 with the released {@code scope-tracer-agent-0.6.0-agent.jar} (SHA-1
 *       {@code 8d0c182527d4c3a5ff7df20106cbfe8c043258c2}, the GitHub release asset):
 *       <pre>{@code
 * javac --release 27 --enable-preview -d classes ContractFixture.java
 * java -javaagent:scope-tracer-agent-0.6.0-agent.jar=html=false --enable-preview \
 *     -XX:StartFlightRecording=filename=full.jfr,dumponexit=true \
 *     -cp classes com.acme.contract.ContractFixture
 * jfr scrub --include-events 'com.ionutbanu.scopetracer.*' full.jfr v0.6.0-contract.jfr
 * }</pre>
 *       The {@code -javaagent} and {@code -XX:StartFlightRecording} arguments are the ones {@code
 *       TracedLaunch} adds. {@code jfr scrub} keeps only the scope-tracer events: the rest of a
 *       default recording (environment variables, system processes, flags) is machine-specific and
 *       not input to the analyzer.
 * </ul>
 */
class TraceModelJsonContractTest {

  /** Set to {@code true} to rewrite the goldens from the fixtures instead of comparing. */
  static final String REGENERATE = "scopetracer.regenerateGoldens";

  /** Where the goldens live in the source tree, relative to the module (surefire's directory). */
  private static final Path GOLDEN_SOURCE_DIR = Path.of("src/test/resources/contract");

  private static final String HOW_TO_REGENERATE =
      "The analyzer's JSON no longer matches the golden file that the plugin's :model contract"
          + " test parses. If the change is intended, regenerate the goldens from the repository"
          + " root with `mvn -pl scope-tracer-analyzer -am -Dtest=TraceModelJsonContractTest"
          + " -Dsurefire.failIfNoSpecifiedTests=false -D"
          + REGENERATE
          + "=true test`, then make the plugin's TraceModelJsonParser and AnalyzerJsonContractTest"
          + " follow in the same issue (spec §3, Plugin).";

  @ParameterizedTest
  @ValueSource(
      strings = {"v0.1.0-nested-failed-cancelled", "v0.2.0-nested-and-failed", "v0.6.0-contract"})
  void analyzerJsonIsTheGoldenFile(String fixture) throws Exception {
    String json =
        TraceModelJson.toJson(JfrParser.parse(resource("recordings/" + fixture + ".jfr")));
    byte[] actual = json.getBytes(StandardCharsets.UTF_8);

    if (Boolean.getBoolean(REGENERATE)) {
      Files.createDirectories(GOLDEN_SOURCE_DIR);
      Files.write(GOLDEN_SOURCE_DIR.resolve(fixture + ".json"), actual);
      return;
    }

    byte[] golden = golden(fixture);
    assertThat(json).as(HOW_TO_REGENERATE).isEqualTo(new String(golden, StandardCharsets.UTF_8));
    assertThat(actual).as(HOW_TO_REGENERATE).isEqualTo(golden);
  }

  private static byte[] golden(String fixture) throws IOException {
    String name = "contract/" + fixture + ".json";
    try (InputStream in =
        TraceModelJsonContractTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in)
          .as("missing golden %s: regenerate the goldens, see TraceModelJsonContractTest", name)
          .isNotNull();
      return in.readAllBytes();
    }
  }
}
