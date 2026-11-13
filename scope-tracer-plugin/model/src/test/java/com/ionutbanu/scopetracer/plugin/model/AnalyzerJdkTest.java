package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnalyzerJdkTest {

  private static final String JAVA = "/jdks/some-jdk/bin/java";

  private static final String TEMURIN_26 =
      """
      openjdk version "26" 2026-03-17
      OpenJDK Runtime Environment Temurin-26+35 (build 26+35)
      OpenJDK 64-Bit Server VM Temurin-26+35 (build 26+35, mixed mode, sharing)
      """;

  private static final String TEMURIN_27 =
      """
      openjdk version "27.0.1.0.2" 2026-10-21
      OpenJDK Runtime Environment Temurin-27.0.1.0.2+1 (build 27.0.1.0.2+1)
      OpenJDK 64-Bit Server VM Temurin-27.0.1.0.2+1 (build 27.0.1.0.2+1, mixed mode, sharing)
      """;

  @Test
  void refusesJdk26WithAMessageNamingTheJdkNeeded() {
    assertThat(AnalyzerJdk.refusal(JAVA, TEMURIN_26))
        .hasValueSatisfying(
            reason ->
                assertThat(reason).contains(JAVA).contains("JDK 26").contains("JDK 27 or newer"));
  }

  @Test
  void acceptsJdk27() {
    assertThat(AnalyzerJdk.refusal(JAVA, TEMURIN_27)).isEmpty();
  }

  @Test
  void acceptsANewerEarlyAccessJdk() {
    assertThat(AnalyzerJdk.refusal(JAVA, "openjdk version \"28-ea\" 2027-03-16")).isEmpty();
  }

  @Test
  void refusesJava8() {
    assertThat(AnalyzerJdk.refusal(JAVA, "java version \"1.8.0_292\""))
        .hasValueSatisfying(reason -> assertThat(reason).contains("JDK 8"));
  }

  @Test
  void refusesWhenTheVersionCannotBeRead() {
    assertThat(AnalyzerJdk.refusal(JAVA, "Error: could not open `lib/jvm.cfg'"))
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("Could not determine").contains("JDK 27"));
  }

  @Test
  void parsesTheVersionLineNotWhatComesBeforeIt() {
    var output = "Picked up JAVA_TOOL_OPTIONS: -Xmx1g -XX:MaxRAM=16g\n" + TEMURIN_27;

    assertThat(AnalyzerJdk.parseVersionOutput(output)).isEqualTo(27);
    assertThat(AnalyzerJdk.parseVersionOutput(TEMURIN_26)).isEqualTo(26);
    assertThat(AnalyzerJdk.parseVersionOutput("")).isEqualTo(-1);
    assertThat(AnalyzerJdk.parseVersionOutput(null)).isEqualTo(-1);
  }
}
