package com.ionutbanu.scopetracer.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compile-level checks of {@link TracedScope}'s typing (#95, #87, #80). The positive cases are this
 * class's own source: if a typed joiner, a factory form or a {@code fork} were typed differently
 * from {@link StructuredTaskScope}, this file would not compile and the build would fail. Each also
 * runs. The negative cases (code that must not compile) are compiled in-process with the JDK's
 * {@code javac} against {@code TracedScope}'s classes, and the test asserts the compiler's error.
 */
class TracedScopeTypingTest {

  @TempDir Path out;

  // --- typed joiners, no casts ---

  @Test
  void allSuccessfulOrThrowJoinsToAListOfT() throws Exception {
    try (var scope = TracedScope.open("t", Joiner.<String>allSuccessfulOrThrow())) {
      scope.fork(() -> "a");
      scope.fork(() -> "b");
      List<String> results = scope.join();
      assertThat(results).containsExactly("a", "b");
    }
  }

  @Test
  void anySuccessfulOrThrowJoinsToT() throws Exception {
    try (var scope = TracedScope.open("t", Joiner.<String>anySuccessfulOrThrow())) {
      scope.fork(() -> "a");
      String result = scope.join();
      assertThat(result).isEqualTo("a");
    }
  }

  @Test
  void allUntilJoinsToAListOfSubtasksOfT() throws Exception {
    try (var scope = TracedScope.open("t", Joiner.<Integer>allUntil(subtask -> false))) {
      scope.fork(() -> 1);
      List<Subtask<Integer>> subtasks = scope.join();
      assertThat(subtasks).singleElement().extracting(Subtask::get).isEqualTo(1);
    }
  }

  @Test
  void awaitAllSuccessfulOrThrowJoinsToVoid() throws Exception {
    try (var scope = TracedScope.open("t", Joiner.<String>awaitAllSuccessfulOrThrow())) {
      Subtask<String> subtask = scope.fork(() -> "a");
      Void result = scope.join();
      assertThat(result).isNull();
      assertThat(subtask.get()).isEqualTo("a");
    }
  }

  // --- each factory form accepts what StructuredTaskScope's counterpart accepts ---

  @Test
  void openNameAcceptsWhatOpenAccepts() throws Exception {
    try (StructuredTaskScope<String, Void, ExecutionException> plain = StructuredTaskScope.open();
        TracedScope<String, Void, ExecutionException> traced = TracedScope.open("n")) {
      Subtask<String> p = plain.fork(() -> "p");
      Subtask<String> t = traced.fork(() -> "t");
      plain.join();
      traced.join();
      assertThat(p.get() + t.get()).isEqualTo("pt");
    }
  }

  /** A joiner is used by one scope only, so each scope gets its own, of the same declared type. */
  @Test
  void openNameJoinerAcceptsWhatOpenJoinerAccepts() throws Exception {
    // A Joiner<Object, ...> where T is String: Joiner<? super T, ...>.
    Joiner<Object, Void, ExecutionException> plainJoiner = Joiner.awaitAllSuccessfulOrThrow();
    Joiner<Object, Void, ExecutionException> tracedJoiner = Joiner.awaitAllSuccessfulOrThrow();
    try (StructuredTaskScope<String, Void, ExecutionException> plain =
        StructuredTaskScope.open(plainJoiner)) {
      plain.join();
    }
    try (TracedScope<String, Void, ExecutionException> traced =
        TracedScope.open("n", tracedJoiner)) {
      traced.join();
    }
    // A mapped exception: R_X is IOException.
    Joiner<String, List<String>, IOException> plainIo =
        Joiner.allSuccessfulOrThrow(cause -> new IOException(cause));
    Joiner<String, List<String>, IOException> tracedIo =
        Joiner.allSuccessfulOrThrow(cause -> new IOException(cause));
    try (StructuredTaskScope<String, List<String>, IOException> plain =
        StructuredTaskScope.open(plainIo)) {
      plain.fork(() -> "p");
      assertThat(plain.join()).containsExactly("p");
    }
    try (TracedScope<String, List<String>, IOException> traced = TracedScope.open("n", tracedIo)) {
      traced.fork(() -> "t");
      assertThat(traced.join()).containsExactly("t");
    }
  }

