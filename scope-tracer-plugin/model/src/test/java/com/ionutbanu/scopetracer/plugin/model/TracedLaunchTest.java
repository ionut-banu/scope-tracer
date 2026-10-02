package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TracedLaunchTest {

  private static final Path AGENT = Path.of("/plugin/scope-tracer-agent-agent.jar");
  private static final Path JFR = Path.of("/tmp/run-1.jfr");

  private static List<String> applied(TracedLaunch.Plan plan) {
    assertThat(plan).isInstanceOf(TracedLaunch.Plan.Apply.class);
    return ((TracedLaunch.Plan.Apply) plan).extraVmArgs();
  }

  private static String refused(TracedLaunch.Plan plan) {
    assertThat(plan).isInstanceOf(TracedLaunch.Plan.Refuse.class);
    return ((TracedLaunch.Plan.Refuse) plan).reason();
  }

  @Test
  void addsAgentPreviewAndRecordingForAPlainConfiguration() {
    var extra = applied(TracedLaunch.plan(List.of("-Xmx512m"), 26, AGENT, JFR));

    assertThat(extra)
        .containsExactly(
            "-javaagent:" + AGENT,
            "--enable-preview",
            "-XX:StartFlightRecording=filename=" + JFR + ",dumponexit=true");
  }

  @Test
  void doesNotDuplicateEnablePreview() {
    var extra = applied(TracedLaunch.plan(List.of("--enable-preview"), 26, AGENT, JFR));

    assertThat(extra).doesNotContain("--enable-preview");
    assertThat(extra).hasSize(2);
  }

  @Test
  void leavesTheConfigurationsOwnArgumentsAlone() {
    var existing = List.of("-Xmx512m", "-Dfoo=bar");
    TracedLaunch.plan(existing, 26, AGENT, JFR);

    assertThat(existing).containsExactly("-Xmx512m", "-Dfoo=bar");
  }

  @Test
  void refusesAJdkOlderThan26() {
    assertThat(refused(TracedLaunch.plan(List.of(), 21, AGENT, JFR))).contains("JDK 21");
  }

  @Test
  void refusesWhenTheJdkVersionIsUnknown() {
    assertThat(refused(TracedLaunch.plan(List.of(), -1, AGENT, JFR)))
        .contains("Could not determine");
  }

  @Test
  void refusesWhenTheAgentIsAlreadyAttached() {
    var existing = List.of("-javaagent:/x/scope-tracer-agent-0.3.0-agent.jar=verbose");

    assertThat(refused(TracedLaunch.plan(existing, 26, AGENT, JFR))).contains("twice");
  }

  @Test
  void anUnrelatedJavaagentIsNotAConflict() {
    var existing = List.of("-javaagent:/x/other-agent.jar");

    assertThat(applied(TracedLaunch.plan(existing, 26, AGENT, JFR))).hasSize(3);
  }

  @Test
  void refusesWhenTheConfigurationStartsItsOwnRecording() {
    var existing = List.of("-XX:StartFlightRecording=filename=mine.jfr");

    assertThat(refused(TracedLaunch.plan(existing, 26, AGENT, JFR))).contains("collide");
  }

  @Test
  void parsesJavaVersionStrings() {
    assertThat(TracedLaunch.parseJavaMajor("26")).isEqualTo(26);
    assertThat(TracedLaunch.parseJavaMajor("26.0.2")).isEqualTo(26);
    assertThat(TracedLaunch.parseJavaMajor("27-ea")).isEqualTo(27);
    assertThat(TracedLaunch.parseJavaMajor("1.8.0_292")).isEqualTo(8);
    assertThat(TracedLaunch.parseJavaMajor("")).isEqualTo(-1);
    assertThat(TracedLaunch.parseJavaMajor(null)).isEqualTo(-1);
    assertThat(TracedLaunch.parseJavaMajor("abc")).isEqualTo(-1);
  }
}
