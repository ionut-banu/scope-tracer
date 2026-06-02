package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.agent.util.Glob;
import org.junit.jupiter.api.Test;

class CaptureFilterTest {

  @Test
  void passthroughCapturesEverything() {
    var f = CaptureFilter.PASSTHROUGH;
    assertThat(f.isPassthrough()).isTrue();
    assertThat(f.hasPackageRules()).isFalse();
    assertThat(f.shouldCapture("anything", "com.acme")).isTrue();
    assertThat(f.shouldCapture("scope#method", "")).isTrue();
  }

  @Test
  void excludeNameDropsMatchingScope() {
    var f = new CaptureFilter(null, Glob.toRegex("noise-*"), null, null, 1.0);
    assertThat(f.shouldCapture("noise-debug", "com.acme")).isFalse();
    assertThat(f.shouldCapture("checkout-pay", "com.acme")).isTrue();
  }

  @Test
  void includeNameRequiresMatch() {
    var f = new CaptureFilter(Glob.toRegex("checkout-*"), null, null, null, 1.0);
    assertThat(f.shouldCapture("checkout-pay", "com.acme")).isTrue();
    assertThat(f.shouldCapture("other-flow", "com.acme")).isFalse();
  }

  @Test
  void excludeWinsOverInclude() {
    // Both include and exclude match — exclude must win.
    var f =
        new CaptureFilter(Glob.toRegex("checkout-*"), Glob.toRegex("*-debug"), null, null, 1.0);
    assertThat(f.shouldCapture("checkout-debug", "com.acme")).isFalse();
    assertThat(f.shouldCapture("checkout-pay", "com.acme")).isTrue();
  }

  @Test
  void includePackageRequiresMatchAndFailsClosedOnUnknown() {
    var f = new CaptureFilter(null, null, Glob.toRegex("com.acme.**"), null, 1.0);
    assertThat(f.shouldCapture("foo", "com.acme.checkout")).isTrue();
    assertThat(f.shouldCapture("foo", "com.other")).isFalse();
    // Stack walk produced no user frame — include rule fails closed.
    assertThat(f.shouldCapture("foo", "")).isFalse();
    assertThat(f.shouldCapture("foo", null)).isFalse();
  }

  @Test
  void excludePackageDropsMatchingScope() {
    var f = new CaptureFilter(null, null, null, Glob.toRegex("com.noisy.**"), 1.0);
    assertThat(f.shouldCapture("foo", "com.noisy.lib")).isFalse();
    assertThat(f.shouldCapture("foo", "com.acme")).isTrue();
    // Empty package — nothing to exclude on, so the scope passes through this rule.
    assertThat(f.shouldCapture("foo", "")).isTrue();
  }

  @Test
  void sampleRateOfZeroDropsEverything() {
    var f = new CaptureFilter(null, null, null, null, 0.0);
    assertThat(f.shouldCapture("foo", "com.acme")).isFalse();
    assertThat(f.shouldCapture("bar", "com.other")).isFalse();
  }

  @Test
  void sampleRateOfOneKeepsEverything() {
    var f = new CaptureFilter(null, null, null, null, 1.0);
    for (int i = 0; i < 100; i++) {
      assertThat(f.shouldCapture("foo-" + i, "com.acme")).isTrue();
    }
  }

  @Test
  void hasPackageRulesTrueForIncludePackage() {
    assertThat(new CaptureFilter(null, null, Glob.toRegex("x"), null, 1.0).hasPackageRules())
        .isTrue();
  }

  @Test
  void hasPackageRulesTrueForExcludePackage() {
    assertThat(new CaptureFilter(null, null, null, Glob.toRegex("x"), 1.0).hasPackageRules())
        .isTrue();
  }

  @Test
  void hasPackageRulesFalseForNameOnlyFilters() {
    assertThat(
            new CaptureFilter(Glob.toRegex("x"), Glob.toRegex("y"), null, null, 1.0)
                .hasPackageRules())
        .isFalse();
  }

  @Test
  void isPassthroughFalseWhenSampleRateSubOne() {
    assertThat(new CaptureFilter(null, null, null, null, 0.5).isPassthrough()).isFalse();
  }
}
