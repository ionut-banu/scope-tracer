package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A child JVM running {@link ExitSubject}, with its stdout and stderr captured as they arrive so a
 * test can wait for a line without sleeping. Used by the at-exit ITs; always closed in a {@code
 * try}-with-resources, which kills the process if it is still alive.
 */
final class ChildJvm implements AutoCloseable {

  static final String AGENT_PREFIX = "[scope-tracer]";
  static final String WRITTEN = AGENT_PREFIX + " HTML report written to ";
  static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final Process process;
  private final Stream out;
  private final Stream err;

  private ChildJvm(Process process) {
    this.process = process;
    this.out = new Stream(process.getInputStream());
    this.err = new Stream(process.getErrorStream());
  }

  /** The agent fat-jar, or {@code null} when the ITs run without one (plain {@code mvn test}). */
  static String agentJar() {
    String jar = System.getProperty("agentJar", "");
    return !jar.isBlank() && Files.exists(Path.of(jar)) ? jar : null;
  }

  static String testClassesDir() {
    String dir = System.getProperty("testClassesDir", "");
    return dir.isBlank() ? null : dir;
  }

  static Path javaHome() {
    return Path.of(System.getProperty("java.home"));
  }

  /**
   * Starts {@code java --enable-preview <jvmArgs> -cp <test classes> ExitSubject <subjectArgs>} in
   * {@code workDir}. JFR's own startup lines are turned off: they go to stdout with a timestamp and
   * a pid, so two runs of one program would never have the same stdout.
   */
  static ChildJvm start(Path workDir, List<String> jvmArgs, String... subjectArgs)
      throws IOException {
    var command = new ArrayList<String>();
    command.add(javaHome().resolve("bin").resolve("java").toString());
    command.add("--enable-preview");
    command.add("-Xlog:jfr+startup=off");
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(testClassesDir());
    command.add(ExitSubject.class.getName());
    command.addAll(List.of(subjectArgs));
    return new ChildJvm(new ProcessBuilder(command).directory(workDir.toFile()).start());
  }

  /** Runs to the end and returns what it did. */
  static Result run(Path workDir, List<String> jvmArgs, String... subjectArgs) throws Exception {
    try (var child = start(workDir, jvmArgs, subjectArgs)) {
      return child.finish();
    }
  }

  long pid() {
    return process.pid();
  }

  /** Blocks until stdout has the line {@code line}, failing after {@link #TIMEOUT}. */
  void awaitStdout(String line) throws InterruptedException {
    out.await(line::equals, "stdout line '" + line + "'");
  }

  /** Blocks until a stderr line starts with {@code prefix}, failing after {@link #TIMEOUT}. */
  void awaitStderrStartingWith(String prefix) throws InterruptedException {
    err.await(l -> l.startsWith(prefix), "stderr line starting '" + prefix + "'");
  }

  void writeStdin(String line) throws IOException {
    OutputStream in = process.getOutputStream();
    in.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    in.flush();
  }

  /**
   * SIGTERM on Linux and macOS, as {@code Process.destroy()} sends. Through the {@link
   * ProcessHandle}, because {@code Process.destroy()} also closes this side of the child's stdout
   * and stderr, and what the child prints while it shuts down would be lost.
   */
  void terminate() {
    process.toHandle().destroy();
  }

  /** SIGKILL, as {@code Process.destroyForcibly()} sends. */
  void kill() {
    process.toHandle().destroyForcibly();
  }

  /** Waits for the process to end, at most {@link #TIMEOUT}, and returns what it did. */
  Result finish() throws Exception {
    if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("child JVM did not exit within " + TIMEOUT + "\n" + err.text());
    }
    out.join();
    err.join();
    return new Result(process.exitValue(), out.text(), err.text());
  }

  @Override
  public void close() {
    if (process.isAlive()) {
      process.destroyForcibly();
      try {
        process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** What a finished child did. */
  record Result(int exitCode, String stdout, String stderr) {

    /** stderr without the agent's own {@code [scope-tracer]} lines. */
    String stderrWithoutAgent() {
      return stderr
          .lines()
          .filter(l -> !l.startsWith(AGENT_PREFIX))
          .map(l -> l + "\n")
          .reduce("", String::concat);
    }

    /** Every {@code [scope-tracer]} line on stderr. */
    List<String> agentLines() {
      return stderr.lines().filter(l -> l.startsWith(AGENT_PREFIX)).toList();
    }

    /** The paths named by {@code HTML report written to} lines. */
    List<String> writtenReports() {
      return stderr
          .lines()
          .filter(l -> l.startsWith(WRITTEN))
          .map(l -> l.substring(WRITTEN.length()))
          .toList();
    }

    /** The agent's warning lines. */
    List<String> warnings() {
      return agentLines().stream().filter(l -> l.startsWith(AGENT_PREFIX + " WARN")).toList();
    }

    /**
     * The program is unchanged: same exit code, byte-identical stdout, and stderr identical once
     * the agent's lines are removed.
     */
    void assertSameProgramAs(Result withoutAgent) {
      assertThat(exitCode).as("exit code; stderr:%n%s", stderr).isEqualTo(withoutAgent.exitCode);
      assertThat(stdout).as("stdout").isEqualTo(withoutAgent.stdout);
      assertThat(stderrWithoutAgent())
          .as("stderr without [scope-tracer] lines")
          .isEqualTo(withoutAgent.stderrWithoutAgent());
    }
  }

  /** One output stream, read on a virtual thread into bytes and a queue of complete lines. */
  private static final class Stream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> seen = new ArrayList<>();
    private final Thread reader;

    Stream(InputStream in) {
      reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    var line = new ByteArrayOutputStream();
                    try {
                      int b;
                      while ((b = in.read()) != -1) {
                        synchronized (bytes) {
                          bytes.write(b);
                        }
                        if (b == '\n') {
                          lines.add(line.toString(StandardCharsets.UTF_8).stripTrailing());
                          line.reset();
                        } else {
                          line.write(b);
                        }
                      }
                    } catch (IOException e) {
                      // the process ended or was killed; what was read is kept
                    }
                    if (line.size() > 0) lines.add(line.toString(StandardCharsets.UTF_8));
                  });
    }

    void await(java.util.function.Predicate<String> match, String what)
        throws InterruptedException {
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      synchronized (seen) {
        if (seen.stream().anyMatch(match)) return;
      }
      while (true) {
        long left = deadline - System.nanoTime();
        String line = left > 0 ? lines.poll(left, TimeUnit.NANOSECONDS) : null;
        if (line == null) throw new AssertionError("timed out waiting for " + what);
        synchronized (seen) {
          seen.add(line);
        }
        if (match.test(line)) return;
      }
    }

    void join() throws InterruptedException {
      reader.join(TIMEOUT);
    }

    String text() {
      synchronized (bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
      }
    }
  }
}
