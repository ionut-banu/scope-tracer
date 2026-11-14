package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.core.events.TracedScopeEvent;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import jdk.jfr.Event;
import jdk.jfr.Recording;

/**
 * The agent's own JFR recording, started in {@code premain} when the {@code record} argument is
 * given, so a program run with only {@code -javaagent:...=record} leaves a {@code .jfr} (and, at
 * exit, its report) without any {@code -XX:StartFlightRecording}.
 *
 * <p>The recording is named {@value #NAME}, enables only the scope-tracer events with their own
 * defaults, is dumped to its destination when the JVM exits, and is capped at {@value
 * #MAX_SIZE_BYTES} bytes, the size JFR itself applies to {@code -XX:StartFlightRecording} when no
 * limit is given. The report is written by the same listener that writes one for any other
 * recording with a destination.
 */
final class AgentRecording {

  /** The recording's name, as {@code jcmd <pid> JFR.check} shows it. */
  static final String NAME = "scope-tracer";

  /** 250 MB, JFR's own default {@code maxsize} for {@code -XX:StartFlightRecording}. */
  static final long MAX_SIZE_BYTES = 250L * 1024 * 1024;

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

  /** How many {@code -N} suffixes are tried before the default name is given up on. */
  private static final int MAX_NAME_ATTEMPTS = 1_000;

  private AgentRecording() {}

  /**
   * Starts the agent's recording if the configuration asks for one. Never throws: a location that
   * cannot be written gives one warning and no recording, and the program runs as it would without
   * the argument.
   *
   * @return the recording's absolute destination, or {@code null} if none was started.
   */
  static Path startIfRequested(AgentConfig config) {
    if (!config.record()) return null;
    Path configured = config.recordPath();
    Path where = configured;
    Path dest = null;
    boolean claimed = false;
    Recording recording = null;
    try {
      Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
      where = configured == null ? userDir : userDir.resolve(configured);
      claimed = configured == null || Files.isDirectory(where);
      String defaultName = defaultFileName(ProcessHandle.current().pid(), LocalDateTime.now());
      dest = resolveDestination(configured, userDir, defaultName);
      recording = new Recording();
      recording.setName(NAME);
      for (Class<?> type : TracedScopeEvent.class.getPermittedSubclasses()) {
        recording.enable(type.asSubclass(Event.class));
      }
      recording.setToDisk(true);
      recording.setMaxSize(MAX_SIZE_BYTES);
      recording.setDumpOnExit(true);
      recording.setDestination(dest);
      recording.start();
      AgentLog.info("recording to " + dest);
      return dest;
    } catch (Throwable t) {
      AgentLog.warn(
          "not recording: cannot record to " + (dest != null ? dest : where) + ": " + describe(t));
      if (recording != null) {
        try {
          recording.close();
        } catch (Throwable ignored) {
          // nothing else to release
        }
      }
      if (claimed && dest != null) {
        try {
          Files.deleteIfExists(dest);
        } catch (Throwable ignored) {
          // the empty file is left behind, as JFR itself would leave it
        }
      }
      return null;
    }
  }

  /**
   * The default file name, after JFR's own {@code hotspot-pid-<pid>-id-<n>-<timestamp>.jfr}: {@code
   * scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr}.
   */
  static String defaultFileName(long pid, LocalDateTime now) {
    return "scope-tracer-" + pid + "-" + TIMESTAMP.format(now) + ".jfr";
  }

  /**
   * Where the agent's recording goes, as an absolute path.
   *
   * <ul>
   *   <li>{@code configured} is {@code null} ({@code record}, {@code record=}): {@code defaultName}
   *       in {@code userDir}.
   *   <li>{@code configured} is an existing directory: {@code defaultName} in it.
   *   <li>otherwise {@code configured} itself, resolved against {@code userDir} when relative, with
   *       its parent directories created. An existing file there is replaced when the recording is
   *       written, as JFR's {@code filename=} does.
   * </ul>
   *
   * <p>A default name is never written over an existing file: the file is created here, empty, and
   * if one of that name already exists {@code -1}, {@code -2}, ... is added before {@code .jfr}.
   *
   * @throws IOException if the location cannot be created or written.
   */
  static Path resolveDestination(Path configured, Path userDir, String defaultName)
      throws IOException {
    if (configured == null) {
      return claimUnique(userDir.toAbsolutePath(), defaultName);
    }
    Path absolute = userDir.toAbsolutePath().resolve(configured);
    if (Files.isDirectory(absolute)) {
      return claimUnique(absolute, defaultName);
    }
    Path parent = absolute.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return absolute;
  }

  private static Path claimUnique(Path dir, String defaultName) throws IOException {
    String stem =
        defaultName.endsWith(".jfr")
            ? defaultName.substring(0, defaultName.length() - 4)
            : defaultName;
    for (int n = 0; n < MAX_NAME_ATTEMPTS; n++) {
      Path candidate = dir.resolve(n == 0 ? stem + ".jfr" : stem + "-" + n + ".jfr");
      try {
        return Files.createFile(candidate);
      } catch (FileAlreadyExistsException e) {
        // taken: try the next suffix
      }
    }
    throw new FileAlreadyExistsException(dir.resolve(defaultName).toString());
  }

  /** One line: the exception's simple class name and message, never a stack trace. */
  static String describe(Throwable t) {
    String msg = t.getMessage();
    String line = t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    return line.replace('\n', ' ').replace('\r', ' ');
  }
}
