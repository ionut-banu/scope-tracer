package com.ionutbanu.scopetracer.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskNameDeriver}. Sibling of {@link ScopeNameDeriverTest} — exercises the
 * agent's task-name derivation outside any ByteBuddy/agent setup.
 */
class TaskNameDeriverTest {

  @Test
  void realCallableClassReturnsSimpleName() {
    assertThat(TaskNameDeriver.derive(new MyTask())).isEqualTo("MyTask");
  }

  @Test
  void lambdaCallableFallsBackToCallerFrame() {
    Callable<String> lambda = () -> "v";
    String name = TaskNameDeriver.derive(lambda);
    // Caller is this test method — TaskNameDeriverTest#lambdaCallableFallsBackToCallerFrame.
    assertThat(name).startsWith("TaskNameDeriverTest#");
    assertThat(name).doesNotContain("lambda$");
  }

  @Test
  void nullCallableFallsThroughToCallerFrame() {
    String name = TaskNameDeriver.derive(null);
    assertThat(name).startsWith("TaskNameDeriverTest#");
  }

  /** Real (non-lambda) Callable used for the class-name tier. */
  private static final class MyTask implements Callable<String> {
    @Override
    public String call() {
      return "x";
    }
  }
}
