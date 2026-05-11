package com.ionutbanu.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class TaskNameDeriverTest {

  @Test
  void realClassReturnsSimpleName() {
    assertThat(TaskNameDeriver.derive(new MyTask())).isEqualTo("MyTask");
  }

  @Test
  void lambdaFallsBackToCallerFrame() {
    Callable<String> lambda = () -> "v";
    String name = TaskNameDeriver.derive(lambda);
    // Caller of TaskNameDeriver.derive is this test method — TaskNameDeriverTest.
    assertThat(name).startsWith("TaskNameDeriverTest#");
  }

  @Test
  void nullCallableReturnsCallerFrameNotNull() {
    // null is rare in practice (TracedScope.fork would NPE elsewhere) but the deriver must not
    // throw. fromClass returns null, so we fall through to the caller frame.
    String name = TaskNameDeriver.derive(null);
    assertThat(name).startsWith("TaskNameDeriverTest#");
  }

  /** Fixture: a real (non-lambda) Callable used by {@link #realClassReturnsSimpleName()}. */
  private static final class MyTask implements Callable<String> {
    @Override
    public String call() {
      return "x";
    }
  }
}
