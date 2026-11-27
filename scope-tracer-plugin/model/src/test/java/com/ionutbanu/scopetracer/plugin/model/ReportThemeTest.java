package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The theme script (#191): an explicit attribute, always one of two fixed values. */
class ReportThemeTest {

  @Test
  void darkSetsTheDarkAttribute() {
    assertThat(ReportTheme.script(true))
        .isEqualTo("document.documentElement.setAttribute('data-theme','dark');");
  }

  @Test
  void lightSetsTheLightAttributeAndNeverRemovesIt() {
    assertThat(ReportTheme.script(false))
        .isEqualTo("document.documentElement.setAttribute('data-theme','light');")
        .doesNotContain("removeAttribute");
  }

  @Test
  void theTwoScriptsDifferOnlyInTheValue() {
    assertThat(ReportTheme.script(true).replace("dark", "light"))
        .isEqualTo(ReportTheme.script(false));
  }
}
