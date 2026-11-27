package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.plugin.model.TaskCallSites.Lookup;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Timeline click's join (#190): a {@code (scopeId, taskId)} from the page to the task's call
 * site, from the plugin's own parsed model and {@link CallSiteParser#forTask}.
 */
class TaskCallSitesTest {

  private static final CallSite STRUCTURED = new CallSite("com.acme.OrderService", "checkout", 42);

  private static PluginTaskRecord task(long id, String name, CallSite callSite) {
    return new PluginTaskRecord(id, name, "vt-" + id, id, null, null, null, callSite);
  }

  private static PluginScopeRecord scope(long id, String name, PluginTaskRecord... tasks) {
    return new PluginScopeRecord(id, name, "main", 1, null, null, null, List.of(tasks));
  }

  private static final PluginTraceModel MODEL =
      new PluginTraceModel(
          List.of(
              scope(
                  1,
                  "checkout",
                  task(10, "OrderService#checkout:42", STRUCTURED),
                  task(11, "Legacy#run:7", null),
                  task(12, "fetch-user", null),
                  task(13, "fetchUser", null)),
              scope(2, "payment", task(20, "Payment#pay:9", null))));

  @Test
  void aTaskWithAStructuredCallSiteFindsItExact() {
    var lookup = TaskCallSites.of(MODEL).find(new TaskClick(1, 10));

    assertThat(lookup)
        .isEqualTo(new Lookup.Found(CallSite.exact("com.acme.OrderService", "checkout", 42)));
  }

  @Test
  void aTaskFromAnOlderRecordingFindsItsParsedShortNameCallSite() {
    var lookup = TaskCallSites.of(MODEL).find(new TaskClick(1, 11));

    assertThat(lookup).isEqualTo(new Lookup.Found(new CallSite("Legacy", "run", 7)));
    assertThat(((Lookup.Found) lookup).callSite().exact()).isFalse();
  }

  @Test
  void aTaskWithNeitherARecordedNorAParseableCallSiteHasNone() {
    var callSites = TaskCallSites.of(MODEL);

    assertThat(callSites.find(new TaskClick(1, 12)))
        .isEqualTo(new Lookup.NoCallSite(12, "fetch-user"));
    assertThat(callSites.find(new TaskClick(1, 13)))
        .isEqualTo(new Lookup.NoCallSite(13, "fetchUser"));
  }

  @Test
  void anUnknownScopeIdFindsNothing() {
    assertThat(TaskCallSites.of(MODEL).find(new TaskClick(99, 10)))
        .isEqualTo(Lookup.Unknown.INSTANCE);
  }

  @Test
  void anUnknownTaskIdFindsNothing() {
    assertThat(TaskCallSites.of(MODEL).find(new TaskClick(1, 99)))
        .isEqualTo(Lookup.Unknown.INSTANCE);
  }

  @Test
  void aTaskIdOfAnotherScopeFindsNothing() {
    var callSites = TaskCallSites.of(MODEL);

    assertThat(callSites.find(new TaskClick(1, 20))).isEqualTo(Lookup.Unknown.INSTANCE);
    assertThat(callSites.find(new TaskClick(2, 10))).isEqualTo(Lookup.Unknown.INSTANCE);
    assertThat(callSites.find(new TaskClick(2, 20)))
        .isEqualTo(new Lookup.Found(new CallSite("Payment", "pay", 9)));
  }

  @Test
  void twoScopesThatShareANameAreToldApartByTheirIds() {
    var first = new CallSite("com.acme.First", "a", 1);
    var second = new CallSite("com.acme.Second", "b", 2);
    var model =
        new PluginTraceModel(
            List.of(
                scope(5, "order", task(1, "First#a:1", first)),
                scope(6, "order", task(1, "Second#b:2", second))));
    var callSites = TaskCallSites.of(model);

    assertThat(callSites.find(new TaskClick(5, 1))).isEqualTo(new Lookup.Found(first.asExact()));
    assertThat(callSites.find(new TaskClick(6, 1))).isEqualTo(new Lookup.Found(second.asExact()));
  }

  @Test
  void anEmptyModelFindsNothing() {
    assertThat(TaskCallSites.EMPTY.find(new TaskClick(1, 10))).isEqualTo(Lookup.Unknown.INSTANCE);
    assertThat(TaskCallSites.of(new PluginTraceModel(List.of())).find(new TaskClick(1, 10)))
        .isEqualTo(Lookup.Unknown.INSTANCE);
  }
}
