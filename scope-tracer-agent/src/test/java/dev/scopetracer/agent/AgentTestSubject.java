package dev.scopetracer.agent;

import java.nio.file.Path;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Minimal program that exercises {@code StructuredTaskScope} under a JFR recording. Invoked by
 * {@link ScopeTracerAgentIT} in a forked JVM with the agent attached; writes the recording to the
 * path given as {@code args[0]}.
 *
 * <p>Two scopes are opened to cover both naming modes:
 *
 * <ul>
 *   <li><b>Named scope</b> ({@code "agent-it-scope"}) — three tasks: two {@code Callable} and one
 *       {@code Runnable}. All succeed.
 *   <li><b>Unnamed scope</b> — one {@code Callable} task that succeeds. The agent derives the name
 *       from the call-site stack frame.
 * </ul>
 */
public final class AgentTestSubject {

  static final String NAMED_SCOPE = "agent-it-scope";

  private AgentTestSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);

    try (var recording = new Recording()) {
      recording.enable("dev.scopetracer.*");
      recording.start();

      // Named scope: three tasks — two Callable, one Runnable (delegates to Callable internally)
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName(NAMED_SCOPE).withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              Thread.sleep(10);
              return "result-1";
            });
        scope.fork(
            () -> {
              Thread.sleep(5);
              return "result-2";
            });
        // Runnable overload — traced via fork(Callable) delegation
        scope.fork((Runnable) () -> {});
        scope.join();
      }

      // Unnamed scope: name must be derived from the call-site by ScopeNameDeriver
      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              Thread.sleep(5);
              return "result-a";
            });
        scope.join();
      }

      recording.stop();
      recording.dump(jfrOut);
    }
  }
}
