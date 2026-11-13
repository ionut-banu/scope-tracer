package com.ionutbanu.scopetracer.agent;

import java.io.Writer;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * An application that brings its own SLF4J, Logback and ByteBuddy. Invoked by {@link
 * AgentIsolationIT} in a forked JVM, with and without the agent, to show that attaching the agent
 * leaves the application's own copies of those libraries alone.
 *
 * <p>Arguments: {@code <jfr> <facts> <layout> <byteBuddyJar> [loggingJar...]}.
 *
 * <ul>
 *   <li>{@code layout} is {@code plain} (SLF4J and Logback on the class path) or {@code child} (the
 *       logging jars are loaded by a child {@link URLClassLoader}, as in Spring Boot executable
 *       jars and servlet containers).
 *   <li>{@code byteBuddyJar} is loaded by a child class loader of its own.
 *   <li>{@code facts} is a properties file the subject writes what it saw into. The subject never
 *       prints anything itself, so the process output is only the application's logging and
 *       whatever the agent adds to it.
 * </ul>
 *
 * <p>SLF4J and ByteBuddy are reached only by reflection, because in the {@code child} layout
 * neither is on the class path.
 *
 * <p>The subject records two scopes: an unnamed one that completes (named by the agent from the
 * call site) and {@link #TRUNCATED_SCOPE}, which is still open when the recording stops. The second
 * makes the analyzer log its "may have been cut short" warning while the agent writes the
 * auto-HTML.
 */
public final class IsolationTestSubject {

  static final String LOGGER_NAME = "isolation-subject";
  static final String LOG_LINE = "isolation-subject-log-line";
  static final String UNNAMED_SCOPE = "IsolationTestSubject#traceOneScope";
  static final String TRUNCATED_SCOPE = "isolation-truncated-scope";

  /** System property naming a property whose value the subject reports as {@code experimental}. */
  static final String EXPERIMENTAL_PROPERTY_KEY = "subject.experimentalProperty";

  static final String UNRELOCATED_EXPERIMENTAL_PROPERTY = "net.bytebuddy.experimental";

  /** Name of the thread {@link ScopeTracerAgent} writes the auto-HTML on. */
  private static final String HTML_THREAD = "scope-tracer-html-gen";

  private IsolationTestSubject() {}

  public static void main(String[] args) throws Exception {
    Path jfr = Path.of(args[0]);
    Path factsFile = Path.of(args[1]);
    String layout = args[2];
    Path byteBuddyJar = Path.of(args[3]);
    URL[] loggingJars = toUrls(Arrays.copyOfRange(args, 4, args.length));

    var facts = new Properties();
    ClassLoader appLoader = ClassLoader.getSystemClassLoader();
    ClassLoader loggingLoader =
        switch (layout) {
          case "plain" -> appLoader;
          case "child" -> new URLClassLoader("own-logging", loggingJars, appLoader);
          default -> throw new IllegalArgumentException("unknown layout " + layout);
        };

    logOneLine(loggingLoader, facts);
    useOwnByteBuddy(byteBuddyJar, appLoader, facts);

    String experimentalProperty = System.getProperty(EXPERIMENTAL_PROPERTY_KEY);
    if (experimentalProperty != null) {
      facts.setProperty("experimental", String.valueOf(System.getProperty(experimentalProperty)));
    }
    facts.setProperty(
        "unrelocatedExperimental",
        String.valueOf(System.getProperty(UNRELOCATED_EXPERIMENTAL_PROPERTY)));

    record(jfr);

    try (Writer out = Files.newBufferedWriter(factsFile)) {
      facts.store(out, null);
    }
  }

  private static void logOneLine(ClassLoader loader, Properties facts) throws Exception {
    Class<?> loggerFactory = Class.forName("org.slf4j.LoggerFactory", true, loader);
    Method getLogger = loggerFactory.getMethod("getLogger", String.class);
    Object logger = getLogger.invoke(null, LOGGER_NAME);
    getLogger.getReturnType().getMethod("info", String.class).invoke(logger, LOG_LINE);

    facts.setProperty("slf4j.loader", describe(loggerFactory.getClassLoader(), loader));
    facts.setProperty(
        "slf4j.factory",
        loggerFactory.getMethod("getILoggerFactory").invoke(null).getClass().getName());
  }

  private static void useOwnByteBuddy(Path jar, ClassLoader parent, Properties facts)
      throws Exception {
    var loader = new URLClassLoader("own-bytebuddy", toUrls(jar.toString()), parent);
    Class<?> byteBuddy = Class.forName("net.bytebuddy.ByteBuddy", true, loader);
    facts.setProperty("bytebuddy.loader", describe(byteBuddy.getClassLoader(), loader));

    // Use it, not only load it: generate a subclass of Object and count its bytes.
    Object instance = byteBuddy.getConstructor().newInstance();
    Object builder = byteBuddy.getMethod("subclass", Class.class).invoke(instance, Object.class);
    Object unloaded =
        Class.forName("net.bytebuddy.dynamic.DynamicType$Builder", false, loader)
            .getMethod("make")
            .invoke(builder);
    byte[] bytes =
        (byte[])
            Class.forName("net.bytebuddy.dynamic.DynamicType", false, loader)
                .getMethod("getBytes")
                .invoke(unloaded);
    facts.setProperty("bytebuddy.generatedBytes", String.valueOf(bytes.length));
  }

  private static void record(Path jfr) throws Exception {
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.setDestination(jfr);
      recording.start();

      traceOneScope();

      var release = new CountDownLatch(1);
      try (var open =
          StructuredTaskScope.open(
              Joiner.awaitAllSuccessfulOrThrow(), c -> c.withName(TRUNCATED_SCOPE))) {
        open.fork(
            () -> {
              release.await();
              return "released";
            });
        // Stopped while TRUNCATED_SCOPE is open: the recording has its open and fork but no
        // close, which is what makes the analyzer warn while the agent writes the auto-HTML.
        recording.stop();
        awaitAutoHtml();
        release.countDown();
        open.join();
      }
    }
  }

  private static void traceOneScope() throws InterruptedException, ExecutionException {
    // The two-argument open: the shorter overloads are recorded twice until #51 is fixed.
    try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), c -> c)) {
      scope.fork(() -> "ok");
      scope.join();
    }
  }

  /**
   * Joins the agent's auto-HTML thread, which {@code recording.stop()} has already started when the
   * agent is attached. Without the agent there is none and this returns at once.
   */
  private static void awaitAutoHtml() throws InterruptedException {
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (HTML_THREAD.equals(thread.getName())) {
        thread.join();
      }
    }
  }

  private static String describe(ClassLoader actual, ClassLoader expected) {
    if (actual == null) {
      return "bootstrap";
    }
    return actual == expected ? "expected" : "other:" + actual;
  }

  private static URL[] toUrls(String... paths) throws MalformedURLException {
    URL[] urls = new URL[paths.length];
    for (int i = 0; i < paths.length; i++) {
      urls[i] = Path.of(paths[i]).toUri().toURL();
    }
    return urls;
  }
}
