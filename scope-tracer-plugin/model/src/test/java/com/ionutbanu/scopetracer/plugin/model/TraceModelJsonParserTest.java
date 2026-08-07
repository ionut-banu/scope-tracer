package com.ionutbanu.scopetracer.plugin.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TraceModelJsonParserTest {

  @Test
  void parsesSuccessFailedAndCancelledOutcomes() {
    var json =
        """
        {"scopes":[
          {"scopeId":1,"name":"checkout","ownerThreadName":"main","ownerThreadId":1,
           "openTime":"2026-01-01T00:00:00Z","closeTime":"2026-01-01T00:00:01Z","parent":null,
           "tasks":[
             {"taskId":1,"taskName":"pricing","threadName":"vt-1","threadId":10,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":"2026-01-01T00:00:00.300Z",
              "outcome":{"type":"success"}},
             {"taskId":2,"taskName":"inventory","threadName":"vt-2","threadId":11,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":"2026-01-01T00:00:00.900Z",
              "outcome":{"type":"failed","exceptionType":"java.lang.RuntimeException",
                         "exceptionMessage":"out of stock","stackTrace":"..."}},
             {"taskId":3,"taskName":"fraudCheck","threadName":"vt-3","threadId":12,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":"2026-01-01T00:00:00.910Z",
              "outcome":{"type":"cancelled"}}
           ]}
        ]}
        """;

    var model = TraceModelJsonParser.parse(json);

    assertThat(model.scopes()).hasSize(1);
    var scope = model.scopes().get(0);
    assertThat(scope.scopeId()).isEqualTo(1L);
    assertThat(scope.name()).isEqualTo("checkout");
    assertThat(scope.openTime()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(scope.parent()).isNull();
    assertThat(scope.tasks()).hasSize(3);

    assertThat(scope.tasks().get(0).outcome()).isEqualTo(new PluginTaskOutcome.Success());

    var failed = (PluginTaskOutcome.Failed) scope.tasks().get(1).outcome();
    assertThat(failed.exceptionType()).isEqualTo("java.lang.RuntimeException");
    assertThat(failed.exceptionMessage()).isEqualTo("out of stock");

    assertThat(scope.tasks().get(2).outcome()).isEqualTo(new PluginTaskOutcome.Cancelled());
  }

  @Test
  void parsesNestedScopeWithParentRef() {
    var json =
        """
        {"scopes":[
          {"scopeId":2,"name":"payment","ownerThreadName":"vt-1","ownerThreadId":10,
           "openTime":"2026-01-01T00:00:00.150Z","closeTime":"2026-01-01T00:00:00.280Z",
           "parent":{"parentScopeId":1,"scopeName":"checkout","taskId":1},
           "tasks":[]}
        ]}
        """;

    var model = TraceModelJsonParser.parse(json);

    var scope = model.scopes().get(0);
    assertThat(scope.parent()).isEqualTo(new PluginParentRef(1L, "checkout", 1L));
    assertThat(scope.tasks()).isEmpty();
  }

  @Test
  void treatsNullOutcomeAndTimesAsIncompleteRecording() {
    var json =
        """
        {"scopes":[
          {"scopeId":3,"name":"truncated","ownerThreadName":"main","ownerThreadId":1,
           "openTime":"2026-01-01T00:00:00Z","closeTime":null,"parent":null,
           "tasks":[
             {"taskId":1,"taskName":null,"threadName":"vt-1","threadId":10,
              "forkTime":"2026-01-01T00:00:00.100Z","completionTime":null,"outcome":null}
           ]}
        ]}
        """;

    var model = TraceModelJsonParser.parse(json);

    var scope = model.scopes().get(0);
    assertThat(scope.closeTime()).isNull();
    var task = scope.tasks().get(0);
    assertThat(task.taskName()).isNull();
    assertThat(task.completionTime()).isNull();
    assertThat(task.outcome()).isNull();
  }
}
