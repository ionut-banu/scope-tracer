package dev.scopetracer.demos;

import dev.scopetracer.analyzer.HtmlRenderer;
import dev.scopetracer.analyzer.JfrParser;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Recording;

/** Captures a JFR recording for a demo action and writes both a .jfr file and an HTML report. */
final class DemoRunner {

  private DemoRunner() {}

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  static void run(String name, ThrowingRunnable action) throws Exception {
    Path targetDir = Path.of("target");
    Files.createDirectories(targetDir);
    Path jfr = targetDir.resolve(name + ".jfr");
    Path html = targetDir.resolve(name + ".html");

    try (var recording = new Recording()) {
      recording.enable("dev.scopetracer.*");
      recording.start();
      try {
        action.run();
      } finally {
        recording.stop();
      }
      recording.dump(jfr);
    }

    var model = JfrParser.parse(jfr);
    Files.writeString(html, HtmlRenderer.render(model));

    System.out.println("JFR  → " + jfr.toAbsolutePath());
    System.out.println("HTML → " + html.toAbsolutePath());
  }
}
