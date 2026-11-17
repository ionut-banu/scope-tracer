package com.ionutbanu.scopetracer.analyzer;

import com.ionutbanu.scopetracer.analyzer.model.TraceModel;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point for the scope-tracer analyzer.
 *
 * <p>Usage: {@code analyzer <input.jfr> <output> [--format=json] [--verbose]}
 *
 * <pre>{@code
 * java --enable-preview -jar scope-tracer-analyzer-<v>-executable.jar myapp.jfr report.html
 * }</pre>
 *
 * <p>By default the output is an HTML report. Passing {@code --format=json} writes the {@link
 * TraceModelJson} representation instead — intended for external tools (e.g. an IDE plugin) that
 * want the parsed trace without loading any scope-tracer classes in-process. Options may come
 * before, between or after the two positional arguments, each at most once. An argument that starts
 * with {@code -} (other than {@code -} alone) is an option, so an input path that starts with one
 * is written {@code ./-name.jfr}.
 *
 * <p>Output:
 *
 * <ul>
 *   <li>{@code stdout} is the one line {@code Report written to <absolute output path>}, or the
 *       usage for {@code -h} / {@code --help}. Nothing else is printed there.
 *   <li>{@code stderr} holds errors (one {@code analyzer: ...} line, or the usage on exit 1) and
 *       warnings a user can act on, printed after the report is written, one line each starting
 *       {@code analyzer: warning: }: scopes or tasks with no end event, and a recording with no
 *       scope-tracer events. A warning does not change the exit code.
 *   <li>The library's SLF4J output is hidden. {@code --verbose} prints it on {@code stderr} at
 *       DEBUG and above, with its level and logger name, and adds the stack trace after the error
 *       line of a run that exits 3 or 4. {@code --verbose} is the only switch: no system property
 *       or environment variable turns diagnostics on.
 * </ul>
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
 * <p>CLI-facing messages go to {@code stderr}/{@code stdout} through the streams {@link #run} is
 * given; library code in {@link JfrParser} and {@link HtmlRenderer} uses SLF4J. The {@code
 * -executable} jar configures Logback from a {@code logback.xml} that only that jar contains; the
 * plain library jar configures no logging and does not need Logback.
 */
public final class AnalyzerMain {

  static final String USAGE = "Usage: analyzer <input.jfr> <output> [--format=json] [--verbose]";

  static final String WARNING = "analyzer: warning: ";

  static final String BUFFER_TUNING_URL =
      "https://github.com/ionut-banu/scope-tracer/blob/main/docs/jfr-events.md"
          + "#high-volume-recordings-jfr-buffer-tuning";

  /**
   * Read by the {@code -executable} jar's {@code logback.xml} as the root level. {@link #main} sets
   * it before the first logger is created, every time, so a value given with {@code -D} is replaced
   * and cannot turn diagnostics on.
   */
  static final String LOG_LEVEL_PROPERTY = "scopetracer.analyzer.cli.logLevel";

  private AnalyzerMain() {}

  public static void main(String[] args) {
    boolean verbose = parse(args) instanceof Arguments.Analyze analyze && analyze.verbose();
    System.setProperty(LOG_LEVEL_PROPERTY, verbose ? "DEBUG" : "OFF");
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Run the analyzer with the given arguments and return an exit code instead of calling {@link
   * System#exit}. Exposed for testing; the {@link #main(String[])} entry point delegates here.
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    return switch (parse(args)) {
      case Arguments.Help() -> {
        out.println(USAGE);
        yield 0;
      }
      case Arguments.Wrong() -> {
        err.println(USAGE);
        yield 1;
      }
      case Arguments.Analyze analyze -> analyze(analyze, out, err);
    };
  }

  private static int analyze(Arguments.Analyze args, PrintStream out, PrintStream err) {
    var jfr = Path.of(args.input());
    var output = Path.of(args.output());

    if (!Files.exists(jfr)) {
      err.println("analyzer: input.jfr not found: " + jfr);
      return 2;
    }

    try {
      var model = JfrParser.parse(jfr);
      var content = args.json() ? TraceModelJson.toJson(model) : HtmlRenderer.render(model);
      Files.writeString(output, content);
      out.println("Report written to " + output.toAbsolutePath());
      warnings(jfr, model).forEach(err::println);
      return 0;
    } catch (NoSuchFileException e) {
      err.println("analyzer: file not found: " + e.getFile());
      return 2;
    } catch (IOException e) {
      err.println("analyzer: I/O error: " + e.getMessage());
      if (args.verbose()) e.printStackTrace(err);
      return 3;
    } catch (RuntimeException e) {
      err.println("analyzer: failed to parse JFR recording " + jfr + ": " + e.getMessage());
      if (args.verbose()) e.printStackTrace(err);
      return 4;
    }
  }

  /**
   * The {@code analyzer: warning:} lines for {@code model}, read from {@code jfr}: none for a
   * complete recording, one for scopes and tasks with no end event, one for a recording with no
   * scopes. Computed from the model, not from log output, so a recording made by the agent and one
   * made with {@code TracedScope} get the same line.
   */
  static List<String> warnings(Path jfr, TraceModel model) {
    if (model.scopes().isEmpty()) {
      return List.of(
          WARNING
              + jfr
              + " contains no scope-tracer events: the program was not run with the"
              + " scope-tracer agent or TracedScope, or the recording did not enable"
              + " com.ionutbanu.scopetracer.* events");
    }
    long scopes = JfrParser.incompleteScopes(model);
    long tasks = JfrParser.incompleteTasks(model);
    if (scopes == 0 && tasks == 0) return List.of();
    var counts = new ArrayList<String>();
    if (scopes > 0) counts.add(count(scopes, "scope") + " without a close event");
    if (tasks > 0) counts.add(count(tasks, "task") + " without a completion event");
    return List.of(
        WARNING
            + jfr
            + " has "
            + String.join(" and ", counts)
            + "; the report draws them as incomplete. Either they were still running when the"
            + " recording was written, or JFR dropped their events (see "
            + BUFFER_TUNING_URL
            + ")");
  }

  private static String count(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  /**
   * Reads {@code args}. Options may be anywhere, each at most once. {@code -h} or {@code --help} is
   * help only as the only argument.
   */
  static Arguments parse(String[] args) {
    if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
      return new Arguments.Help();
    }
    boolean json = false;
    boolean verbose = false;
    var positional = new ArrayList<String>();
    for (String arg : args) {
      if (arg.length() > 1 && arg.startsWith("-")) {
        switch (arg) {
          case "--format=json" -> {
            if (json) return new Arguments.Wrong();
            json = true;
          }
          case "--verbose" -> {
            if (verbose) return new Arguments.Wrong();
            verbose = true;
          }
          default -> {
            return new Arguments.Wrong();
          }
        }
      } else {
        positional.add(arg);
      }
    }
    if (positional.size() != 2) return new Arguments.Wrong();
    return new Arguments.Analyze(positional.get(0), positional.get(1), json, verbose);
  }

  /** What a command line asks for. */
  sealed interface Arguments {

    /** {@code -h} or {@code --help} alone: the usage on stdout, exit 0. */
    record Help() implements Arguments {}

    /** Not a valid command line: the usage on stderr, exit 1. */
    record Wrong() implements Arguments {}

    /** Analyze {@code input} into {@code output}. */
    record Analyze(String input, String output, boolean json, boolean verbose)
        implements Arguments {}
  }
}
