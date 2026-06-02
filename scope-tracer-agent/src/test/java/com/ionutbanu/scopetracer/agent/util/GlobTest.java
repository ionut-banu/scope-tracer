package com.ionutbanu.scopetracer.agent.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GlobTest {

  @Test
  void literalPatternMatchesItselfOnly() {
    var p = Glob.toRegex("checkout-flow");
    assertThat(p.matcher("checkout-flow").matches()).isTrue();
    assertThat(p.matcher("checkout-flow-2").matches()).isFalse();
    assertThat(p.matcher("a-checkout-flow").matches()).isFalse();
  }

  @Test
  void singleStarMatchesAnySubstring() {
    var p = Glob.toRegex("checkout-*");
    assertThat(p.matcher("checkout-").matches()).isTrue();
    assertThat(p.matcher("checkout-payment").matches()).isTrue();
    assertThat(p.matcher("checkout-foo.bar").matches()).isTrue();
    assertThat(p.matcher("payment").matches()).isFalse();
  }

  @Test
  void doubleStarIsEquivalentToSingleStar() {
    var p = Glob.toRegex("com.acme.**");
    assertThat(p.matcher("com.acme.foo").matches()).isTrue();
    assertThat(p.matcher("com.acme.foo.bar").matches()).isTrue();
    assertThat(p.matcher("com.other").matches()).isFalse();
  }

  @Test
  void questionMarkMatchesExactlyOneCharacter() {
    var p = Glob.toRegex("scope-?");
    assertThat(p.matcher("scope-1").matches()).isTrue();
    assertThat(p.matcher("scope-A").matches()).isTrue();
    assertThat(p.matcher("scope-").matches()).isFalse();
    assertThat(p.matcher("scope-12").matches()).isFalse();
  }

  @Test
  void regexMetacharactersAreEscaped() {
    // Dot is regex-special; in glob it must match a literal dot only.
    var p = Glob.toRegex("a.b");
    assertThat(p.matcher("a.b").matches()).isTrue();
    assertThat(p.matcher("aXb").matches()).isFalse();
  }

  @Test
  void plusAndBracketAreEscaped() {
    var p = Glob.toRegex("group[+]");
    assertThat(p.matcher("group[+]").matches()).isTrue();
    assertThat(p.matcher("group+").matches()).isFalse();
  }

  @Test
  void nullGlobRejected() {
    assertThatThrownBy(() -> Glob.toRegex(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyGlobRejected() {
    assertThatThrownBy(() -> Glob.toRegex("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wildcardOnlyMatchesEverything() {
    var p = Glob.toRegex("*");
    assertThat(p.matcher("").matches()).isTrue();
    assertThat(p.matcher("anything").matches()).isTrue();
    assertThat(p.matcher("a.b.c").matches()).isTrue();
  }
}
