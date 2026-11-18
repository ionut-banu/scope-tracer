package com.ionutbanu.scopetracer.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
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
 * the agent was loaded from to the bootstrap search path, then enters the bootstrap loader's copy
 * of {@code ScopeTracerAgent}, which does the real work, and hands it the jar's path: that copy
 * reads the advice bytes from this jar, because the bootstrap loader serves no resources from a jar
 * appended at runtime. It touches no other agent class, because this copy's classes are not the
 * ones the advice will see: state set here would be invisible there, and package-private access
 * between the two copies fails.
 *
 * <p>The jar it appends is the {@code -javaagent} jar, which is not always the jar this class was
 * loaded from. The JVM loads {@code Premain-Class} through the system class loader, which searches
 * {@code -cp} first, and appends the {@code -javaagent} jar to the end of that loader's path, after
 * every {@code -cp} entry and the jars their {@code Class-Path} attributes list. A scope-tracer jar
 * on {@code -cp} (the thin jar, or another copy of the fat-jar) therefore wins, and its code source
 * is the wrong jar (#128). {@link #chooseJar} takes instead the last copy of {@code
 * ScopeTracerAgent.class} the system class loader lists, unless that copy is itself an entry of
 * {@code java.class.path}: the JVM does not append a jar already on the path, so then the last copy
 * may be any {@code -cp} jar, and the agent refuses rather than guess. Only this version's code
 * makes that choice: an earlier release's jar on {@code -cp} runs its own {@code premain}.
 *
 * <p>It never throws. When the jar cannot be found, opened or appended, or cannot be told apart
 * from the class path, it prints one {@code WARN not tracing} line naming the reason and the two
 * fixes, and starts nothing.
 */
final class BootstrapEntry {

  /** The fat-jar's name when its manifest cannot be read. */
  static final String GENERIC_NAME = "scope-tracer-agent-<version>-agent.jar";

  private static final String BOOT_CLASS_PATH = "Boot-Class-Path";

  private BootstrapEntry() {}

  /**
   * Appends the jar {@code source} names to the bootstrap search path and calls {@code
   * ScopeTracerAgent.premainFromAppendedJar(args, inst, jar)} on the bootstrap loader's copy.
   *
   * @param source the code source of the application class loader's {@code ScopeTracerAgent}.
   */
  static void enter(String args, Instrumentation inst, CodeSource source) {
    enter(args, inst, source, BootstrapEntry::systemCopies, System.getProperty("java.class.path"));
  }

  /**
   * {@link #enter(String, Instrumentation, CodeSource)} with the class loader's copies of {@code
   * ScopeTracerAgent.class} and {@code java.class.path} passed in, so a test can supply them.
   *
   * @param copies every URL of {@code ScopeTracerAgent.class} on the system class loader, in its
   *     search order; it may throw.
   * @param classPath the value of {@code java.class.path}; may be {@code null}.
   */
  static void enter(
      String args,
      Instrumentation inst,
      CodeSource source,
      Callable<List<URL>> copies,
      String classPath) {
    try {
      // Load AgentLog into this copy now, while the jar is not yet on the bootstrap path. Once it
      // is, the application class loader finds every agent class it has not loaded yet in the
      // bootstrap copy, and a first call from here to that copy's package-private AgentLog would
      // fail with IllegalAccessError: neither the info line nor a warning would be printed.
      Class.forName(AgentLog.class.getName(), true, BootstrapEntry.class.getClassLoader());
      Path jar = appendAgentJar(source, inst, copies, classPath);
      if (jar == null) {
        return;
      }
      // Package-private, on the other copy: both are in unnamed modules, so setAccessible is
      // allowed. The path goes over as an argument, so the advice bytes come from this jar (#123).
      Method entry =
          Class.forName(ScopeTracerAgent.class.getName(), true, null)
              .getDeclaredMethod(
                  "premainFromAppendedJar", String.class, Instrumentation.class, Path.class);
      entry.setAccessible(true);
      entry.invoke(null, args, inst, jar);
    } catch (InvocationTargetException e) {
      // premain never throws, so this is only for safety: an exception out of it aborts the JVM.
      AgentLog.warn("not tracing: agent startup failed: " + AgentLog.describe(e.getCause()));
    } catch (Throwable t) {
      AgentLog.warn("not tracing: agent startup failed: " + AgentLog.describe(t));
    }
  }

