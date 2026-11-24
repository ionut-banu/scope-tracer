package com.ionutbanu.scopetracer.plugin.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the temp HTML file behind the tool window's views (#62). Showing the next recording's
 * report deletes the previous one; disposing the tool window's content (closing the project)
 * deletes the one shown; a report that arrives after that is deleted at once. Synchronized: the
 * EDT shows, the platform disposes.
 *
 * <pre>{@code
 * if (shown.show(display.htmlFile())) { ...replace the views... }
 * // on dispose:
 * shown.dispose();
 * }</pre>
 */
public final class ShownReport {

  private Path current;
  private boolean disposed;

  /**
   * Makes {@code next} (or no report, {@code null}) the file shown, deleting the previous one.
   *
   * @return {@code false} when already disposed: {@code next} has been deleted and the views must
   *     not be touched.
   */
  public synchronized boolean show(Path next) {
    if (disposed) {
      deleteQuietly(next);
      return false;
    }
    var previous = current;
    current = next;
    if (previous != null && !Objects.equals(previous, next)) {
      deleteQuietly(previous);
    }
    return true;
  }

  /** The report file shown, or {@code null}. */
  public synchronized Path current() {
    return current;
  }

  public synchronized boolean isDisposed() {
    return disposed;
  }

  /** Deletes the file shown; every later {@link #show} is refused. */
  public synchronized void dispose() {
    disposed = true;
    deleteQuietly(current);
    current = null;
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // a temp file left in java.io.tmpdir is the worst case
    }
  }
}
