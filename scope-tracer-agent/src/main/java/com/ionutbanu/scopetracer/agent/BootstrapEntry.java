package com.ionutbanu.scopetracer.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
 * ScopeTracerAgent.class} the system class loader lists, unless that copy is reachable from {@code
 * java.class.path}: an entry of it, or a jar its entries' manifest {@code Class-Path} attributes
 * list, directly or through a chain. The JVM does not append a jar that is already reachable, so
 * then the last copy may be any of those jars, and the agent refuses rather than guess (#158): with
 * {@code -cp app.jar}, {@code Class-Path: agent.jar thin.jar} and {@code Class-Path: thin.jar
 * agent.jar} list the same copies in the same order. The manifests are read only when the last copy
 * is not the running copy and not a {@code -cp} entry, so the common case reads none. Only this
 * version's code makes that choice: an earlier release's jar on {@code -cp} runs its own {@code
 * premain}.
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

  /** Reads the {@code Class-Path} attribute of a jar's manifest; a test supplies its own. */
  @FunctionalInterface
  interface ClassPathAttribute {
    /**
     * The value of {@code jar}'s manifest {@code Class-Path}, or {@code null}; may throw.
     *
     * @param jar a regular file, by real path.
     */
    String of(Path jar) throws Exception;
  }

  /**
   * The jar to append: {@code running}, the jar this class was loaded from, unless the system class
   * loader lists a later copy of {@code ScopeTracerAgent.class}. That copy is the {@code
   * -javaagent} jar when the JVM appended it, after every {@code -cp} entry and the jars their
   * manifest {@code Class-Path} attributes list, which it does only when the jar is not reachable
   * from the class path already. So the last copy is chosen, with one info line naming both jars,
   * only when it is not reachable from {@code classPath}: not an entry, and not listed, directly or
   * through a chain, by an entry's manifest {@code Class-Path}. When it is reachable, the {@code
   * -javaagent} jar cannot be told apart from the class path: this prints the {@code WARN not
   * tracing} line, naming the jar whose {@code Class-Path} listed the last copy when one did, and
   * returns {@code null}. When the copies cannot be listed or read, the answer is {@code running},
   * as it was before #128; when the manifests cannot be read, the last copy is taken as not
   * reachable, as it was before #158. Paths are compared by real path. No manifest is read when the
   * running copy is the last copy or the last copy is an entry of {@code classPath}. Never throws.
   */
  static Path chooseJar(Path running, Callable<List<URL>> copies, String classPath) {
    return chooseJar(running, copies, classPath, BootstrapEntry::classPathAttribute);
  }

  /** {@link #chooseJar(Path, Callable, String)} with the manifest reader passed in, for a test. */
  static Path chooseJar(
      Path running, Callable<List<URL>> copies, String classPath, ClassPathAttribute manifests) {
    Path last;
    Path reachedFrom;
    try {
      last = jarOf(lastOf(copies.call()));
      if (last == null) {
        return running;
      }
      last = realPath(last);
      if (last.equals(realPath(running))) {
        return running;
      }
      reachedFrom = reachedFrom(last, classPath, manifests);
    } catch (Throwable t) {
      return running;
    }
    if (reachedFrom != null) {
      notTracing(
          "the -javaagent jar cannot be told apart from the class path, which holds the agent at "
              + AgentLog.path(running)
              + " and at "
              + AgentLog.path(last)
              + (reachedFrom.equals(last)
                  ? ""
                  : " (listed by the Class-Path of " + AgentLog.path(reachedFrom) + ")"),
          expectedName(running, last),
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

  /**
   * Where {@code jar} is reachable from {@code classPath}: {@code jar} itself when it is an entry,
   * the jar whose manifest {@code Class-Path} lists it when it is reached through one, or {@code
   * null} when it is not reachable. Entries are checked first, so a direct entry reads no manifest;
   * then every jar's {@code Class-Path} is followed breadth first, each file once, so a cycle ends.
   * An entry or a manifest that cannot be read is skipped. If the walk itself fails, the answer is
   * {@code null}: the last copy is taken as appended by the JVM, as before #158.
   */
  private static Path reachedFrom(Path jar, String classPath, ClassPathAttribute manifests) {
    if (classPath == null) {
      return null;
    }
    var seen = new HashSet<Path>();
    var queue = new ArrayDeque<Path>();
    for (String entry : classPath.split(File.pathSeparator, -1)) {
      try {
        Path path = realPath(Path.of(entry.isEmpty() ? "." : entry));
        if (path.equals(jar)) {
          return jar;
        }
        if (seen.add(path)) {
          queue.add(path);
        }
      } catch (Throwable t) {
        // an entry that is not a path cannot be the jar, and lists nothing
      }
    }
    try {
      while (!queue.isEmpty()) {
        Path listing = queue.remove();
        for (Path listed : listedBy(listing, manifests)) {
          if (listed.equals(jar)) {
            return listing;
          }
          if (seen.add(listed)) {
            queue.add(listed);
          }
        }
      }
    } catch (Throwable t) {
      // the walk failed as a whole: keep the answer from before #158
    }
    return null;
  }

  /**
   * The local files {@code listing}'s manifest {@code Class-Path} names, by real path, resolved as
   * the JVM resolves them: space-separated URLs relative to {@code listing}, {@code %20} for a
   * space. A directory, a missing file, an entry that is not a valid URI and one that is not a
   * {@code file:} URL are left out, as is everything when {@code listing} is not a regular file or
   * its manifest cannot be read.
   */
  private static List<Path> listedBy(Path listing, ClassPathAttribute manifests) {
    String value;
    try {
      if (!Files.isRegularFile(listing)) {
        return List.of();
      }
      value = manifests.of(listing);
    } catch (Throwable t) {
      return List.of();
    }
    if (value == null || value.isBlank()) {
      return List.of();
    }
    var listed = new ArrayList<Path>();
    URI base = listing.toUri();
    for (String entry : value.strip().split("\\s+")) {
      try {
        URI uri = base.resolve(new URI(entry));
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
          continue;
        }
        Path path = realPath(Path.of(uri));
        if (Files.isRegularFile(path)) {
          listed.add(path);
        }
      } catch (Throwable t) {
        // the JVM finds nothing there either
      }
    }
    return listed;
  }

  /** The {@code Class-Path} value of {@code jar}'s manifest, or {@code null}. */
  private static String classPathAttribute(Path jar) throws Exception {
    try (var file = new JarFile(jar.toFile(), false)) {
      Manifest manifest = file.getManifest();
      return manifest == null
          ? null
          : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
    }
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

  /** {@code path} with symbolic links resolved, or absolute and normalized if it does not exist. */
  private static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (Throwable t) {
      return path.toAbsolutePath().normalize();
    }
  }

  /**
   * The original file name from the first of {@code jars} whose manifest lists one, or {@link
   * #GENERIC_NAME}. The refusal reads the running jar first: in {@code -cp agent.jar:thin.jar} the
   * running copy is the fat-jar, and the last copy is the thin jar, which has no {@code
   * Boot-Class-Path}.
   */
  private static String expectedName(Path... jars) {
    for (Path jar : jars) {
      String name = expectedName(jar);
      if (!GENERIC_NAME.equals(name)) {
        return name;
      }
    }
    return GENERIC_NAME;
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
