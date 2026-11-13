package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallSiteParserTest {

  @Test
  void parsesClassMethodAndLine() {
    var callSite = CallSiteParser.parse("OrderProcessingDemo#processOrder:106");

    assertThat(callSite).isEqualTo(new CallSite("OrderProcessingDemo", "processOrder", 106));
  }

  @Test
  void parsesBareClassName() {
    var callSite = CallSiteParser.parse("FindUserTask");

    assertThat(callSite).isEqualTo(new CallSite("FindUserTask", null, null));
  }

  @Test
  void parsesClassAndMethodWithoutLine() {
    var callSite = CallSiteParser.parse("OrderService#checkout");

    assertThat(callSite).isEqualTo(new CallSite("OrderService", "checkout", null));
  }

  @Test
  void returnsNullForNullInput() {
    assertThat(CallSiteParser.parse(null)).isNull();
  }

  @Test
  void returnsNullForExplicitScopeNameWithHyphensAndDigits() {
    assertThat(CallSiteParser.parse("order-processing-ORD-001")).isNull();
  }

  @Test
  void returnsNullForMalformedLineSuffix() {
    assertThat(CallSiteParser.parse("OrderService#checkout:notanumber")).isNull();
  }

  @Test
  void returnsNullForLowercaseExplicitLabel() {
    // scope.fork("validateOrder", () -> ...) — an explicit hand-picked label (README's
    // documented fork(String, Callable) overload), not a class name. Real class names are
    // PascalCase by convention; explicit labels are near-universally camelCase, so a
    // lowercase-starting bare identifier is treated as unparseable rather than a doomed PSI
    // lookup that would misleadingly report "no class found".
    assertThat(CallSiteParser.parse("validateOrder")).isNull();
  }

  @Test
  void forTaskPrefersStructuredCallSiteOverParsingTaskName() {
    var callSite = new CallSite("OrderService", "checkout", 42);
    var task = new PluginTaskRecord(1, "findUser", "vt-1", 10, null, null, null, callSite);

    assertThat(CallSiteParser.forTask(task)).isEqualTo(CallSite.exact("OrderService", "checkout", 42));
  }

  @Test
  void forTaskFallsBackToParsingTaskNameWhenCallSiteAbsent() {
    var task = new PluginTaskRecord(1, "OrderService#checkout:42", "vt-1", 10, null, null, null, null);

    assertThat(CallSiteParser.forTask(task)).isEqualTo(new CallSite("OrderService", "checkout", 42));
  }

  @Test
  void forTaskReturnsNullWhenNeitherSourceResolves() {
    // Legacy recording with an explicit hand-picked label and no structured callSite — the gap
    // this feature fixes for new recordings, still unresolvable for old ones.
    var task = new PluginTaskRecord(1, "validateOrder", "vt-1", 10, null, null, null, null);

    assertThat(CallSiteParser.forTask(task)).isNull();
  }

  // --- exact (structured) versus short-name call sites (#100) ---

  @Test
  void forTaskMarksAStructuredCallSiteExactAndKeepsItsBinaryName() {
    var structured = CallSite.exact("com.acme.Plain$Named", null, null);
    var task = new PluginTaskRecord(1, "Named", "vt-1", 10, null, null, null, structured);

    var callSite = CallSiteParser.forTask(task);

    assertThat(callSite.exact()).isTrue();
    assertThat(callSite.className()).isEqualTo("com.acme.Plain$Named");
  }

  @Test
  void forTaskWithoutStructuredCallSiteGivesAShortName() {
    var task = new PluginTaskRecord(1, "OrderService#checkout:42", "vt-1", 10, null, null, null, null);

    var callSite = CallSiteParser.forTask(task);

    assertThat(callSite.exact()).isFalse();
    assertThat(callSite.lookupNames()).isEmpty();
  }

  @Test
  void structuredCallSiteInTheUnnamedPackageIsStillExact() {
    // No dot in the name: exactness comes from where the call site came from, not its shape.
    var task =
        new PluginTaskRecord(
            1, "Traced#main:7", "vt-1", 10, null, null, null, CallSite.exact("Traced", "main", 7));

    var callSite = CallSiteParser.forTask(task);

    assertThat(callSite.exact()).isTrue();
    assertThat(callSite.lookupNames()).containsExactly("Traced");
  }

  @Test
  void topLevelClassIsLookedUpByItsQualifiedName() {
    assertThat(CallSite.exact("com.acme.Traced", "main", 7).lookupNames())
        .containsExactly("com.acme.Traced");
  }

  @Test
  void nestedClassIsLookedUpExactly() {
    assertThat(CallSite.exact("com.acme.Plain$Named", null, null).lookupNames())
        .containsExactly("com.acme.Plain.Named", "com.acme.Plain$Named");
  }

  @Test
  void anonymousAndLocalClassesFallBackToTheEnclosingNamedClass() {
    assertThat(CallSite.exact("com.acme.Plain$1", "call", 12).lookupNames())
        .containsExactly("com.acme.Plain");
    assertThat(CallSite.exact("com.acme.Plain$1Local", null, null).lookupNames())
        .containsExactly("com.acme.Plain");
    assertThat(CallSite.exact("com.acme.Outer$Inner$2", "call", 5).lookupNames())
        .containsExactly("com.acme.Outer.Inner", "com.acme.Outer$Inner");
    assertThat(CallSite.exact("com.acme.Outer$1Local$Deep", "run", 9).lookupNames())
        .containsExactly("com.acme.Outer");
  }
}
