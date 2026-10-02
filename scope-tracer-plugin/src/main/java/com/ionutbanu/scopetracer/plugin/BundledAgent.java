package com.ionutbanu.scopetracer.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled scope-tracer agent fat-jar for a run. The jar must keep its original file
 * name ({@code scope-tracer-agent-<version>-agent.jar}) because its manifest lists that name in
 * {@code Boot-Class-Path}, which the JVM resolves relative to the jar's own directory.
 */
final class BundledAgent {

  private static final String NAME_RESOURCE = "/agent/agent-jar-name.txt";

  private BundledAgent() {}

  /** Extracts the agent into a fresh temp directory and returns the jar's path. */
  static Path extract() throws IOException {
    String jarName;
    try (InputStream in = BundledAgent.class.getResourceAsStream(NAME_RESOURCE)) {
      if (in == null) {
        throw new IOException("Bundled agent not found — plugin build is broken.");
      }
      jarName = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
    Path dir = Files.createTempDirectory("scope-tracer-agent-");
    Path jar = dir.resolve(jarName);
    try (InputStream in = BundledAgent.class.getResourceAsStream("/agent/" + jarName)) {
      if (in == null) {
        throw new IOException("Bundled agent jar /agent/" + jarName + " not found.");
      }
      Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
    }
    return jar;
  }

  /** Deletes the extracted jar and its temp directory; never throws. */
  static void cleanUp(Path jar) {
    try {
      Files.deleteIfExists(jar);
      Files.deleteIfExists(jar.getParent());
    } catch (IOException ignored) {
      // temp files; the OS will clear them
    }
  }
}
