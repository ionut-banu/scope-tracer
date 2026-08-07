package com.ionutbanu.scopetracer.analyzer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * CLI entry point for the scope-tracer analyzer.
 *
 * <p>Usage: {@code analyzer <input.jfr> <output> [--format=json]}
 *
 * <p>By default the output is an HTML report. Passing {@code --format=json} writes the {@link
 * TraceModelJson} representation instead — intended for external tools (e.g. an IDE plugin) that
 * want the parsed trace without loading any scope-tracer classes in-process.
 *
 * <p>Exit codes:
 *
 * <ul>
 *   <li>{@code 0} — success.
 *   <li>{@code 1} — wrong arguments (usage printed to {@code stderr}).
 *   <li>{@code 2} — input file does not exist.
 *   <li>{@code 3} — I/O error while reading the input or writing the output.
 *   <li>{@code 4} — input file is not a valid JFR recording or the parser failed.
 * </ul>
 *
 * <p>CLI-facing messages go to {@code stderr}/{@code stdout} by convention; library code in {@link
 * JfrParser} and {@link HtmlRenderer} uses SLF4J.
 */
public final class AnalyzerMain {

  private static final String USAGE = "Usage: analyzer <input.jfr> <output> [--format=json]";

  private AnalyzerMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Run the analyzer with the given arguments and return an exit code instead of calling {@link
   * System#exit}. Exposed for testing; the {@link #main(String[])} entry point delegates here.
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
      out.println(USAGE);
      return 0;
    }
    if (args.length < 2 || args.length > 3) {
      err.println(USAGE);
      return 1;
    }
    boolean jsonFormat;
    if (args.length == 3) {
      if (!args[2].equals("--format=json")) {
        err.println(USAGE);
        return 1;
      }
      jsonFormat = true;
    } else {
      jsonFormat = false;
    }
    var jfr = Path.of(args[0]);
    var output = Path.of(args[1]);

    if (!Files.exists(jfr)) {
      err.println("analyzer: input.jfr not found: " + jfr);
      return 2;
    }

    try {
      var model = JfrParser.parse(jfr);
      var content = jsonFormat ? TraceModelJson.toJson(model) : HtmlRenderer.render(model);
      Files.writeString(output, content);
      out.println("Report written to " + output.toAbsolutePath());
      return 0;
    } catch (NoSuchFileException e) {
      err.println("analyzer: file not found: " + e.getFile());
      return 2;
    } catch (IOException e) {
      err.println("analyzer: I/O error: " + e.getMessage());
      return 3;
    } catch (RuntimeException e) {
      err.println("analyzer: failed to parse JFR recording " + jfr + ": " + e.getMessage());
      return 4;
    }
  }
}