  /**
   * Appends the {@code -javaagent} jar to the bootstrap search path, prints the info line and
   * returns the jar's absolute path, or prints the warning and returns {@code null}.
   */
  private static Path appendAgentJar(
      CodeSource source, Instrumentation inst, Callable<List<URL>> copies, String classPath) {
    URL url = source == null ? null : source.getLocation();
    if (url == null) {
      notTracing("the jar it was loaded from is unknown", GENERIC_NAME, null);
      return null;
    }
    if (!"file".equalsIgnoreCase(url.getProtocol())) {
      notTracing(
          "it was loaded from " + AgentLog.path(url) + ", which is not a local file",
          GENERIC_NAME,
          null);
      return null;
    }
    Path jar = null;
    String expectedName = GENERIC_NAME;
    try {
      // Path.of(URI), not URL.getPath(): a space or a non-ASCII character in the path survives.
      jar = Path.of(url.toURI()).toAbsolutePath();
      jar = chooseJar(jar, copies, classPath);
      if (jar == null) {
        return null;
      }
      // The JVM reads the jar by name; this handle is only for the manifest and the call.
      try (var file = new JarFile(jar.toFile())) {
        expectedName = expectedName(file);
        inst.appendToBootstrapClassLoaderSearch(file);
      }
    } catch (Throwable t) {
      notTracing(
          "cannot add "
              + AgentLog.path(jar != null ? jar : url)
              + " to it: "
              + AgentLog.describe(t),
          expectedName,
          jar);
      return null;
    }
    AgentLog.info(
        "added "
            + jar
            + " to the bootstrap class path, because the jar is not named "
            + expectedName);
    return jar;
  }

  /**
   * The jar to append: {@code running}, the jar this class was loaded from, unless the system class
   * loader lists a later copy of {@code ScopeTracerAgent.class}. That copy is the {@code
   * -javaagent} jar, which the JVM appends after every {@code -cp} entry; it is chosen, with one
   * info line naming both jars, when it is not itself an entry of {@code classPath}. When it is,
   * the {@code -javaagent} jar cannot be told apart from the class path: this prints the {@code
   * WARN not tracing} line and returns {@code null}. When the copies cannot be listed or read, the
   * answer is {@code running}, as it was before #128. Paths are compared by real path. Never
   * throws.
   */
  static Path chooseJar(Path running, Callable<List<URL>> copies, String classPath) {
    Path last;
    boolean lastOnClassPath;
    try {
      last = jarOf(lastOf(copies.call()));
      if (last == null) {
        return running;
      }
      last = realPath(last);
      if (last.equals(realPath(running))) {
        return running;
      }
      lastOnClassPath = isEntryOf(last, classPath);
    } catch (Throwable t) {
      return running;
    }
    if (lastOnClassPath) {
      notTracing(
          "the -javaagent jar cannot be told apart from the class path, which holds the agent at "
              + AgentLog.path(running)
              + " and at "
              + AgentLog.path(last),
          expectedName(last),
          null);
      return null;
    }
    AgentLog.info(
        "ignoring "
            + AgentLog.path(running)
            + " on the class path: the -javaagent jar is "
            + AgentLog.path(last));
    return last;
  }

  /** Every copy of {@code ScopeTracerAgent.class} on the system class loader, in search order. */
  private static List<URL> systemCopies() throws Exception {
    return Collections.list(
        ClassLoader.getSystemResources(
            ScopeTracerAgent.class.getName().replace('.', '/') + ".class"));
  }

  private static URL lastOf(List<URL> urls) {
    return urls == null || urls.isEmpty() ? null : urls.get(urls.size() - 1);
  }

  /**
   * The local jar a {@code jar:file:...!/...} URL points into, or {@code null} for any other URL (a
   * directory, {@code jrt:}, a remote jar).
   */
  private static Path jarOf(URL url) throws Exception {
    if (url == null || !"jar".equalsIgnoreCase(url.getProtocol())) {
      return null;
    }
    String spec = url.getFile();
    int separator = spec.indexOf("!/");
    if (separator < 0) {
      return null;
    }
    URI inner = new URI(spec.substring(0, separator));
    if (!"file".equalsIgnoreCase(inner.getScheme())) {
      return null;
    }
    return Path.of(inner);
  }

  /** Whether {@code jar} is one of the entries of {@code classPath}, compared by real path. */
  private static boolean isEntryOf(Path jar, String classPath) {
    if (classPath == null) {
      return false;
    }
    for (String entry : classPath.split(File.pathSeparator, -1)) {
      try {
        if (realPath(Path.of(entry.isEmpty() ? "." : entry)).equals(jar)) {
          return true;
        }
      } catch (Throwable t) {
        // an entry that is not a path cannot be the jar
      }
    }
    return false;
  }

  /** {@code path} with symbolic links resolved, or absolute and normalized if it does not exist. */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (Throwable t) {
      return path.toAbsolutePath().normalize();
    }
  }

  /** The original file name read from {@code jar}'s manifest, or {@link #GENERIC_NAME}. */
  private static String expectedName(Path jar) {
    try (var file = new JarFile(jar.toFile())) {
      return expectedName(file);
    } catch (Throwable t) {
      return GENERIC_NAME;
    }
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
            + AgentLog.path(expectedName)
            + ", or add -Xbootclasspath/a:"
            + (jar != null ? AgentLog.path(jar) : "<path to the jar>"));
  }
}
