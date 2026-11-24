package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs a child process that cannot outlive its caller's patience: the call returns when the child
 * exits, when {@code timeout} passes, or when the cancellation check turns true (or the calling
 * thread is interrupted), and in the last two cases only after the child has been killed with
 * {@link Process#destroyForcibly()} and its {@link Process#onExit()} has completed.
 *
 * <p>The child can never block on a full pipe: stdout is discarded (or merged into the capture),
 * and the captured stream goes to a temp file in {@code tempDir}, deleted before the call returns.
 * Nothing reads from the child while it runs. Of the capture, the first {@link
 * #OUTPUT_LIMIT_BYTES} are kept for the caller's message.
 *
 * <p>Plain Java with no IntelliJ dependency; the plugin passes {@code indicator::isCanceled}.
 *
 * <pre>{@code
 * var result = BoundedProcess.run(List.of(java, "-version"), Capture.STDOUT_AND_STDERR,
 *     Duration.ofSeconds(30), () -> false, tempDir);
 * if (result instanceof BoundedProcess.Exited e && e.exitCode() == 0) { ... e.output() ... }
 * }</pre>
 */
public final class BoundedProcess {

  /**
   * At most 8 KiB of the child's captured output is kept, so a child writing a megabyte of stderr
   * puts 8 KiB, not a megabyte, into an error notification. The start is kept: it holds the
   * analyzer's error line and the {@code java -version} line.
   */
  public static final int OUTPUT_LIMIT_BYTES = 8 * 1024;

  /** How often the cancellation check is polled; well inside the 2 s a cancel must take. */
  static final Duration POLL_INTERVAL = Duration.ofMillis(50);

  /** Which of the child's streams are captured; stdout not captured is discarded. */
  public enum Capture {
    STDERR,
    STDOUT_AND_STDERR
  }

  /** How the run ended. {@link #process()} is empty only when the child was never started. */
  public sealed interface Result {
    Optional<ProcessHandle> process();
  }

  /** The child exited by itself; {@code output} is the start of what it wrote to the capture. */
  public record Exited(int exitCode, String output, Optional<ProcessHandle> process)
      implements Result {}

  /** The timeout passed; the child has been killed and reaped. */
  public record TimedOut(Optional<ProcessHandle> process) implements Result {}

  /**
   * The cancellation check turned true or the caller was interrupted (its interrupt flag is set
   * again); the child, if it was started, has been killed and reaped.
   */
  public record Cancelled(Optional<ProcessHandle> process) implements Result {}

  private BoundedProcess() {}

  /**
   * Runs {@code command} to one of the three endings in {@link Result}.
   *
   * @throws IOException when the child cannot be started or its capture file cannot be made.
   */
  public static Result run(
      List<String> command,
      Capture capture,
      Duration timeout,
      BooleanSupplier cancelled,
      Path tempDir)
      throws IOException {
    if (cancelled.getAsBoolean()) {
      return new Cancelled(Optional.empty());
    }
    var captureFile = Files.createTempFile(tempDir, "scope-tracer-output-", ".txt");
    Process process = null;
    try {
      var builder = new ProcessBuilder(command);
      if (capture == Capture.STDOUT_AND_STDERR) {
        builder.redirectErrorStream(true).redirectOutput(captureFile.toFile());
      } else {
        builder.redirectOutput(Redirect.DISCARD).redirectError(captureFile.toFile());
      }
      process = builder.start();
      var handle = Optional.of(process.toHandle());
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        if (cancelled.getAsBoolean()) {
          kill(process);
          return new Cancelled(handle);
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          kill(process);
          return new TimedOut(handle);
        }
        boolean exited;
        try {
          exited = process.waitFor(Math.min(left, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          kill(process);
          Thread.currentThread().interrupt();
          return new Cancelled(handle);
        }
        if (exited) {
          return new Exited(process.exitValue(), readHead(captureFile), handle);
        }
      }
    } finally {
      // Covers anything thrown above (a cancellation check that throws, say): never leave it.
      if (process != null && process.isAlive()) {
        kill(process);
      }
      Files.deleteIfExists(captureFile);
    }
  }

  /** Kills the child and its descendants, then waits, uninterruptibly, until it is reaped. */
  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    process.onExit().join();
  }

  private static String readHead(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      var head = in.readNBytes(OUTPUT_LIMIT_BYTES);
      var text = new String(head, StandardCharsets.UTF_8);
      return in.read() < 0 ? text : text + "\n[... output truncated after 8 KiB]";
    }
  }
}
