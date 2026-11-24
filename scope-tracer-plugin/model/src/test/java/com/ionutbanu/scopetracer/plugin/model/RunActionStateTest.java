package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.RunActionState.Decision;
import com.ionutbanu.scopetracer.plugin.model.RunActionState.Selection;
import org.junit.jupiter.api.Test;

class RunActionStateTest {

  @Test
  void noProjectOpenHidesTheAction() {
    Decision d = RunActionState.decide(false, new Selection.None());

    assertThat(d.visible()).isFalse();
    assertThat(d.enabled()).isFalse();
    assertThat(d.runs()).isFalse();
  }

  @Test
  void noProjectOpenHidesTheActionWhateverTheSelection() {
    assertThat(RunActionState.decide(false, new Selection.Application()).visible()).isFalse();
    assertThat(RunActionState.decide(false, new Selection.Other("Tests", "JUnit")).visible())
        .isFalse();
  }

  @Test
  void noConfigurationSelectedIsVisibleAndExplainsWhatIsNeeded() {
    Decision d = RunActionState.decide(true, new Selection.None());

    assertThat(d.visible()).isTrue();
    assertThat(d.enabled()).isTrue();
    assertThat(d.runs()).isFalse();
    assertThat(d.explanation())
        .isEqualTo(
            "No run configuration is selected. Select or create a Java Application run configuration, then choose \"Run with Scope Tracer\" again.");
  }

  @Test
  void aJUnitConfigurationIsVisibleAndNamedInTheExplanation() {
    assertExplainsOther("MyServiceTest", "JUnit");
  }

  @Test
  void aGradleConfigurationIsVisibleAndNamedInTheExplanation() {
    assertExplainsOther("app [run]", "Gradle");
  }

  @Test
  void aRemoteJvmDebugConfigurationIsVisibleAndNamedInTheExplanation() {
    assertExplainsOther("Attach 5005", "Remote JVM Debug");
  }

  @Test
  void aJavaApplicationConfigurationRuns() {
    Decision d = RunActionState.decide(true, new Selection.Application());

    assertThat(d.visible()).isTrue();
    assertThat(d.enabled()).isTrue();
    assertThat(d.runs()).isTrue();
    assertThat(d.explanation()).isNull();
  }

  private static void assertExplainsOther(String name, String typeName) {
    Decision d = RunActionState.decide(true, new Selection.Other(name, typeName));

    assertThat(d.visible()).isTrue();
    assertThat(d.enabled()).isTrue();
    assertThat(d.runs()).isFalse();
    assertThat(d.explanation())
        .isEqualTo(
            "\""
                + name
                + "\" is a "
                + typeName
                + " run configuration. \"Run with Scope Tracer\" runs Java Application configurations: select or create one, then choose it again.");
  }
}
