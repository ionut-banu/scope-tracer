package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
}
