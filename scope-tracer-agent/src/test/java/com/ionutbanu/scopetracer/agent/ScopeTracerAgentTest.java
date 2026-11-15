package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