  @Test
  void openNameConfigAcceptsWhatOpenConfigAccepts() throws Exception {
    try (StructuredTaskScope<String, Void, ExecutionException> plain =
            StructuredTaskScope.open(cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()));
        TracedScope<String, Void, ExecutionException> traced =
            TracedScope.open("n", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
      plain.join();
      traced.join();
    }
    // #80: with var, T is inferred as Object, exactly as for StructuredTaskScope.open(cfg -> cfg).
    try (var plain = StructuredTaskScope.open(cfg -> cfg);
        var traced = TracedScope.open("n", cfg -> cfg)) {
      StructuredTaskScope<Object, Void, ExecutionException> plainTyped = plain;
      TracedScope<Object, Void, ExecutionException> tracedTyped = traced;
      plainTyped.join();
      tracedTyped.join();
    }
    // #80: with a declared type, T = String, and fork gives Subtask<String>.
    try (TracedScope<String, Void, ExecutionException> s = TracedScope.open("n", cfg -> cfg)) {
      Subtask<String> subtask = s.fork(() -> "x");
      s.join();
      assertThat(subtask.get()).isEqualTo("x");
    }
  }

  @Test
  void openNameJoinerConfigAcceptsWhatOpenJoinerConfigAccepts() throws Exception {
    // A Joiner<Object, ...> where T is String.
    Joiner<Object, List<Object>, ExecutionException> plainJoiner = Joiner.allSuccessfulOrThrow();
    Joiner<Object, List<Object>, ExecutionException> tracedJoiner = Joiner.allSuccessfulOrThrow();
    try (StructuredTaskScope<String, List<Object>, ExecutionException> plain =
        StructuredTaskScope.open(plainJoiner, cfg -> cfg)) {
      plain.fork(() -> "p");
      assertThat(plain.join()).containsExactly("p");
    }
    try (TracedScope<String, List<Object>, ExecutionException> traced =
        TracedScope.open("n", tracedJoiner, cfg -> cfg)) {
      traced.fork(() -> "t");
      assertThat(traced.join()).containsExactly("t");
    }
    // A mapped exception: R_X is IOException.
    Joiner<String, String, IOException> plainIo =
        Joiner.anySuccessfulOrThrow(cause -> new IOException(cause));
    Joiner<String, String, IOException> tracedIo =
        Joiner.anySuccessfulOrThrow(cause -> new IOException(cause));
    try (StructuredTaskScope<String, String, IOException> plain =
        StructuredTaskScope.open(plainIo, cfg -> cfg)) {
      plain.fork(() -> "p");
      assertThat(plain.join()).isEqualTo("p");
    }
    try (TracedScope<String, String, IOException> traced =
        TracedScope.open("n", tracedIo, cfg -> cfg)) {
      traced.fork(() -> "t");
      assertThat(traced.join()).isEqualTo("t");
    }
  }

  // --- fork is typed by T ---

  @Test
  void forkIsTypedByT() throws Exception {
    try (TracedScope<String, Void, ExecutionException> scope = TracedScope.open("n")) {
      Subtask<String> callable = scope.fork(() -> "x");
      Subtask<String> labelled = scope.fork("label", () -> "y");
      Subtask<String> runnable = scope.fork(() -> {});
      scope.join();
      assertThat(callable.get()).isEqualTo("x");
      assertThat(labelled.get()).isEqualTo("y");
      assertThat(runnable.get()).isNull();
    }
  }

  @Test
  void forkOfAnotherTypeDoesNotCompile() throws Exception {
    var errors =
        compile(
            """
            TracedScope<String, Void, ExecutionException> scope = TracedScope.open("n");
            scope.fork(() -> 1);
            """);
    // U would have to be a supertype of Integer and a subtype of String.
    assertThat(errors)
        .anySatisfy(
            e ->
                assertThat(e)
                    .contains("compiler.err.cant.apply.symbols: kindname.method, fork")
                    .contains("compiler.misc.incompatible.bounds: U")
                    .contains("java.lang.String")
                    .contains("java.lang.Integer"));
  }

  // --- what no longer exists (#87, #95) ---

  /** The reviewer's snippet from #87, verbatim. */
  @Test
  void reviewersConstructorSnippetDoesNotCompile() throws Exception {
    var errors =
        compile(
            """
            try (var scope = new TracedScope<Void, RuntimeException>("sneaky")) {
              scope.fork(() -> { throw new IOException("task failed"); });
              scope.join();
            }
            """);
    assertThat(errors).isNotEmpty();
  }

