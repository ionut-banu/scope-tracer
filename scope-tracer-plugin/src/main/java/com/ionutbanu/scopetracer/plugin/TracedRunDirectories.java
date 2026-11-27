package com.ionutbanu.scopetracer.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The temp recording directories of this project's "Run with Scope Tracer" runs (#64), one per
 * run. A directory is deleted with everything in it when its recordings have been analyzed, when
 * the run is released without opening them, and, for whatever is still here, when the project
 * closes. A run that left more recordings than are opened keeps its directory ({@link #keep}).
 */
@Service(Service.Level.PROJECT)
final class TracedRunDirectories implements Disposable {

  private final Set<Path> live = ConcurrentHashMap.newKeySet();

  static TracedRunDirectories getInstance(Project project) {
    return project.getService(TracedRunDirectories.class);
  }

  /** Makes a new temp directory for one run's recordings. */
  static Path create(Project project) throws IOException {
    var dir = Files.createTempDirectory("scope-tracer-run-");
    getInstance(project).live.add(dir);
    return dir;
  }

  /** Deletes {@code dir} and everything in it; never throws. */
  static void delete(Project project, Path dir) {
    if (!project.isDisposed()) {
      getInstance(project).live.remove(dir);
    }
    deleteRecursively(dir);
  }

  /** Leaves {@code dir} on disk for the user: it is no longer deleted when the project closes. */
  static void keep(Project project, Path dir) {
    if (!project.isDisposed()) {
      getInstance(project).live.remove(dir);
    }
  }

  @Override
  public void dispose() {
    for (var dir : List.copyOf(live)) {
      deleteRecursively(dir);
    }
    live.clear();
  }

  static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (var p : walk.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // temp files; the OS will clear them
        }
      }
    } catch (IOException | RuntimeException ignored) {
      // temp files; the OS will clear them
    }
  }
}
