package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

  private static final String KNOWN =
      "record, html, verbose, output.dir, output.suffix, min.scopes, include.name, exclude.name,"
          + " include.package, exclude.package, sample.rate";

  /** What {@code parse} wrote to stderr through {@link AgentLog}, line by line. */
  private record Parsed(AgentConfig config, List<String> warnings) {}

  private static Parsed parseCapturing(String args) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    AgentConfig config;
    try {
      config = AgentConfig.parse(args);
    } finally {
      System.setErr(original);
    }
    return new Parsed(config, bytes.toString(StandardCharsets.UTF_8).lines().toList());
  }

  private static String warn(String text) {
    return "[scope-tracer] WARN " + text;
  }

  @Test
  void nullArgsYieldsDefaults() {
    var cfg = AgentConfig.parse(null);
    assertThat(cfg.verbose()).isFalse();
    assertThat(cfg.generateHtml()).isTrue();
    assertThat(cfg.outputDir()).isNull();
    assertThat(cfg.outputSuffix()).isEqualTo(".html");
    assertThat(cfg.minScopes()).isEqualTo(1);
    assertThat(cfg.filter()).isSameAs(CaptureFilter.PASSTHROUGH);
  }

  @Test
  void noFilterArgsYieldsPassthroughFilter() {
    var cfg = AgentConfig.parse("verbose,html=false");
    assertThat(cfg.filter()).isSameAs(CaptureFilter.PASSTHROUGH);
  }

  @Test
  void includeNameCompilesGlob() {
    var cfg = AgentConfig.parse("include.name=checkout-*");
    assertThat(cfg.filter().includeName()).isNotNull();
    assertThat(cfg.filter().includeName().matcher("checkout-pay").matches()).isTrue();
    assertThat(cfg.filter().includeName().matcher("other").matches()).isFalse();
  }

  @Test
  void excludeNameCompilesGlob() {
    var cfg = AgentConfig.parse("exclude.name=*-debug");
    assertThat(cfg.filter().excludeName()).isNotNull();
    assertThat(cfg.filter().excludeName().matcher("scope-debug").matches()).isTrue();
  }

  @Test
  void includePackageCompilesGlob() {
    var cfg = AgentConfig.parse("include.package=com.acme.**");
    assertThat(cfg.filter().includePackage()).isNotNull();
    assertThat(cfg.filter().includePackage().matcher("com.acme.checkout").matches()).isTrue();
    assertThat(cfg.filter().includePackage().matcher("com.other").matches()).isFalse();
  }

  @Test
  void excludePackageCompilesGlob() {
    var cfg = AgentConfig.parse("exclude.package=com.noisy.**");
    assertThat(cfg.filter().excludePackage()).isNotNull();
    assertThat(cfg.filter().excludePackage().matcher("com.noisy.lib").matches()).isTrue();
  }

  @Test
  void sampleRateParsedAsDouble() {
    var cfg = AgentConfig.parse("sample.rate=0.25");
    assertThat(cfg.filter().sampleRate()).isEqualTo(0.25);
  }

  @Test
  void sampleRateClampedToZeroOneRange() {
    assertThat(AgentConfig.parse("sample.rate=-1.0").filter().sampleRate()).isEqualTo(0.0);
    assertThat(AgentConfig.parse("sample.rate=2.0").filter().sampleRate()).isEqualTo(1.0);
  }

  @Test
  void malformedSampleRateFallsBackToDefault() {
    var cfg = AgentConfig.parse("sample.rate=abc");
    assertThat(cfg.filter().sampleRate()).isEqualTo(1.0);
    assertThat(cfg.filter()).isSameAs(CaptureFilter.PASSTHROUGH);
  }

  @Test
  void emptyFilterValueIsIgnored() {
    // include.name= with no value should be treated as "not set", not as a malformed pattern.
    var cfg = AgentConfig.parse("include.name=");
    assertThat(cfg.filter()).isSameAs(CaptureFilter.PASSTHROUGH);
  }

  @Test
  void allFilterArgsCombined() {
    var cfg =
        AgentConfig.parse(
            "include.name=checkout-*,exclude.name=*-debug,"
                + "include.package=com.acme.**,exclude.package=com.noisy.**,sample.rate=0.5");
    var f = cfg.filter();
    assertThat(f.includeName()).isNotNull();
    assertThat(f.excludeName()).isNotNull();
    assertThat(f.includePackage()).isNotNull();
    assertThat(f.excludePackage()).isNotNull();
    assertThat(f.sampleRate()).isEqualTo(0.5);
    assertThat(f.isPassthrough()).isFalse();
    assertThat(f.hasPackageRules()).isTrue();
  }

  @Test
  void htmlFalseDisablesGeneration() {
    assertThat(AgentConfig.parse("html=false").generateHtml()).isFalse();
    assertThat(AgentConfig.parse("html=FALSE").generateHtml()).isFalse();
    assertThat(AgentConfig.parse("html=true").generateHtml()).isTrue();
  }

  @Test
  void verboseFlagParsed() {
    assertThat(AgentConfig.parse("verbose").verbose()).isTrue();
  }

  @Test
  void outputDirParsedAsPath() {
    var cfg = AgentConfig.parse("output.dir=/tmp/reports");
    assertThat(cfg.outputDir()).isEqualTo(Path.of("/tmp/reports"));
  }

  @Test
  void outputSuffixGetsLeadingDotWhenMissing() {
    assertThat(AgentConfig.parse("output.suffix=report.html").outputSuffix())
        .isEqualTo(".report.html");
    assertThat(AgentConfig.parse("output.suffix=.report.html").outputSuffix())
        .isEqualTo(".report.html");
  }

  @Test
  void minScopesParsedAsInteger() {
    assertThat(AgentConfig.parse("min.scopes=42").minScopes()).isEqualTo(42);
  }

  @Test
  void minScopesFloorsAtOne() {
    // Negative/zero values are clamped to 1 — there is no "always skip" mode (use html=false).
    assertThat(AgentConfig.parse("min.scopes=0").minScopes()).isEqualTo(1);
    assertThat(AgentConfig.parse("min.scopes=-5").minScopes()).isEqualTo(1);
  }

  @Test
  void malformedMinScopesFallsBackToDefault() {
    assertThat(AgentConfig.parse("min.scopes=abc").minScopes()).isEqualTo(1);
  }

  @Test
  void unknownKeysIgnored() {
    var cfg = AgentConfig.parse("future.flag=yes,html=false");
    assertThat(cfg.generateHtml()).isFalse();
  }

  @Test
  void multipleArgsCombined() {
    var cfg = AgentConfig.parse("verbose,html=false,output.dir=/tmp,min.scopes=5");
    assertThat(cfg.verbose()).isTrue();
    assertThat(cfg.generateHtml()).isFalse();
    assertThat(cfg.outputDir()).isEqualTo(Path.of("/tmp"));
    assertThat(cfg.minScopes()).isEqualTo(5);
  }

  // --- record / record=<path> (issue #56) ---

  @Test
  void recordAbsentStartsNoRecordingOfTheAgentsOwn() {
    assertThat(AgentConfig.parse(null).record()).isFalse();
    assertThat(AgentConfig.parse("html=false,verbose").record()).isFalse();
  }

  @Test
  void bareRecordUsesTheDefaultLocation() {
    var cfg = AgentConfig.parse("record");
    assertThat(cfg.record()).isTrue();
    assertThat(cfg.recordPath()).isNull();
  }

  @Test
  void emptyRecordValueBehavesAsBareRecord() {
    var cfg = AgentConfig.parse("record=");
    assertThat(cfg.record()).isTrue();
    assertThat(cfg.recordPath()).isNull();
  }

  @Test
  void recordWithAFilePathKeepsThePath() {
    var cfg = AgentConfig.parse("record=/tmp/traces/run.jfr");
    assertThat(cfg.record()).isTrue();
    assertThat(cfg.recordPath()).isEqualTo(Path.of("/tmp/traces/run.jfr"));
  }

  @Test
  void recordWithAnExistingDirectoryPutsTheDefaultNameInIt(@TempDir Path dir) throws Exception {
    var cfg = AgentConfig.parse("record=" + dir);
    assertThat(cfg.record()).isTrue();

    Path dest =
        AgentRecording.resolveDestination(cfg.recordPath(), Path.of("/unused"), "default.jfr");

    assertThat(dest).isEqualTo(dir.resolve("default.jfr"));
  }

  @Test
  void recordWithAnInvalidPathStartsNoRecordingAndDoesNotThrow() {
    var cfg = AgentConfig.parse("record=bad\0path");
    assertThat(cfg.record()).isFalse();
    assertThat(cfg.recordPath()).isNull();
  }

  @Test
  void recordCombinesWithHtmlFalseAndOutputDir() {
    var quiet = AgentConfig.parse("record,html=false");
    assertThat(quiet.record()).isTrue();
    assertThat(quiet.generateHtml()).isFalse();

    var routed = AgentConfig.parse("record=/tmp/r.jfr,output.dir=/tmp/reports");
    assertThat(routed.recordPath()).isEqualTo(Path.of("/tmp/r.jfr"));
    assertThat(routed.outputDir()).isEqualTo(Path.of("/tmp/reports"));
    assertThat(routed.generateHtml()).isTrue();
  }

  @Test
  void invalidOutputDirIsIgnoredRatherThanThrown() {
    var cfg = AgentConfig.parse("output.dir=bad\0dir");
    assertThat(cfg.outputDir()).isNull();
  }

  @Test
  void defaultFileNameHasThePidAndJfrsTimestampFormat() {
    assertThat(
            AgentRecording.defaultFileName(4242, java.time.LocalDateTime.of(2026, 11, 14, 9, 5, 7)))
        .isEqualTo("scope-tracer-4242-2026_11_14_09_05_07.jfr");
  }

  @Test
  void defaultNameIsNeverWrittenOverAnExistingFile(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("scope-tracer-1-x.jfr"), "keep me");

    Path dest = AgentRecording.resolveDestination(null, dir, "scope-tracer-1-x.jfr");

    assertThat(dest).isEqualTo(dir.resolve("scope-tracer-1-x-1.jfr"));
    assertThat(dest).exists().isEmptyFile();
    assertThat(dir.resolve("scope-tracer-1-x.jfr")).hasContent("keep me");
  }

  @Test
  void relativeRecordPathIsResolvedAgainstTheWorkingDirectoryWithParentsCreated(@TempDir Path dir)
      throws Exception {
    Path dest = AgentRecording.resolveDestination(Path.of("a/b/run.jfr"), dir, "unused.jfr");

    assertThat(dest).isEqualTo(dir.resolve("a/b/run.jfr"));
    assertThat(dir.resolve("a/b")).isDirectory();
  }

  // --- argument warnings (issue #114) ---

  @Test
  void unknownKeyWarnsOnceListingTheKnownKeysAndStartsNoRecording() {
    var p = parseCapturing("recrod");
    assertThat(p.warnings())
        .containsExactly(
            warn("ignoring unknown agent argument 'recrod'; known arguments: " + KNOWN));
    assertThat(p.config().record()).isFalse();
  }

  @Test
  void theKnownKeysInTheWarningAreTheOnesTheParserAccepts() {
    // every listed key parses without the unknown-key warning
    for (String key : AgentConfig.KNOWN_KEYS) {
      assertThat(parseCapturing(key + "=1").warnings())
          .as(key)
          .noneMatch(w -> w.contains("unknown agent argument"));
    }
    assertThat(String.join(", ", AgentConfig.KNOWN_KEYS)).isEqualTo(KNOWN);
  }

  @Test
  void keysAreCaseSensitive() {
    for (String key : List.of("RECORD", "Record")) {
      var p = parseCapturing(key);
      assertThat(p.warnings()).hasSize(1);
      assertThat(p.warnings().get(0))
          .startsWith(warn("ignoring unknown agent argument '" + key + "'"));
      assertThat(p.config().record()).isFalse();
    }
  }

  @Test
  void anUnknownKeyDoesNotStopTheOthers() {
    var p = parseCapturing("recrod,record=/tmp/t.jfr,foo=bar,html=flase");
    assertThat(p.warnings()).hasSize(3);
    assertThat(p.warnings().get(0)).contains("unknown agent argument 'recrod'");
    assertThat(p.warnings().get(1)).contains("unknown agent argument 'foo'");
    assertThat(p.warnings().get(2))
        .isEqualTo(warn("ignoring malformed html value: 'flase' (expected true or false)"));
    assertThat(p.config().record()).isTrue();
    assertThat(p.config().recordPath()).isEqualTo(Path.of("/tmp/t.jfr"));
    assertThat(p.config().generateHtml()).isTrue();
  }

  @Test
  void aValueWithNoKeyWarnsAndEmptyTokensDoNot() {
    assertThat(parseCapturing("=x").warnings())
        .containsExactly(warn("ignoring agent argument with no key: '=x'"));
    var p = parseCapturing("record,=x");
    assertThat(p.warnings()).containsExactly(warn("ignoring agent argument with no key: '=x'"));
    assertThat(p.config().record()).isTrue();
    for (String quiet : List.of("record,,", "record,", ",", "=", " = ", ",,=,", "")) {
      assertThat(parseCapturing(quiet).warnings()).as(quiet).isEmpty();
    }
    assertThat(parseCapturing("record,,").config().record()).isTrue();
  }

  @Test
  void aWarningIsOneLineWhateverTheArgumentContains() {
    var p = parseCapturing("a\tb\rc\u0000d\u0001e\u202ef");
    assertThat(p.warnings()).hasSize(1);
    assertThat(p.warnings().get(0))
        .contains("'a\\tb\\rc\\u0000d\\u0001e\\u202ef'")
        .doesNotContain("\t")
        .doesNotContain("\r");
    var noKey = parseCapturing("=a\tb\r\nc");
    assertThat(noKey.warnings())
        .containsExactly(warn("ignoring agent argument with no key: '=a\\tb\\r\\nc'"));
  }

  @Test
  void aHugeArgumentIsShortenedInItsWarning() {
    var p = parseCapturing("x".repeat(1_000_000));
    assertThat(p.warnings()).hasSize(1);
    assertThat(p.warnings().get(0).length()).isLessThan(500);
  }

  @Test
  void recordTrueIsBareRecordIgnoringCase() {
    for (String v : List.of("record=true", "record=TRUE", "record=True")) {
      var p = parseCapturing(v);
      assertThat(p.warnings()).as(v).isEmpty();
      assertThat(p.config().record()).as(v).isTrue();
      assertThat(p.config().recordPath()).as(v).isNull();
    }
  }

  @Test
  void recordFalseIsAsIfAbsentAndSilent() {
    for (String v : List.of("record=false", "record=False", "record=FALSE")) {
      var p = parseCapturing(v);
      assertThat(p.warnings()).as(v).isEmpty();
      assertThat(p.config().record()).as(v).isFalse();
      assertThat(p.config().recordPath()).as(v).isNull();
    }
    assertThat(parseCapturing("record=/tmp/a.jfr,record=false").config().record()).isFalse();
  }

  @Test
  void aFileNamedTrueOrFalseIsStillReachableWithADotSlash() {
    assertThat(AgentConfig.parse("record=./false").recordPath()).isEqualTo(Path.of("./false"));
    assertThat(AgentConfig.parse("record=./false").record()).isTrue();
    assertThat(AgentConfig.parse("record=./true").recordPath()).isEqualTo(Path.of("./true"));
  }

  @Test
  void htmlTakesTrueFalseAndBareMeansTrue() {
    for (String v : List.of("html", "html=true", "html=TRUE", "html=")) {
      var p = parseCapturing(v);
      assertThat(p.warnings()).as(v).isEmpty();
      assertThat(p.config().generateHtml()).as(v).isTrue();
    }
    for (String v : List.of("html=false", "html=False")) {
      var p = parseCapturing(v);
      assertThat(p.warnings()).as(v).isEmpty();
      assertThat(p.config().generateHtml()).as(v).isFalse();
    }
  }

  @Test
  void htmlWithAnyOtherValueWarnsAndKeepsTheReportOn() {
    for (String bad : List.of("flase", "no", "0")) {
      var p = parseCapturing("html=" + bad);
      assertThat(p.warnings())
          .containsExactly(
              warn("ignoring malformed html value: '" + bad + "' (expected true or false)"));
      assertThat(p.config().generateHtml()).isTrue();
    }
  }

  @Test
  void verboseFalseTurnsVerboseOffAndBareOrTrueTurnsItOn() {
    assertThat(parseCapturing("verbose=false").config().verbose()).isFalse();
    assertThat(parseCapturing("verbose=FALSE").warnings()).isEmpty();
    for (String v : List.of("verbose", "verbose=true", "verbose=True")) {
      var p = parseCapturing(v);
      assertThat(p.warnings()).as(v).isEmpty();
      assertThat(p.config().verbose()).as(v).isTrue();
    }
  }

  @Test
  void verboseWithAnotherValueWarnsAndLeavesItOff() {
    var p = parseCapturing("verbose=maybe");
    assertThat(p.warnings())
        .containsExactly(
            warn("ignoring malformed verbose value: 'maybe' (expected true or false)"));
    assertThat(p.config().verbose()).isFalse();
  }

  @Test
  void anExplicitVerboseArgumentBeatsTheSystemProperty() {
    withVerbosePropertyOn(
        () -> {
          assertThat(parseCapturing(null).config().verbose()).isTrue();
          assertThat(parseCapturing("verbose=false").config().verbose()).isFalse();
          assertThat(parseCapturing("verbose=false").warnings()).isEmpty();
          return null;
        });
  }

  private static void withVerbosePropertyOn(Supplier<Void> body) {
    String key = "scopetracer.agent.verbose";
    String before = System.getProperty(key);
    System.setProperty(key, "true");
    try {
      body.get();
    } finally {
      if (before == null) System.clearProperty(key);
      else System.setProperty(key, before);
    }
  }

  @Test
  void minScopesBelowOneWarnsAndBehavesAsOne() {
    for (String v : List.of("0", "-3")) {
      var p = parseCapturing("min.scopes=" + v);
      assertThat(p.warnings()).containsExactly(warn("min.scopes must be at least 1, using 1"));
      assertThat(p.config().minScopes()).isEqualTo(1);
    }
    assertThat(parseCapturing("min.scopes=1").warnings()).isEmpty();
  }

  @Test
  void sampleRateOutOfRangeWarnsAndIsClampedAsBefore() {
    var high = parseCapturing("sample.rate=5");
    assertThat(high.warnings())
        .containsExactly(warn("sample.rate must be between 0.0 and 1.0, using 1.0"));
    assertThat(high.config().filter().sampleRate()).isEqualTo(1.0);
    var low = parseCapturing("sample.rate=-0.5");
    assertThat(low.warnings())
        .containsExactly(warn("sample.rate must be between 0.0 and 1.0, using 0.0"));
    assertThat(low.config().filter().sampleRate()).isEqualTo(0.0);
    assertThat(parseCapturing("sample.rate=0").warnings()).isEmpty();
    assertThat(parseCapturing("sample.rate=1").warnings()).isEmpty();
  }

  @Test
  void sampleRateNaNIsMalformedAndKeepsOne() {
    var p = parseCapturing("sample.rate=NaN");
    assertThat(p.warnings()).containsExactly(warn("ignoring malformed sample.rate value: NaN"));
    assertThat(p.config().filter().sampleRate()).isEqualTo(1.0);
    assertThat(p.config().filter()).isSameAs(CaptureFilter.PASSTHROUGH);
  }

  @Test
  void aKeyThatNeedsAValueWarnsWithoutOneAndKeepsTheDefault() {
    for (String key :
        List.of(
            "output.dir",
            "output.suffix",
            "min.scopes",
            "sample.rate",
            "include.name",
            "exclude.name",
            "include.package",
            "exclude.package")) {
      for (String arg : List.of(key + "=", key)) {
        var p = parseCapturing(arg);
        assertThat(p.warnings())
            .as(arg)
            .containsExactly(warn("ignoring " + key + " with no value"));
        assertThat(p.config().outputDir()).as(arg).isNull();
        assertThat(p.config().outputSuffix()).as(arg).isEqualTo(".html");
        assertThat(p.config().minScopes()).as(arg).isEqualTo(1);
        assertThat(p.config().filter()).as(arg).isSameAs(CaptureFilter.PASSTHROUGH);
      }
    }
    for (String ok : List.of("record", "record=")) {
      assertThat(parseCapturing(ok).warnings()).as(ok).isEmpty();
      assertThat(parseCapturing(ok).config().record()).as(ok).isTrue();
    }
  }

  @Test
  void validArgumentsGiveNoWarning() {
    for (String ok :
        List.of(
            "html=false",
            "record,html=false",
            "record=/tmp/r.jfr,output.dir=/tmp/reports,output.suffix=.report.html,min.scopes=2",
            "include.name=checkout-*,exclude.name=*-debug,include.package=com.acme.**,"
                + "exclude.package=com.noisy.**,sample.rate=0.25",
            " record ",
            "verbose,html=false,output.dir=/tmp,min.scopes=5")) {
      assertThat(parseCapturing(ok).warnings()).as(ok).isEmpty();
    }
  }

  @Test
  void theExistingMalformedValueWarningsKeepTheirTextAndAppearOnce() {
    assertThat(parseCapturing("min.scopes=abc").warnings())
        .containsExactly(warn("ignoring malformed min.scopes value: abc"));
    assertThat(parseCapturing("sample.rate=abc").warnings())
        .containsExactly(warn("ignoring malformed sample.rate value: abc"));
    assertThat(parseCapturing("include.name=[").warnings()).isEmpty();
    var path = parseCapturing("output.dir=bad\0dir");
    assertThat(path.warnings()).hasSize(1);
    assertThat(path.warnings().get(0))
        .startsWith(warn("ignoring output.dir value that is not a valid path: "));
    var rec = parseCapturing("record=bad\0path");
    assertThat(rec.warnings()).hasSize(1);
    assertThat(rec.warnings().get(0))
        .startsWith(warn("ignoring record value that is not a valid path: "));
  }

  @Test
  void aKeyGivenTwiceKeepsTheLastValueWithoutAWarning() {
    var p = parseCapturing("record=/tmp/a.jfr,record=/tmp/b.jfr,min.scopes=3,min.scopes=4");
    assertThat(p.warnings()).isEmpty();
    assertThat(p.config().recordPath()).isEqualTo(Path.of("/tmp/b.jfr"));
    assertThat(p.config().minScopes()).isEqualTo(4);
  }

  @Test
  void hostileInputAlwaysYieldsAConfig() {
    var inputs = new java.util.ArrayList<String>();
    inputs.add(null);
    inputs.add("");
    inputs.add(",".repeat(10_000));
    inputs.add("=");
    inputs.add("=".repeat(10_000));
    inputs.add("\0");
    inputs.add("record=\0,html=\0,\0=\0");
    inputs.add("x".repeat(1_000_000));
    inputs.add("=" + "x".repeat(1_000_000));
    inputs.add("a=b,".repeat(100_000));
    String huge = "9".repeat(1_000_000);
    String[] badValues = {
      "",
      "\0",
      "NaN",
      "-NaN",
      "Infinity",
      "-Infinity",
      "1e999",
      "-1e999",
      "abc",
      "-1",
      "0",
      "99999999999999999999",
      "\t\r\n",
      "[",
      "***",
      huge,
      "x".repeat(1_000_000),
      "true",
      "false",
      "\ud800"
    };
    for (String key : AgentConfig.KNOWN_KEYS) {
      inputs.add(key);
      inputs.add(key + "=");
      for (String bad : badValues) inputs.add(key + "=" + bad);
    }
    var original = System.err;
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      for (String in : inputs) {
        assertThat(AgentConfig.parse(in))
            .as("input of length %s", in == null ? -1 : in.length())
            .isNotNull();
      }
    } finally {
      System.setErr(original);
    }
  }
}
