package com.ionutbanu.scopetracer.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Gets the agent onto the bootstrap class path when its manifest's {@code Boot-Class-Path} did not.
 *
 * <p>{@code Boot-Class-Path} names the fat-jar by its original file name, resolved in the directory
 * of the {@code -javaagent} jar. A renamed copy ({@code agent.jar}) leaves the JVM looking for a
 * file that is not there, which it ignores without a word, and loads {@code ScopeTracerAgent} from
 * the application class loader instead. Advice inlined into {@code StructuredTaskScope} cannot see
 * that loader, so nothing would be traced.
 *
 * <p>{@link #enter} is what {@code premain} does in that case, and all it does: it appends the jar
 * the agent was loaded from to the bootstrap search path, then runs {@code premain} again in the
 * bootstrap loader's copy of {@code ScopeTracerAgent}, which does the real work. It touches no
 * other agent class, because this copy's classes are not the ones the advice will see: state set
 * here would be invisible there, and package-private access between the two copies fails.
 *
 * <p>It never throws. When the jar cannot be found, opened or appended it prints one {@code WARN
 * not tracing} line naming the reason and the two fixes, and starts nothing.
 */
final class BootstrapEntry {

  /** The fat-jar's name when its manifest cannot be read. */
  static final String GENERIC_NAME = "scope-tracer-agent-<version>-agent.jar";

  private static final String BOOT_CLASS_PATH = "Boot-Class-Path";

  private BootstrapEntry() {}

  /**
   * Appends the jar {@code source} names to the bootstrap search path and calls {@code
   * ScopeTracerAgent.premain(args, inst)} on the bootstrap loader's copy.
   *
   * @param source the code source of the application class loader's {@code ScopeTracerAgent}.
   */
  static void enter(String args, Instrumentation inst, CodeSource source) {
    try {
      // Load AgentLog into this copy now, while the jar is not yet on the bootstrap path. Once it
      // is, the application class loader finds every agent class it has not loaded yet in the
      // bootstrap copy, and a first call from here to that copy's package-private AgentLog would
      // fail with IllegalAccessError: neither the info line nor a warning would be printed.
      Class.forName(AgentLog.class.getName(), true, BootstrapEntry.class.getClassLoader());
      if (!appendOwnJar(source, inst)) {
        return;
      }
      Class.forName(ScopeTracerAgent.class.getName(), true, null)
          .getMethod("premain", String.class, Instrumentation.class)
          .invoke(null, args, inst);
    } catch (InvocationTargetException e) {
      // premain never throws, so this is only for safety: an exception out of it aborts the JVM.
      AgentLog.warn("not tracing: agent startup failed: " + describe(e.getCause()));
    } catch (Throwable t) {
      AgentLog.warn("not tracing: agent startup failed: " + describe(t));
    }
  }

  /**
   * Appends the agent's own jar to the bootstrap search path and prints the info line, or prints
   * the warning and returns {@code false}.
   */
  private static boolean appendOwnJar(CodeSource source, Instrumentation inst) {
    URL url = source == null ? null : source.getLocation();
    if (url == null) {
      notTracing("the jar it was loaded from is unknown", GENERIC_NAME, null);
      return false;
    }
    if (!"file".equalsIgnoreCase(url.getProtocol())) {
      notTracing("it was loaded from " + url + ", which is not a local file", GENERIC_NAME, null);
      return false;
    }
    Path jar = null;
    String expectedName = GENERIC_NAME;
    try {
      // Path.of(URI), not URL.getPath(): a space or a non-ASCII character in the path survives.
      jar = Path.of(url.toURI()).toAbsolutePath();
      // The JVM reads the jar by name; this handle is only for the manifest and the call.
      try (var file = new JarFile(jar.toFile())) {
        expectedName = expectedName(file);
        inst.appendToBootstrapClassLoaderSearch(file);
      }
    } catch (Throwable t) {
      notTracing(
          "cannot add " + (jar != null ? jar : url) + " to it: " + describe(t), expectedName, jar);
      return false;
    }
    AgentLog.info(
        "added "
            + jar
            + " to the bootstrap class path, because the jar is not named "
            + expectedName);
    return true;
  }

  /** The file name the manifest's {@code Boot-Class-Path} gives, or {@link #GENERIC_NAME}. */
  private static String expectedName(JarFile file) {
    try {
      var manifest = file.getManifest();
      String value =
          manifest == null ? null : manifest.getMainAttributes().getValue(BOOT_CLASS_PATH);
      if (value == null || value.isBlank()) return GENERIC_NAME;
      String first = value.strip().split("\\s+")[0];
      return first.substring(first.lastIndexOf('/') + 1);
    } catch (Throwable t) {
      return GENERIC_NAME;
    }
  }

  private static void notTracing(String reason, String expectedName, Path jar) {
    AgentLog.warn(
        "not tracing: the agent jar is not on the bootstrap class path, and "
            + reason
            + "; rename the jar to "
            + expectedName
            + ", or add -Xbootclasspath/a:"
            + (jar != null ? jar : "<path to the jar>"));
  }

  /** One line: the exception's simple class name and message. */
  private static String describe(Throwable t) {
    if (t == null) return "unknown error";
    String msg = t.getMessage();
    String line = t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    return line.replace('\n', ' ').replace('\r', ' ');
  }
}
