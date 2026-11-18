package com.ionutbanu.scopetracer.demos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoJarsTest {

  @Test
  void aKnownVersionIsInEveryName() {
    var jars = new DemoJars("0.4.0-SNAPSHOT");

    assertThat(jars.analyzerJar())
        .isEqualTo(
            "scope-tracer-analyzer/target/scope-tracer-analyzer-0.4.0-SNAPSHOT-executable.jar");
    assertThat(jars.agentJar())
        .isEqualTo("scope-tracer-agent/target/scope-tracer-agent-0.4.0-SNAPSHOT-agent.jar");
    assertThat(jars.versionKnown()).isTrue();
    assertThat(jars.notes()).hasSize(1).noneMatch(n -> n.contains(DemoJars.PLACEHOLDER));
  }

  @Test
  void anUnknownVersionPrintsThePlaceholderAndSaysSo() {
    for (String unknown : new String[] {null, "", "  "}) {
      var jars = new DemoJars(unknown);

      assertThat(jars.analyzerJar())
          .isEqualTo("scope-tracer-analyzer/target/scope-tracer-analyzer-<VERSION>-executable.jar");
      assertThat(jars.agentJar())
          .isEqualTo("scope-tracer-agent/target/scope-tracer-agent-<VERSION>-agent.jar");
      assertThat(jars.versionKnown()).isFalse();
      assertThat(jars.notes()).hasSize(2).last().asString().contains("<VERSION>", "unknown");
    }
  }

  @Test
  void theRunningDemoDoesNotThrowWithoutAManifest() {
    // Surefire runs from target/classes, so there is no manifest and the version is unknown.
    assertThat(DemoJars.ofRunningDemo().analyzerJar()).startsWith("scope-tracer-analyzer/target/");
  }
}
