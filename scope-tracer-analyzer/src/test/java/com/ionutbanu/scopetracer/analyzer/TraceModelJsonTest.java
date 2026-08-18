package com.ionutbanu.scopetracer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.ionutbanu.scopetracer.analyzer.model.CallSite;
import com.ionutbanu.scopetracer.analyzer.model.ScopeRecord;
import com.ionutbanu.scopetracer.analyzer.model.TaskOutcome;
import com.ionutbanu.scopetracer.analyzer.model.TaskRecord;
import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceModelJsonTest {

  private static final Instant T0 = Instant.parse("2024-01-01T10:00:00.000Z");
  private static final Instant T1 = T0.plusMillis(10);
  private static final Instant T2 = T0.plusMillis(20);

  @Test
  void emptyModelProducesScopesArray() {
    var json = TraceModelJson.toJson(new TraceModel(List.of()));
    assertThat(json).isEqualTo("{\"scopes\":[]}");
  }

  @Test
  void successOutcomeSerialisedAsTypeSuccess() {
    var json = TraceModelJson.toJson(singleTaskModel(new TaskOutcome.Success()));
    assertThat(json).contains("\"outcome\":{\"type\":\"success\"}");
  }

  @Test
  void failedOutcomeIncludesExceptionFields() {
    var json =
        TraceModelJson.toJson(
            singleTaskModel(
                new TaskOutcome.Failed(
                    "java.io.IOException", "disk full", "  at Foo.bar(Foo.java:1)\n")));
    assertThat(json).contains("\"type\":\"failed\"");
    assertThat(json).contains("\"exceptionType\":\"java.io.IOException\"");
    assertThat(json).contains("\"exceptionMessage\":\"disk full\"");
  }

  @Test
  void nullFieldsAreSerialisedAsJsonNull() {
    var tasks = List.of(new TaskRecord(1, null, "worker", -1L, T1, null, null));
    var scope = new ScopeRecord(1L, "scope", "main", -1L, T0, null, tasks, null);
    var json = TraceModelJson.toJson(new TraceModel(List.of(scope)));
    assertThat(json).contains("\"taskName\":null");
    assertThat(json).contains("\"completionTime\":null");
    assertThat(json).contains("\"outcome\":null");
    assertThat(json).contains("\"closeTime\":null");
    assertThat(json).contains("\"parent\":null");
    assertThat(json).contains("\"callSite\":null");
  }

  @Test
  void callSiteSerialisedWhenPresent() {
    var tasks =
        List.of(
            new TaskRecord(
                1,
                "findUser",
                "worker",
                -1L,
                T1,
                T2,
                new TaskOutcome.Success(),
                new CallSite("OrderService", "checkout", 42)));
    var scope = new ScopeRecord(1L, "scope", "main", -1L, T0, T2, tasks, null);
    var json = TraceModelJson.toJson(new TraceModel(List.of(scope)));
    assertThat(json).contains("\"callSite\":{\"className\":\"OrderService\"");
    assertThat(json).contains("\"methodName\":\"checkout\"");
    assertThat(json).contains("\"line\":42");
  }

  @Test
  void scriptTerminatorIsEscapedToPreventInlineBreakout() {
    // A naïve embedding would allow exceptionMessage to break out of <script> via "</script>".
    // The escaper escapes forward slashes so the embedded JSON cannot terminate its host script.
    var json =
        TraceModelJson.toJson(
            singleTaskModel(
                new TaskOutcome.Failed("X", "boom</script><script>alert(1)</script>", null)));
    assertThat(json).doesNotContain("</script>");
  }

  @Test
  void parentRefSerialisedWhenPresent() {
    var parentRef = new ScopeRecord.ParentRef(7L, "outer", 3L);
    var scope = new ScopeRecord(2L, "inner", "vt-1", 1L, T0, T2, List.of(), parentRef);
    var json = TraceModelJson.toJson(new TraceModel(List.of(scope)));
    assertThat(json).contains("\"parent\":{\"parentScopeId\":7");
    assertThat(json).contains("\"scopeName\":\"outer\"");
    assertThat(json).contains("\"taskId\":3");
  }

  private static TraceModel singleTaskModel(TaskOutcome outcome) {
    var tasks = List.of(new TaskRecord(1, "doWork", "worker", -1L, T1, T2, outcome));
    var scope = new ScopeRecord(1L, "scope", "main", -1L, T0, T2, tasks, null);
    return new TraceModel(List.of(scope));
  }
}
