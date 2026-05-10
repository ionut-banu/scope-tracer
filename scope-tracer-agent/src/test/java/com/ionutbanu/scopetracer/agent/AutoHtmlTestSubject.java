package com.ionutbanu.scopetracer.agent;

import java.nio.file.Path;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * Minimal test subject for the auto-HTML integration test. Creates a JFR recording with {@link
 * Recording#setDestination(Path)} set — this mirrors what {@code jcmd JFR.start filename=...} does,
 * which causes the {@link jdk.jfr.FlightRecorderListener} registered by {@link ScopeTracerAgent} to
 * fire when the recording stops.
 */
public final class AutoHtmlTestSubject {

  private AutoHtmlTestSubject() {}

  public static void main(String[] args) throws Exception {
    Path dest = Path.of(args[0]);
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.setDestination(dest);
      recording.start();

      try (var scope =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(),
              c -> c.withName("checkout-flow").withThreadFactory(Thread.ofVirtual().factory()))) {
        scope.fork(
            () -> {
              Thread.sleep(20);
              return "ok";
            });
        scope.join();
      }

      // stop() transitions the recording to STOPPED, which triggers the FlightRecorderListener.
      // The HTML is written on a daemon thread — sleep briefly to let it finish before exit.
      recording.stop();
    }
    Thread.sleep(500);
  }
}
