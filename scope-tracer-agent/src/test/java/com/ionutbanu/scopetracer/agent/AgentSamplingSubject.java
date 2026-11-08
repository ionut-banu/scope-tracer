package com.ionutbanu.scopetracer.agent;

import java.nio.file.Path;
import java.util.concurrent.StructuredTaskScope;
import jdk.jfr.Recording;

/**
 * Opens {@value #SCOPE_COUNT} scopes with the no-argument {@code StructuredTaskScope.open()}, one
 * after another, each with one task. Run by {@link ScopeTracerAgentFilterIT} with {@code
 * sample.rate} set, to count how many of them the sampler keeps; writes the recording to {@code
 * args[0]}.
 */
public final class AgentSamplingSubject {

  static final int SCOPE_COUNT = 1_000;

  private AgentSamplingSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfrOut = Path.of(args[0]);

    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();

      for (int i = 0; i < SCOPE_COUNT; i++) {
        final int n = i;
        try (var scope = StructuredTaskScope.open()) {
          scope.fork(() -> "sampled-" + n);
          scope.join();
        }
      }

      recording.stop();
      recording.dump(jfrOut);
    }
  }
}
