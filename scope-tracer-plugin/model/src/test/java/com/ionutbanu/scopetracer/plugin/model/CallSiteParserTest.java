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

    assertThat(CallSiteParser.forTask(task)).isEqualTo(callSite);
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
}
