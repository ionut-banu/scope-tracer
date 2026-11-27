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
    assertThat(RunActionState.decide(false, new Selection.Other("Tests", "Gradle")).visible())
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
            "No run configuration is selected. Select or create a Java Application or JUnit run configuration, then choose \"Run with Scope Tracer\" again.");
  }

  /** Until #64 a JUnit configuration was explained; now it runs, like an application. */
  @Test
  void aJUnitConfigurationRuns() {
    Decision d = RunActionState.decide(true, new Selection.JUnit());

    assertThat(d.visible()).isTrue();
    assertThat(d.enabled()).isTrue();
    assertThat(d.runs()).isTrue();
    assertThat(d.explanation()).isNull();
  }

  @Test
  void noProjectOpenHidesTheActionForAJUnitConfigurationToo() {
    assertThat(RunActionState.decide(false, new Selection.JUnit()).visible()).isFalse();
  }

  // #212: no article before the type name. One case per type that starts with a vowel and one per
  // type that starts with a consonant, so that neither "a" nor "an" can come back unnoticed.

  @Test
  void anAntTargetConfigurationIsExplainedWithoutAnArticle() {
    assertExplainsOther("build.xml jar", "Ant Target");
  }

  @Test
  void anAndroidAppConfigurationIsExplainedWithoutAnArticle() {
    assertExplainsOther("app", "Android App");
  }

  @Test
  void aKotlinConfigurationIsExplainedWithoutAnArticle() {
    assertExplainsOther("MainKt", "Kotlin");
  }

  @Test
  void aTestNgConfigurationIsExplainedWithoutAnArticle() {
    assertExplainsOther("Suite", "TestNG");
  }

  @Test
  void noTypeNameIsPrecededByAnArticle() {
    for (var type :
        new String[] {
          "Ant Target", "Android App", "Gradle", "Remote JVM Debug", "Kotlin", "TestNG"
        }) {
      var explanation = RunActionState.decide(true, new Selection.Other("x", type)).explanation();

      assertThat(explanation)
          .as(type)
          .contains("has run configuration type \"" + type + "\".")
          .doesNotContain(" a " + type)
          .doesNotContain(" an " + type)
          .doesNotContain(" a \"" + type)
          .doesNotContain(" an \"" + type);
    }
  }

  @Test
  void aNameAndTypeWithMarkupCharactersArePassedOnUnchangedForTheGlueToEscape() {
    var explanation =
        RunActionState.decide(true, new Selection.Other("a<b>&c", "T<&>")).explanation();

    assertThat(explanation).startsWith("\"a<b>&c\" has run configuration type \"T<&>\".");
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
                + "\" has run configuration type \""
                + typeName
                + "\". \"Run with Scope Tracer\" runs Java Application and JUnit configurations: select or create one, then choose it again.");
  }
}
