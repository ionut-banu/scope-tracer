package com.ionutbanu.scopetracer.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScopeNameDeriver}. These tests call {@link ScopeNameDeriver#derive()}
 * directly — no ByteBuddy or agent setup is required.
 */
class ScopeNameDeriverTest {

  // --- basic format ---

  /**
   * Called from a regular named method, {@link ScopeNameDeriver#derive()} must return a string of
   * the form {@code "SimpleClassName#methodName"} where the class is the caller's simple name (no
   * package prefix) and the method is the unmangled caller method name.
   */
  @Test
  void deriveReturnsSimpleClassHashMethodFormat() {
    String derived = ScopeNameDeriver.derive();
    assertThat(derived).isEqualTo("ScopeNameDeriverTest#deriveReturnsSimpleClassHashMethodFormat");
  }

  /**
   * The returned name must contain exactly one {@code #} separator, confirming that neither the
   * class nor the method part is empty and that no extra separators were introduced.
   */
  @Test
  void derivedNameContainsExactlyOneHash() {
    String derived = ScopeNameDeriver.derive();
    assertThat(derived).contains("#");
    assertThat(derived.indexOf('#')).isEqualTo(derived.lastIndexOf('#'));
  }

  // --- lambda name stripping ---

  /**
   * When {@link ScopeNameDeriver#derive()} is called from inside a lambda, the JVM synthesises a
   * method name of the form {@code lambda$enclosingMethod$N}. {@code ScopeNameDeriver} must strip
   * the {@code lambda$} prefix and the trailing {@code $N} counter, leaving only the enclosing
   * method name — so the result is the same as calling from the method directly.
   */
  @Test
  void deriveLambdaContextStripsLambdaPrefix() {
    String[] result = new String[1];
    Runnable r = () -> result[0] = ScopeNameDeriver.derive();
    r.run();

    assertThat(result[0]).doesNotContain("lambda$");
    assertThat(result[0]).contains("#");
    // The enclosing method name must appear in the result (lambda prefix stripped).
    assertThat(result[0]).contains("deriveLambdaContextStripsLambdaPrefix");
  }

  // --- no package prefix ---

  /**
   * The class part of the returned name must be the simple class name only — no package prefix.
   * Verifying the absence of a dot before the {@code #} guards against regressions in the
   * package-stripping logic.
   */
  @Test
  void derivedClassNameHasNoPackagePrefix() {
    String derived = ScopeNameDeriver.derive();
    String classPart = derived.substring(0, derived.indexOf('#'));
    assertThat(classPart).doesNotContain(".");
  }
}
