package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

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
}
