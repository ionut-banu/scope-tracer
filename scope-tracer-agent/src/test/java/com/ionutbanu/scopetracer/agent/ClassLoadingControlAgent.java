package com.ionutbanu.scopetracer.agent;

import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * A test-only agent for {@link AgentStartupSignalIT} with no scope-tracer code in it: its {@code
 * premain} loads 1500 classes of {@code java.base}, without initializing them. It stands for "any
 * agent that loads many classes at start-up", so the test can tell whether the JDK itself hangs
 * when it is signalled in such a {@code premain}.
 */
public final class ClassLoadingControlAgent {

  private static final int CLASSES = 1500;

  private ClassLoadingControlAgent() {}

  public static void premain(String args, Instrumentation inst) throws Exception {
    List<String> names;
    try (Stream<Path> files =
        Files.walk(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base"))) {
      names =
          files
              .map(Path::toString)
              .filter(p -> p.endsWith(".class") && !p.endsWith("module-info.class"))
              .map(p -> p.substring("/modules/java.base/".length(), p.length() - ".class".length()))
              .map(p -> p.replace('/', '.'))
              .limit(CLASSES)
              .toList();
    }
    for (String name : names) {
      try {
        Class.forName(name, false, null);
      } catch (Throwable t) {
        // a class that cannot be loaded here does not matter; the point is the loading
      }
    }
  }
}