  @Test
  void constructorsDoNotExist() throws Exception {
    assertThat(compile("var scope = new TracedScope<Object, Void, RuntimeException>(\"x\");"))
        .anySatisfy(e -> assertThat(e).contains("kindname.constructor, TracedScope"));
    // The raw form the README shows (#85 updates the README).
    assertThat(compile("var scope = new TracedScope(\"x\");"))
        .anySatisfy(e -> assertThat(e).contains("kindname.constructor, TracedScope"));
  }

  @Test
  void undeclaredExecutionExceptionDoesNotCompile() throws Exception {
    var errors =
        compile(
            """
            try (var scope = TracedScope.open("sneaky")) {
              scope.fork(() -> { throw new IOException("task failed"); });
              scope.join();
            }
            """);
    assertThat(errors)
        .anySatisfy(
            e ->
                assertThat(e)
                    .contains(
                        "compiler.err.unreported.exception.need.to.catch.or.throw:"
                            + " java.util.concurrent.ExecutionException"));
    // The same body with the catch clause compiles.
    assertThat(
            compile(
                """
                try (var scope = TracedScope.open("sneaky")) {
                  scope.fork(() -> { throw new IOException("task failed"); });
                  scope.join();
                } catch (ExecutionException e) {
                  Throwable cause = e.getCause();
                }
                """))
        .isEmpty();
  }

  @Test
  void mappedExceptionCannotBeCaughtAsExecutionException() throws Exception {
    var errors =
        compile(
            """
            try (var scope = TracedScope.open("io",
                Joiner.<String, IOException>allSuccessfulOrThrow(c -> new IOException(c)))) {
              scope.join();
            } catch (IOException e) {
            } catch (ExecutionException e) {
            }
            """);
    assertThat(errors)
        .anySatisfy(
            e ->
                assertThat(e)
                    .contains(
                        "compiler.err.except.never.thrown.in.try:"
                            + " java.util.concurrent.ExecutionException"));
  }

  @Test
  void threadFactoryFactoriesDoNotExist() throws Exception {
    assertThat(compile("TracedScope.open(\"x\", Thread.ofPlatform().factory());"))
        .anySatisfy(
            e -> assertThat(e).contains("compiler.err.cant.apply.symbols: kindname.method, open"));
    assertThat(
            compile(
                "TracedScope.open(\"x\", Joiner.awaitAllSuccessfulOrThrow(),"
                    + " Thread.ofPlatform().factory());"))
        .anySatisfy(
            e -> assertThat(e).contains("compiler.err.cant.apply.symbols: kindname.method, open"));
    // With them gone, a lambda configuration function is not ambiguous.
    assertThat(compile("TracedScope.open(\"x\", c -> c).close();")).isEmpty();
  }

  /** The helper itself reports nothing for code that compiles, so an empty result means it did. */
  @Test
  void compileReportsNoErrorForValidCode() throws Exception {
    assertThat(
            compile(
                "try (var scope = TracedScope.open(\"x\")) { scope.join(); }"
                    + " catch (ExecutionException e) { }"))
        .isEmpty();
  }

  /**
   * Compiles {@code body} as the body of a method that declares only {@code InterruptedException},
   * against the compiled {@code TracedScope}, and returns the compiler's errors as raw diagnostics
   * ({@code -XDrawDiagnostics}: a message key and its arguments, whatever the locale).
   */
  private List<String> compile(String body) throws IOException, URISyntaxException {
    var source =
        """
        import com.ionutbanu.scopetracer.core.TracedScope;
        import java.io.IOException;
        import java.util.concurrent.ExecutionException;
        import java.util.concurrent.StructuredTaskScope.Joiner;

        class Snippet {
          void run() throws InterruptedException {
        %s
          }
        }
        """
            .formatted(body);
    var dir = Files.createTempDirectory(out, "snippet");
    var file = Files.writeString(dir.resolve("Snippet.java"), source);
    var classes =
        Path.of(TracedScope.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    var javac = ToolProvider.findFirst("javac").orElseThrow();
    var output = new StringWriter();
    try (var writer = new PrintWriter(output)) {
      javac.run(
          writer,
          writer,
          "--release",
          Integer.toString(Runtime.version().feature()),
          "--enable-preview",
          "-Xlint:-preview",
          "-XDrawDiagnostics",
          "-proc:none",
          "-cp",
          classes.toString(),
          "-d",
          dir.toString(),
          file.toString());
    }
    return output.toString().lines().filter(line -> line.contains(": compiler.err.")).toList();
  }
}
