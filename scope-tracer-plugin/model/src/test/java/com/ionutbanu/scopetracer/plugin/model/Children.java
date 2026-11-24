package com.ionutbanu.scopetracer.plugin.model;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Commands that start the child programs under {@code src/test/resources/children/}, single-file
 * Java sources run by the test JVM's own {@code java}, so no JDK 27 is needed.
 */
final class Children {

  static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");

  private Children() {}

  static Path source(String name) {
    try {
      return Path.of(Children.class.getResource("/children/" + name + ".java").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<String> command(String name, String... args) {
    var command = new ArrayList<>(List.of(JAVA.toString(), source(name).toString()));
    command.addAll(List.of(args));
    return command;
  }

  /** Alive children of the test JVM; the runners reap theirs before returning. */
  static List<ProcessHandle> aliveChildren() {
    return ProcessHandle.current().children().filter(ProcessHandle::isAlive).toList();
  }
}
