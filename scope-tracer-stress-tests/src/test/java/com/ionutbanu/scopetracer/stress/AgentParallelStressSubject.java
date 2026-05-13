package com.ionutbanu.scopetracer.stress;

import java.nio.file.Path;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Forked-JVM driver for {@link AgentParallelStressIT}. Uses plain {@link StructuredTaskScope} so
 * that the scope-tracer agent (attached via {@code -javaagent}) is what produces JFR events. The
 * test must verify the agent does not lose events under high task concurrency.
 *
 * <p>Args: {@code <jfr-output-path> <scopes> <tasksPerScope>}.
 */
public final class AgentParallelStressSubject {

  private AgentParallelStressSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);
    int scopes = Integer.parseInt(args[1]);
    int tasksPerScope = Integer.parseInt(args[2]);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.setToDisk(true);
      recording.setMaxSize(128L * 1024 * 1024);
      recording.start();

      try (var outer =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c ->
                  c.withName("agent-stress-outer")
                      .withThreadFactory(Thread.ofVirtual().factory()))) {
        for (int i = 0; i < scopes; i++) {
          final int idx = i;
          outer.fork(
              () -> {
                try (var inner =
                    StructuredTaskScope.open(
                        Joiner.awaitAllSuccessfulOrThrow(),
                        c ->
                            c.withName("agent-stress-inner-" + idx)
                                .withThreadFactory(Thread.ofVirtual().factory()))) {
                  for (int t = 0; t < tasksPerScope; t++) {
                    inner.fork(
                        () -> {
                          java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                          return 1;
                        });
                  }
                  inner.join();
                }
                return idx;
              });
        }
        outer.join();
      }

      recording.stop();
      recording.dump(jfrOut);
    }
  }
}
