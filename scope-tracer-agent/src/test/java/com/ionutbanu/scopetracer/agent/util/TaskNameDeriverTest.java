package com.ionutbanu.scopetracer.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodType;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskNameDeriver}. Sibling of {@link ScopeNameDeriverTest} — exercises the
 * agent's task-name derivation outside any ByteBuddy/agent setup.
 */
class TaskNameDeriverTest {

  private static final String SELF = "com.ionutbanu.scopetracer.agent.util.TaskNameDeriverTest";

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

  // --- the structured call site (#86), with the binary class name, uncut (#100) ---

  @Test
  void lambdaCallSiteIsTheCallingFrame() {
    Callable<String> lambda = () -> "v";
    var callSite = TaskNameDeriver.deriveCallSite(lambda);
    assertThat(callSite.className()).isEqualTo(SELF);
    assertThat(callSite.methodName()).isEqualTo("lambdaCallSiteIsTheCallingFrame");
    assertThat(callSite.line()).isPositive();
    assertThat(TaskNameDeriver.format(callSite))
        .isEqualTo("TaskNameDeriverTest#lambdaCallSiteIsTheCallingFrame:" + callSite.line());
  }

  @Test
  void methodReferenceCallSiteIsTheCallingFrame() {
    Callable<String> ref = this::value;
    var callSite = TaskNameDeriver.deriveCallSite(ref);
    assertThat(callSite.className()).isEqualTo(SELF);
    assertThat(callSite.methodName()).isEqualTo("methodReferenceCallSiteIsTheCallingFrame");
    assertThat(callSite.line()).isPositive();
  }

  @Test
  void namedNestedCallableHasItsBinaryNameAndNoMethodOrLine() {
    var callSite = TaskNameDeriver.deriveCallSite(new MyTask());
    assertThat(callSite.className()).isEqualTo(SELF + "$MyTask");
    assertThat(callSite.methodName()).isNull();
    assertThat(callSite.line()).isZero();
    assertThat(TaskNameDeriver.format(callSite)).isEqualTo("MyTask");
  }

  @Test
  void namedTopLevelCallableHasItsQualifiedName() {
    var callSite = TaskNameDeriver.deriveCallSite(new AgentTopLevelTask());
    assertThat(callSite.className())
        .isEqualTo("com.ionutbanu.scopetracer.agent.util.AgentTopLevelTask");
    assertThat(TaskNameDeriver.format(callSite)).isEqualTo("AgentTopLevelTask");
  }

  @Test
  void forkInsideNestedClassRecordsTheNestedBinaryName() {
    var callSite = Inner.derive();
    assertThat(callSite.className()).isEqualTo(SELF + "$Inner");
    assertThat(callSite.methodName()).isEqualTo("derive");
    assertThat(TaskNameDeriver.format(callSite))
        .isEqualTo("TaskNameDeriverTest#derive:" + callSite.line());
  }

  @Test
  void forkInsideAnonymousClassRecordsTheAnonymousBinaryName() throws Exception {
    Callable<TaskNameDeriver.CallSite> anonymous =
        new Callable<>() {
          @Override
          public TaskNameDeriver.CallSite call() {
            return TaskNameDeriver.deriveCallSite(() -> "v");
          }
        };
    var callSite = anonymous.call();
    assertThat(callSite.className()).isEqualTo(SELF + "$1");
    assertThat(callSite.methodName()).isEqualTo("call");
  }

  @Test
  void callerFrameInTheUnnamedPackageHasNoDot() {
    var callSite = TaskNameDeriver.toCallSite(new FakeFrame("Traced", "lambda$main$0", 7));
    assertThat(callSite.className()).isEqualTo("Traced");
    assertThat(callSite.methodName()).isEqualTo("main");
    assertThat(callSite.line()).isEqualTo(7);
    assertThat(TaskNameDeriver.format(callSite)).isEqualTo("Traced#main:7");
  }

  @Test
  void underivableCallSiteIsNull() {
    assertThat(TaskNameDeriver.fromFrames(Stream.<StackWalker.StackFrame>empty())).isNull();
    assertThat(
            TaskNameDeriver.fromFrames(
                Stream.of(
                    new FakeFrame("java.util.concurrent.StructuredTaskScopeImpl", "fork", 1),
                    new FakeFrame(
                        "com.ionutbanu.scopetracer.agent.util.TaskNameDeriver", "derive", 2))))
        .isNull();
    assertThat(TaskNameDeriver.format(null)).isNull();
  }

  private String value() {
    return "m";
  }

  /** Forks from a method of a static nested class. */
  private static final class Inner {
    static TaskNameDeriver.CallSite derive() {
      return TaskNameDeriver.deriveCallSite(() -> "i");
    }
  }

  /** Real (non-lambda) Callable used for the class-name tier. */
  private static final class MyTask implements Callable<String> {
    @Override
    public String call() {
      return "x";
    }
  }

  /** A stack frame with a chosen class, method and line, for names no real class can have. */
  private record FakeFrame(String getClassName, String getMethodName, int getLineNumber)
      implements StackWalker.StackFrame {

    @Override
    public Class<?> getDeclaringClass() {
      throw new UnsupportedOperationException();
    }

    @Override
    public MethodType getMethodType() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getDescriptor() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getByteCodeIndex() {
      return 0;
    }

    @Override
    public String getFileName() {
      return null;
    }

    @Override
    public boolean isNativeMethod() {
      return false;
    }

    @Override
    public StackTraceElement toStackTraceElement() {
      throw new UnsupportedOperationException();
    }
  }
}

/** A named {@code Callable} that is a top-level class, so its binary name has no {@code $}. */
final class AgentTopLevelTask implements Callable<String> {
  @Override
  public String call() {
    return "t";
  }
}
