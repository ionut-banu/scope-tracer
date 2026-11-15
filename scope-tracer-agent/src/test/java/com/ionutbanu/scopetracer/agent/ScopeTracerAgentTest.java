package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bootstrap copy's entry from {@link BootstrapEntry} under a renamed jar (issue #123): the
 * advice bytes come from the jar {@code BootstrapEntry} appended, and when that jar cannot be read
 * the agent does not trace. It never falls back to the system class loader, which would read
 * whatever {@code -cp} has under the advice classes' names (criterion 8).
 */
class ScopeTracerAgentTest {

  private static final String WARN_NOT_TRACING = "[scope-tracer] WARN not tracing: ";

  /** Calls {@code premainFromAppendedJar} with stderr captured; the lines it printed. */
  private static List<String> enterWith(Path jar, List<String> calls) {
    Instrumentation inst =
        (Instrumentation)
            Proxy.newProxyInstance(
                ScopeTracerAgentTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (self, method, args) -> {
                  calls.add(method.getName());
                  throw new UnsupportedOperationException(method.getName());
                });
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      assertThatCode(() -> ScopeTracerAgent.premainFromAppendedJar("html=false", inst, jar))
          .doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @Test
  void appendedJarThatDoesNotExistWarnsOnceAndInstrumentsNothing(@TempDir Path dir) {
    Path missing = dir.resolve("gone.jar");
    var calls = new ArrayList<String>();
    List<String> lines = enterWith(missing, calls);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains("cannot read the advice classes from " + missing + ": ")
        .contains("Exception");
    assertThat(calls).as("Instrumentation calls").isEmpty();
  }

  @Test
  void appendedFileThatIsNotAJarWarnsOnceAndInstrumentsNothing(@TempDir Path dir) throws Exception {
    Path notAJar = Files.writeString(dir.resolve("agent.jar"), "not a zip file");
    var calls = new ArrayList<String>();
    List<String> lines = enterWith(notAJar, calls);

    assertThat(lines)
        .singleElement()
        .asString()
        .startsWith(WARN_NOT_TRACING)
        .contains("cannot read the advice classes from " + notAJar + ": ")
        .contains("ZipException");
    assertThat(calls).as("Instrumentation calls").isEmpty();
  }

  // --- issue #121, criterion 11: closing the jar locator ---

  private static final String CLOSE_FAILURE = "close failed on purpose";
  private static final String INSTALL_FAILURE = "install failed on purpose";

  /**
   * A jar locator that finds nothing (the advice classes' own loader already has their bytes in a
   * unit test) and whose {@code close()} throws, as {@code JarFile.close()} could.
   */
  private static final ClassFileLocator CLOSE_THROWS =
      new ClassFileLocator() {
        @Override
        public Resolution locate(String name) {
          return new Resolution.Illegal(name);
        }

        @Override
        public void close() throws IOException {
          throw new IOException(CLOSE_FAILURE);
        }
      };

  /**
   * An {@code Instrumentation} on which ByteBuddy's {@code installOn} succeeds: retransformation
   * supported, no class loaded yet. Every call is recorded; {@code addTransformer} throws {@code
   * IllegalStateException} when {@code failInstall} is set.
   */
  private static Instrumentation fakeInstrumentation(List<String> calls, boolean failInstall) {
    return (Instrumentation)
        Proxy.newProxyInstance(
            ScopeTracerAgentTest.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (self, method, args) -> {
              calls.add(method.getName());
              return switch (method.getName()) {
                case "addTransformer" -> {
                  if (failInstall) throw new IllegalStateException(INSTALL_FAILURE);
                  yield null;
                }
                case "isRetransformClassesSupported", "isRedefineClassesSupported" -> true;
                case "getAllLoadedClasses", "getInitiatedClasses" -> new Class<?>[0];
                case "isModifiableClass", "isModifiableModule", "isNativeMethodPrefixSupported" ->
                    false;
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                case "toString" -> "fake Instrumentation";
                default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
              };
            });
  }

  /** {@code start} under a renamed jar with {@link #CLOSE_THROWS}; the lines it printed. */
  private static List<String> startWithClosingFailure(Path dir, Instrumentation inst) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      assertThatCode(
              () ->
                  ScopeTracerAgent.start(
                      "html=false", inst, dir.resolve("agent.jar"), jar -> CLOSE_THROWS))
          .doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @Test
  void closeThatThrowsAfterASuccessfulInstallIsIgnoredSilently(@TempDir Path dir) {
    var calls = new ArrayList<String>();
    List<String> lines = startWithClosingFailure(dir, fakeInstrumentation(calls, false));

    assertThat(lines).as("stderr").isEmpty();
    assertThat(calls).as("Instrumentation calls").contains("addTransformer");
  }

  @Test
  void installThatThrowsIsNamedAndNotHiddenByAFailingClose(@TempDir Path dir) {
    var calls = new ArrayList<String>();
    List<String> lines = startWithClosingFailure(dir, fakeInstrumentation(calls, true));

    assertThat(lines)
        .as("ByteBuddy's installOn wraps the exception out of addTransformer")
        .containsExactly(
            WARN_NOT_TRACING
                + "instrumentation failed: IllegalStateException: Could not install class file"
                + " transformer");
    assertThat(lines.get(0)).doesNotContain("IOException").doesNotContain(CLOSE_FAILURE);
    assertThat(calls).as("Instrumentation calls").contains("addTransformer");
  }
}
