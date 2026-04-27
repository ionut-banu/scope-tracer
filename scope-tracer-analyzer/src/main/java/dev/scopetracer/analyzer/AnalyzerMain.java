package dev.scopetracer.analyzer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the scope-tracer analyzer.
 *
 * <p>Usage: {@code analyzer <input.jfr> <output.html>}
 */
public final class AnalyzerMain {

  private AnalyzerMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: analyzer <input.jfr> <output.html>");
      System.exit(1);
    }
    var jfr = Path.of(args[0]);
    var html = Path.of(args[1]);
    var model = JfrParser.parse(jfr);
    Files.writeString(html, HtmlRenderer.render(model));
    System.out.println("Report written to " + html.toAbsolutePath());
  }
}
