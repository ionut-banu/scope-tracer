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
