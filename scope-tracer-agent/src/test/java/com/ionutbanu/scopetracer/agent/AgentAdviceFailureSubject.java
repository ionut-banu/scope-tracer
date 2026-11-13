package com.ionutbanu.scopetracer.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Makes {@code ScopeOpenAdvice} throw inside a woven {@code open} and checks, in the traced JVM,
 * that every {@code open} overload still returns a working scope and throws what it would throw
 * untraced (spec §1, "Tracing never changes behaviour"). Run by {@link ScopeTracerAgentIT} with the
 * agent attached; exits non-zero if any check fails, and writes the recording to {@code args[0]}.
 *
 * <p>The failure is made by setting {@link AgentState#FILTER} to {@code null}: the advice
 * dereferences it for every scope, so each run of the advice ends in a {@link NullPointerException}
 * that {@code suppress = Throwable.class} must swallow. The test subject sees the agent's own
 * {@code AgentState}: the class is on the bootstrap class path, which the application class loader
 * delegates to first. The filter is restored before {@value #AFTER_RESTORE_SCOPE} is opened, which
 * must be traced as usual.
 */
public final class AgentAdviceFailureSubject {

  static final String NAMED_DURING_FAILURE = "advice-failure-named";
  static final String AFTER_RESTORE_SCOPE = "advice-failure-after-restore";

  private AgentAdviceFailureSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      CaptureFilter saved = AgentState.FILTER;
      AgentState.FILTER = null;
      try {
        try (var scope = StructuredTaskScope.open()) {
          var a = scope.fork(() -> "a");
          var b = scope.fork(() -> "b");
          check(scope.join() == null, "open().join() returns null");
          check("a".equals(a.get()) && "b".equals(b.get()), "open() subtasks keep their results");
        }

        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
          scope.fork(() -> "only");
          check("only".equals(scope.join()), "open(Joiner).join() returns the joiner's result");
        }

        try (var scope = StructuredTaskScope.open(c -> c.withName(NAMED_DURING_FAILURE))) {
          var a = scope.fork(() -> "a");
          check(scope.join() == null, "open(UnaryOperator).join() returns null");
          check("a".equals(a.get()), "open(UnaryOperator) subtasks keep their results");
        }

        try (var scope =
            StructuredTaskScope.open(
                Joiner.<String>allSuccessfulOrThrow(), c -> c.withName(NAMED_DURING_FAILURE))) {
          scope.fork(() -> "x");
          scope.fork(() -> "y");
          check(
              List.of("x", "y").equals(scope.join()),
              "open(Joiner, UnaryOperator).join() returns the joiner's result");
        }

        checkOpenThrowsTheConfigFunctionsException();
      } finally {
        AgentState.FILTER = saved;
      }

      checkOpenThrowsTheConfigFunctionsException();

      try (var scope =
          StructuredTaskScope.open(
              Joiner.<String>awaitAllSuccessfulOrThrow(), c -> c.withName(AFTER_RESTORE_SCOPE))) {
        scope.fork(() -> "traced");
        scope.join();
      }

      recording.stop();
      recording.dump(jfrOut);
    }
  }

  /**
   * A configuration function that throws: {@code open(Joiner, UnaryOperator)} and {@code
   * open(UnaryOperator)} must throw that very exception.
   */
  private static void checkOpenThrowsTheConfigFunctionsException() {
    var boom = new IllegalStateException("configuration failed");
    try {
      StructuredTaskScope.open(
              Joiner.allUntil(subtask -> false),
              c -> {
                throw boom;
              })
          .close();
      throw new AssertionError("open returned although its configuration function threw");
    } catch (IllegalStateException e) {
      check(e == boom, "open throws the configuration function's exception, got " + e);
    }
    try {
      StructuredTaskScope.open(
              c -> {
                throw boom;
              })
          .close();
      throw new AssertionError(
          "open(UnaryOperator) returned although its configuration function threw");
    } catch (IllegalStateException e) {
      check(
          e == boom, "open(UnaryOperator) throws the configuration function's exception, got " + e);
    }
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new AssertionError("check failed: " + what);
    }
  }
}
