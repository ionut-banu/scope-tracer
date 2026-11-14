package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.analyzer.HtmlRenderer;
import com.ionutbanu.scopetracer.analyzer.JfrParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * Writes an HTML report beside a recording when it stops: after {@code jcmd JFR.stop} or a
 * programmatic {@code stop()}, and when the JVM exits for a recording with {@code dumponexit} (from
 * {@code -XX:StartFlightRecording} or the agent's own {@code record}).
 *
 * <p>At exit JFR calls the listener from its own shutdown hook, after the recording has been
 * written. The JVM halts once its shutdown hooks return and does not wait for a thread started
 * while it is shutting down, so then the report is written on the calling thread, and exit waits
 * for it. Otherwise it is written on a thread of its own, so a {@code stop()} in the application or
 * a {@code jcmd} is not held up.
 *
 * <p>Nothing here throws into JFR or the application: every failure is one {@code [scope-tracer]}
 * warning line and no report. The report is written to a temporary file in the target directory and
 * moved into place, so a JVM halted mid-write never leaves a partial report.
 */
final class AutoHtml {

  private AutoHtml() {}

  /** The listener that writes a report for every recording with a destination that stops. */
  static FlightRecorderListener listener(AgentConfig config) {
    return new FlightRecorderListener() {
      @Override
      public void recordingStateChanged(Recording recording) {
        try {
          if (recording.getState() != RecordingState.STOPPED) return;
          Path dest = recording.getDestination();
          if (dest == null) return; // no destination: nothing on disk to report on
          if (shuttingDown()) {
            write(dest, config);
          } else {
            new Thread(() -> write(dest, config), "scope-tracer-html-gen").start();
          }
        } catch (Throwable t) {
          AgentLog.warn("auto-HTML generation failed: " + AgentRecording.describe(t));
        }
      }
    };
  }

  /**
   * Whether the JVM has started running its shutdown hooks. {@code Runtime.addShutdownHook} refuses
   * from that moment on, which is decided before any hook runs, so unlike a flag set by a hook of
   * the agent's own it does not race with JFR's hook. The probe thread is never started.
   */
  static boolean shuttingDown() {
    Thread probe = new Thread(() -> {}, "scope-tracer-shutdown-probe");
    try {
      Runtime.getRuntime().addShutdownHook(probe);
    } catch (IllegalStateException e) {
      return true;
    }
    Runtime.getRuntime().removeShutdownHook(probe);
    return false;
  }

  /**
   * Parses {@code dest} and writes its report, unless the recording is empty, holds fewer scopes
   * than {@code min.scopes}, or the report cannot be written (one warning). Never throws.
   */
  static void write(Path dest, AgentConfig config) {
    Path html = null;
    try {
      if (!Files.isRegularFile(dest) || Files.size(dest) == 0) {
        AgentLog.debug("skipping auto-HTML: nothing recorded in " + dest);
        return;
      }
      var model = JfrParser.parse(dest);
      if (model.scopes().size() < config.minScopes()) {
        AgentLog.debug(
            "skipping auto-HTML: "
                + model.scopes().size()
                + " scope(s) is below min.scopes="
                + config.minScopes());
        return;
      }
      html = htmlPath(dest, config).toAbsolutePath();
      if (Files.isDirectory(html)) {
        AgentLog.warn("cannot write HTML report to " + html + ": a directory is in the way");
        return;
      }
      String report = HtmlRenderer.render(model);
      Path parent = html.getParent();
      Files.createDirectories(parent);
      writeAtomically(html, report);
      AgentLog.info("HTML report written to " + html);
    } catch (Throwable t) {
      AgentLog.warn(
          (html != null
                  ? "cannot write HTML report to " + html
                  : "auto-HTML generation failed for " + dest.toAbsolutePath())
              + ": "
              + AgentRecording.describe(t));
    }
  }

  /** {@code a.jfr} becomes {@code a<suffix>}, beside it or in {@code output.dir}. */
  static Path htmlPath(Path dest, AgentConfig config) {
    String filename = dest.getFileName().toString();
    String htmlFilename =
        filename.endsWith(".jfr")
            ? filename.substring(0, filename.length() - 4) + config.outputSuffix()
            : filename + config.outputSuffix();
    return config.outputDir() != null
        ? config.outputDir().resolve(htmlFilename)
        : dest.resolveSibling(htmlFilename);
  }

  private static void writeAtomically(Path html, String content) throws IOException {
    // CREATE_NEW, not Files.createTempFile: a temp file is created owner-only, and the report would
    // then not be readable by anyone else, as it was when written in place.
    Path tmp =
        html.resolveSibling(
            "."
                + html.getFileName()
                + "."
                + Long.toHexString(ThreadLocalRandom.current().nextLong())
                + ".tmp");
    try {
      Files.writeString(
          tmp,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      try {
        Files.move(tmp, html, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, html, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
