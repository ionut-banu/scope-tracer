package com.ionutbanu.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodType;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TaskNameDeriverTest {

  private static final String SELF = "com.ionutbanu.scopetracer.core.TaskNameDeriverTest";

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

  @Test
  void deriveCallSiteForRealClassHasNoMethodOrLine() {
    var info = TaskNameDeriver.deriveCallSite(new MyTask());
    assertThat(info.className()).isEqualTo(SELF + "$MyTask");
    assertThat(info.methodName()).isNull();
    assertThat(info.line()).isZero();
  }

  @Test
  void deriveCallSiteForLambdaCapturesCallerFrame() {
    Callable<String> lambda = () -> "v";
    var info = TaskNameDeriver.deriveCallSite(lambda);
    assertThat(info.className()).isEqualTo(SELF);
    assertThat(info.methodName()).isEqualTo("deriveCallSiteForLambdaCapturesCallerFrame");
    assertThat(info.line()).isPositive();
  }

  // --- callSiteClassName is the binary class name, uncut (#100) ---

  @Test
  void callSiteOfMethodReferenceIsTheBinaryNameOfTheCallingClass() {
    Callable<String> ref = this::value;
    var info = TaskNameDeriver.deriveCallSite(ref);
    assertThat(info.className()).isEqualTo(SELF);
    assertThat(info.methodName())
        .isEqualTo("callSiteOfMethodReferenceIsTheBinaryNameOfTheCallingClass");
    assertThat(info.line()).isPositive();
    assertThat(TaskNameDeriver.format(info))
        .isEqualTo(
            "TaskNameDeriverTest#callSiteOfMethodReferenceIsTheBinaryNameOfTheCallingClass:"
                + info.line());
  }

  @Test
  void callSiteOfTopLevelCallableIsItsQualifiedName() {
    var info = TaskNameDeriver.deriveCallSite(new TopLevelTask());
    assertThat(info.className()).isEqualTo("com.ionutbanu.scopetracer.core.TopLevelTask");
    assertThat(TaskNameDeriver.format(info)).isEqualTo("TopLevelTask");
  }

  @Test
  void callSiteOfNestedCallableIsItsBinaryNameButTheLabelStaysSimple() {
    var info = TaskNameDeriver.deriveCallSite(new MyTask());
    assertThat(info.className()).isEqualTo(SELF + "$MyTask");
    assertThat(TaskNameDeriver.format(info)).isEqualTo("MyTask");
  }

  @Test
  void callSiteOfLocalCallableIsItsBinaryName() {
    final class LocalTask implements Callable<String> {
      @Override
      public String call() {
        return "l";
      }
    }
    var info = TaskNameDeriver.deriveCallSite(new LocalTask());
    assertThat(info.className()).isEqualTo(SELF + "$1LocalTask");
    assertThat(info.methodName()).isNull();
    assertThat(info.line()).isZero();
    assertThat(TaskNameDeriver.format(info)).isEqualTo("LocalTask");
  }

  @Test
  void forkInsideNestedClassRecordsTheNestedBinaryName() {
    var info = Inner.derive();
    assertThat(info.className()).isEqualTo(SELF + "$Inner");
    assertThat(info.methodName()).isEqualTo("derive");
    assertThat(info.line()).isPositive();
    // The label keeps the outer simple name, as before.
    assertThat(TaskNameDeriver.format(info)).isEqualTo("TaskNameDeriverTest#derive:" + info.line());
  }

  @Test
  void forkInsideAnonymousClassRecordsTheAnonymousBinaryName() throws Exception {
    Callable<TaskNameDeriver.CallSiteInfo> anonymous =
        new Callable<>() {
          @Override
          public TaskNameDeriver.CallSiteInfo call() {
            return TaskNameDeriver.deriveCallSite(() -> "v");
          }
        };
    var info = anonymous.call();
    assertThat(info.className()).isEqualTo(SELF + "$1");
    assertThat(info.methodName()).isEqualTo("call");
    assertThat(TaskNameDeriver.format(info)).isEqualTo("TaskNameDeriverTest#call:" + info.line());
  }

  @Test
  void anonymousCallableUsesTheCallerFrameTier() {
    var info =
        TaskNameDeriver.deriveCallSite(
            new Callable<String>() {
              @Override
              public String call() {
                return "a";
              }
            });
    assertThat(info.className()).isEqualTo(SELF);
    assertThat(info.methodName()).isEqualTo("anonymousCallableUsesTheCallerFrameTier");
  }

  @Test
  void callerFrameInTheUnnamedPackageHasNoDot() {
    var info = TaskNameDeriver.toCallSiteInfo(new FakeFrame("Traced", "lambda$main$0", 7));
    assertThat(info.className()).isEqualTo("Traced");
    assertThat(info.methodName()).isEqualTo("main");
    assertThat(info.line()).isEqualTo(7);
    assertThat(TaskNameDeriver.format(info)).isEqualTo("Traced#main:7");
  }

  @Test
  void callerFrameOfNestedClassInTheUnnamedPackageKeepsTheDollar() {
    var info = TaskNameDeriver.toCallSiteInfo(new FakeFrame("Plain$Named", "run", 3));
    assertThat(info.className()).isEqualTo("Plain$Named");
    assertThat(TaskNameDeriver.format(info)).isEqualTo("Plain#run:3");
  }

  @Test
  void noUsableFrameGivesNoCallSite() {
    assertThat(TaskNameDeriver.fromFrames(Stream.<StackWalker.StackFrame>empty())).isNull();
    assertThat(
            TaskNameDeriver.fromFrames(
                Stream.of(
                    new FakeFrame("com.ionutbanu.scopetracer.core.TracedScope", "fork", 1),
                    new FakeFrame("java.util.concurrent.StructuredTaskScopeImpl", "fork", 2))))
        .isNull();
    assertThat(TaskNameDeriver.format(null)).isNull();
  }

  private String value() {
    return "m";
  }

  /** Forks from a method of a static nested class. */
  private static final class Inner {
    static TaskNameDeriver.CallSiteInfo derive() {
      return TaskNameDeriver.deriveCallSite(() -> "i");
    }
  }

  /** Fixture: a real (non-lambda) Callable used by {@link #realClassReturnsSimpleName()}. */
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
final class TopLevelTask implements Callable<String> {
  @Override
  public String call() {
    return "t";
  }
}
