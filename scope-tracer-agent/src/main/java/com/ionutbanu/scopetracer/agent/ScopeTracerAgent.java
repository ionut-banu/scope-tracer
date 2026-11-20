package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.agent.advice.ForkAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeCloseAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeConstructorAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeOpenAdvice;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import jdk.jfr.FlightRecorder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Java agent entry point for zero-code-change tracing of {@code StructuredTaskScope}.
 *
 * <p>Attach with {@code -javaagent:scope-tracer-agent-*-agent.jar}. The agent retransforms {@code
 * java.util.concurrent.StructuredTaskScope} to inject JFR event emission at every lifecycle moment
 * (scope open/close, task fork/succeed/fail/cancel). The resulting {@code .jfr} recording is
 * compatible with the standard {@code scope-tracer-analyzer} pipeline.
 *
 * <p><b>One flag:</b> with the {@code record} argument the agent starts a JFR recording of its own,
 * so nothing else is needed to get a trace and its report:
 *
 * <pre>{@code
 * java --enable-preview -javaagent:scope-tracer-agent-<v>-agent.jar=record -jar app.jar
 * [scope-tracer] recording to /work/scope-tracer-4242-2026_11_14_10_30_00.jfr
 * ...
 * [scope-tracer] HTML report written to /work/scope-tracer-4242-2026_11_14_10_30_00.html
 * }</pre>
 *
 * <p>The recording is named {@code scope-tracer}, holds only scope-tracer events, is capped at 250
 * MB and is written when the JVM exits (or at {@code jcmd <pid> JFR.stop name=scope-tracer}).
 *
 * <p><b>Auto-HTML generation:</b> When a JFR recording that has a destination file transitions to
 * {@code STOPPED}, the agent parses the file and writes an HTML report alongside it ({@code
 * /tmp/orders.jfr} gives {@code /tmp/orders.html}). That happens when the recording is stopped with
 * {@code jcmd <pid> JFR.stop} or {@code Recording.stop()}, and when the JVM exits for a recording
 * with {@code dumponexit}: {@code -XX:StartFlightRecording=filename=...,dumponexit=true} or the
 * agent's own {@code record}. At exit the report is written before the JVM halts, so a large
 * recording delays exit; {@code html=false} turns it off. Once written, a message is printed to
 * {@code stderr}:
 *
 * <pre>{@code
 * [scope-tracer] HTML report written to /tmp/orders.html
 * }</pre>
 *
 * <p>If the recording contains no scope-tracer events the HTML step is skipped. A JVM killed
 * outright ({@code kill -9}, {@code Runtime.halt}) runs no shutdown hooks and gets no report; a
 * report is never left half-written.
 *
 * <p><b>Agent arguments</b> ({@code -javaagent:agent.jar=key=value,...}). Keys are exact and
 * case-sensitive. An unknown key, a value with no key, a boolean ({@code record}, {@code html},
 * {@code verbose}: {@code true} or {@code false} ignoring case, the bare key meaning {@code true})
 * with another value, a key that needs a value and has none, and a number out of range each print
 * one {@code [scope-tracer] WARN} line and the agent carries on with the other arguments:
 *
 * <ul>
 *   <li>{@code record} or {@code record=<path>} — start the agent's own recording. Without a path
 *       (or with an existing directory) the file is {@code
 *       scope-tracer-<pid>-<yyyy_MM_dd_HH_mm_ss>.jfr} in the working directory (or that directory),
 *       never written over an existing file; a file path is used as is, its parent directories
 *       created and an existing file replaced. {@code record=true} is {@code record}; {@code
 *       record=false} records nothing of the agent's own, silently. A location that cannot be
 *       written gives a warning and no recording; the program still runs and is still instrumented.
 *   <li>{@code html=false} — disable auto-HTML generation, on {@code JFR.stop} and at exit.
 *   <li>{@code verbose} — print every instrumented class to {@code stderr}, and why an auto-HTML
 *       report was skipped (same as {@code -Dscopetracer.agent.verbose=true}); {@code
 *       verbose=false} turns all of it off, even with the property.
 *   <li>{@code output.dir=<path>} — write the HTML report to this directory instead of alongside
 *       the {@code .jfr} file. The directory is created if it does not exist; non-writable paths
 *       cause the HTML step to be skipped with a warning.
 *   <li>{@code output.suffix=<ext>} — filename suffix used when replacing {@code .jfr} (default
 *       {@code .html}). Useful for namespacing reports (e.g. {@code output.suffix=.report.html}).
 *   <li>{@code min.scopes=<N>} — skip auto-HTML generation if the recording contains fewer than N
 *       scopes. Default is 1 (any non-empty recording produces a report).
 *   <li>{@code include.name=<glob>} — only capture scopes whose name matches the glob. Patterns
 *       support {@code *}, {@code **} (both ≡ {@code .*}, any characters including dots), and
 *       {@code ?} (one character). Not path-aware: {@code checkout-*} matches {@code
 *       checkout-payment} and {@code checkout-foo-bar} alike. A glob is matched against the whole
 *       name.
 *   <li>{@code exclude.name=<glob>} — drop scopes whose name matches the glob. Exclude wins over
 *       include.
 *   <li>{@code include.package=<glob>} — only capture scopes opened from a class whose
 *       fully-qualified package matches the glob. The glob is matched against the whole package
 *       name, and {@code *} and {@code **} match any characters including dots: {@code
 *       com.acme.checkout*} matches {@code com.acme.checkout}, its sub-packages and also a sibling
 *       such as {@code com.acme.checkoutx}; {@code com.acme.**} matches everything under {@code
 *       com.acme.} and not {@code com.acme} itself; a literal such as {@code com.acme.checkout}
 *       matches that one package. No glob means "this package and everything below it". Fails
 *       closed: scopes opened from frames the stack walker cannot identify are dropped.
 *   <li>{@code exclude.package=<glob>} — drop scopes whose call-site package matches the glob, with
 *       the same matching rules ({@code exclude.package=com.acme.checkout*} also drops {@code
 *       com.acme.checkoutx}).
 *   <li>{@code sample.rate=<0.0-1.0>} — sample only this fraction of scopes that survive
 *       include/exclude filtering. {@code 1.0} (default) captures every surviving scope; {@code
 *       0.0} drops everything; {@code 0.01} keeps roughly 1%. Sampling is evaluated last so
 *       out-of-scope traffic does not consume the sample budget. Sampling is per-scope and
 *       independent — a sampled-in parent may have a sampled-out child, which renders as an orphan
 *       task in the parent's view.
 * </ul>
 *
 * <p>Filtered or sampled-out scopes produce <b>zero</b> JFR events for their entire lifetime (open,
 * every fork, every completion, close). The filter decision is made once at scope-open time and
 * propagated through the rest of the pipeline by simply not registering the scope in the agent's
 * state map; downstream advice handlers short-circuit on the missing state.
 *
 * <p><b>Scope naming:</b> When a scope is opened with {@code Configuration.withName("my-scope")},
 * that name is used as-is. When no name is configured, the name is derived from the call-site stack
 * frame (format: {@code SimpleClassName#methodName}).
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>JDK 27+ with {@code --enable-preview} (StructuredTaskScope is a preview API).
 *   <li>The agent fat-jar is listed on {@code Boot-Class-Path} in its own manifest — this is
 *       pre-configured by the Maven build.
 *   <li>The jar can be renamed or copied ({@code -javaagent:/opt/agent.jar}). Under a name other
 *       than {@code scope-tracer-agent-<v>-agent.jar} the agent adds the jar to the bootstrap class
 *       path itself and prints one line saying so:
 *       <pre>{@code
 * [scope-tracer] added /opt/agent.jar to the bootstrap class path, because the jar is not named scope-tracer-agent-<v>-agent.jar
 * }</pre>
 *       The JVM then prints its own {@code Sharing is only supported for boot loader classes
 *       because bootstrap classpath has been appended} warning. Keeping the original name, or
 *       adding {@code -Xbootclasspath/a:<path to the jar>} beside {@code -javaagent}, avoids it.
 *   <li>Under a renamed jar, a scope-tracer jar of this version or later also on {@code -cp} (the
 *       thin jar, or another copy of the fat-jar) does not take over: the {@code -javaagent} jar is
 *       the one added and the one that instruments, and the agent names the jar it passed over:
 *       <pre>{@code
 * [scope-tracer] ignoring /app/lib/scope-tracer-agent-<v>.jar on the class path: the -javaagent jar is /opt/agent.jar
 * }</pre>
 *       When the {@code -javaagent} jar is itself on {@code -cp} together with any other
 *       scope-tracer jar, in either order ({@code -cp agent.jar:thin.jar}, {@code -cp
 *       thin.jar:agent.jar}, {@code -cp a.jar:agent.jar:b.jar}), the agent cannot tell which is its
 *       own and traces nothing, with one {@code WARN not tracing} line naming the original file
 *       name and {@code -Xbootclasspath/a:} as fixes. An earlier release's jar on {@code -cp}
 *       (0.3.x or before) still takes over: its own {@code premain} is the one the JVM calls. A
 *       scope-tracer jar reached only through another jar's manifest {@code Class-Path} is not
 *       handled yet (#158).
 * </ul>
 *
 * <p><b>Limitations:</b>
 *
 * <ul>
 *   <li>Do not combine with {@link com.ionutbanu.scopetracer.core.TracedScope} — duplicate events
 *       will be emitted for the same scope.
 *   <li>{@code fork(Runnable)} tasks are traced transparently: the JDK delegates {@code
 *       fork(Runnable)} to {@code fork(Callable)} internally, so the existing {@code
 *       fork(Callable)} advice covers both overloads.
 *   <li>JDK 26 and later (JDK-XXXXXXX): a JVM that gets SIGTERM, SIGINT or SIGHUP during the first
 *       ~200 ms of {@code premain} can hang until {@code kill -9}. The JVM's exit path waits in
 *       {@code JvmtiExport::post_vm_death} for a JVMTI callback that is itself waiting for a
 *       handshake. {@code premain} does not defend against it; any agent that loads many classes
 *       there triggers it, and JDK 21 is not affected. A signal sent later exits normally.
 * </ul>
 */
public final class ScopeTracerAgent {

  private ScopeTracerAgent() {}

  /**
   * Agent premain hook — called by the JVM before the application's {@code main} method.
   *
   * @param args agent arguments (comma-separated {@code key=value} pairs; see class Javadoc).
   * @param inst the JVM instrumentation handle.
   */
  public static void premain(String args, Instrumentation inst) {
    // Not loaded by the bootstrap loader: the jar is not under the name Boot-Class-Path lists. Put
    // it on the bootstrap path and let the bootstrap loader's copy of this class do the work; this
    // copy does nothing else, or every argument warning would be printed twice (#113).
    if (ScopeTracerAgent.class.getClassLoader() != null) {
      try {
        BootstrapEntry.enter(
            args, inst, ScopeTracerAgent.class.getProtectionDomain().getCodeSource());
      } catch (Throwable t) {
        // enter never throws; this is for the getProtectionDomain() call
      }
      return;
    }
    start(args, inst, null, Instrumenter::jarLocator);
  }

  /**
   * What {@link BootstrapEntry} calls, by reflection, on the bootstrap loader's copy once it has
   * appended {@code appendedJar} to the bootstrap search path. Not public: the path is handed over
   * as an argument, with no system property, no public method and no static field written from the
   * application class loader's copy.
   *
   * @param appendedJar the jar {@code BootstrapEntry} appended; the advice bytes are read from it.
   */
  static void premainFromAppendedJar(String args, Instrumentation inst, Path appendedJar) {
    start(args, inst, appendedJar, Instrumenter::jarLocator);
  }

  /** How the advice bytes are read from an appended jar; a test can replace it. */
  @FunctionalInterface
  interface JarLocators {
    ClassFileLocator open(Path jar) throws IOException;
  }

  /**
   * The real {@code premain}. {@code appendedJar} is {@code null} when the JVM put the jar on the
   * bootstrap class path itself ({@code Boot-Class-Path} or {@code -Xbootclasspath/a:}); {@code
   * jarLocators} then goes unused.
   */
  static void start(String args, Instrumentation inst, Path appendedJar, JarLocators jarLocators) {
    // premain must never throw: an exception out of it aborts JVM startup. Each step that fails
    // loses what it would have added (tracing, reports, the agent's recording) and nothing else.
    AgentConfig config;
    try {
      // The pinned ByteBuddy (<bytebuddy.version>) reads class files up to Java 27 (major 71) on
      // its own: on JDK 27 the agent ITs pass without this property (checked for #54), and only
      // AgentIsolationIT, which asserts the property, fails. It is kept so a JDK newer than the
      // ByteBuddy release (28 early-access builds, #55) is still instrumented rather than refused.
      // The fat-jar relocates ByteBuddy, and Shade rewrites this inlined constant exactly as it
      // does inside ByteBuddy: the agent sets the relocated name its own ByteBuddy reads and
      // leaves the application's ByteBuddy alone. AgentIsolationIT checks it.
      System.setProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY, "true");

      config = AgentConfig.parse(args);
      AgentState.FILTER = config.filter();
      // Before the auto-HTML listener is registered, so the html-gen and shutdown-hook threads see
      // it.
      AgentLog.setVerbose(config.verbose());
    } catch (Throwable t) {
      AgentLog.warn("not tracing: agent startup failed: " + AgentLog.describe(t));
      return;
    }
    try {
      Instrumenter.instrument(config, inst, appendedJar, jarLocators);
    } catch (Throwable t) {
      AgentLog.warn("not tracing: instrumentation failed: " + AgentLog.describe(t));
    }
    // A report for every recording with a destination that stops: after jcmd JFR.stop or a
    // programmatic stop(), and at JVM exit for a dumponexit recording. The listener fires for all
    // recordings; AutoHtml skips any that hold no scope-tracer events.
    if (config.generateHtml()) {
      try {
        FlightRecorder.addListener(AutoHtml.listener(config));
      } catch (Throwable t) {
        AgentLog.warn("no auto-HTML: " + AgentLog.describe(t));
      }
    }
    // Last, so the recording starts after the instrumentation is in place and still before main.
    AgentRecording.startIfRequested(config);
  }

  /**
   * Everything that uses ByteBuddy, and with it every method that names a ByteBuddy type in its
   * signature. They live here, not in {@code ScopeTracerAgent}, because the JVM looks {@code
   * premain} up with {@code getDeclaredMethod}, which resolves the signature of every method the
   * class declares: a copy of {@code ScopeTracerAgent} loaded from a jar with no ByteBuddy beside
   * it (the thin {@code scope-tracer-agent-<v>.jar} on {@code -cp}) would otherwise throw {@code
   * NoClassDefFoundError} there, before {@code premain} could catch anything, and abort the JVM
   * (#128). This class is loaded only by the copy that instruments. {@code ScopeTracerAgentTest}
   * checks that no method of {@code ScopeTracerAgent} names a ByteBuddy type.
   */
  private static final class Instrumenter {

    private Instrumenter() {}

    static void instrument(
        AgentConfig config, Instrumentation inst, Path appendedJar, JarLocators jarLocators)
        throws Exception {
      // Where the advice bytes are read from after the advice class's own loader. Under a renamed
      // jar that is the jar BootstrapEntry appended, never the system class loader: -cp may hold
      // other classes under the advice classes' names (#123).
      ClassFileLocator adviceSource;
      if (appendedJar == null) {
        adviceSource = ClassFileLocator.ForClassLoader.ofSystemLoader();
      } else {
        try {
          adviceSource = jarLocators.open(appendedJar);
        } catch (Throwable t) {
          AgentLog.warn(
              "not tracing: cannot read the advice classes from "
                  + AgentLog.path(appendedJar)
                  + ": "
                  + AgentLog.describe(t));
          return;
        }
      }
      try {
        install(config, inst, adviceSource);
      } finally {
        // Every Advice is built, its bytes read, before install returns, so nothing reads the jar
        // after this; the JVM keeps its own handle on it for class loading.
        if (appendedJar != null) closeQuietly(adviceSource);
      }
    }

    static ClassFileLocator jarLocator(Path jar) throws IOException {
      return ClassFileLocator.ForJarFile.of(jar.toFile());
    }

    /**
     * Closes the jar locator and ignores a failure: by then tracing is on (or {@code install}'s own
     * exception is on its way to the caller), and a failed close loses one file handle and nothing
     * else. Reporting it would print {@code not tracing} while the agent traces, or hide the
     * exception that did stop it (#121, from #123's QA).
     */
    static void closeQuietly(ClassFileLocator locator) {
      try {
        locator.close();
      } catch (Throwable ignored) {
        // nothing is lost but the handle
      }
    }

    static void install(AgentConfig config, Instrumentation inst, ClassFileLocator adviceSource) {
      // Built once, here, before installOn: Advice.to reads the advice class's bytes when it is
      // called and keeps them. StructuredTaskScope is first loaded after premain has returned, so
      // an
      // Advice built inside the transformers would read the jar after it was closed.
      Advice openAdvice = advice(ScopeOpenAdvice.class, adviceSource);
      Advice constructorAdvice = advice(ScopeConstructorAdvice.class, adviceSource);
      Advice forkAdvice = advice(ForkAdvice.class, adviceSource);
      Advice closeAdvice = advice(ScopeCloseAdvice.class, adviceSource);

      // Fork/close are implemented in StructuredTaskScopeImpl (the non-public concrete class
      // returned by open()), not in the public StructuredTaskScope API class. We match the
      // public class for the static open() factory and the impl class for fork()/close().
      var forkAndCloseMatcher =
          ElementMatchers.namedOneOf(
              "java.util.concurrent.StructuredTaskScope",
              "java.util.concurrent.StructuredTaskScopeImpl");

      // Verbose logging (every instrumented class printed to stderr) is disabled by default.
      // Enable with -Dscopetracer.agent.verbose=true or the verbose agent arg.
      // .with(RETRANSFORMATION) returns a RedefinitionListenable subtype that accepts a
      // RedefinitionStrategy.Listener — a different overload from AgentBuilder.with(Listener).
      // We must add the redefinition listener before transitioning back to AgentBuilder.
      var redefinable =
          new AgentBuilder.Default().with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
      AgentBuilder agentBuilder;
      if (config.verbose()) {
        agentBuilder =
            redefinable
                .with(AgentBuilder.RedefinitionStrategy.Listener.StreamWriting.toSystemError())
                .with(
                    AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly());
      } else {
        agentBuilder = redefinable;
      }
      // Always, beside the verbose listeners: one WARN line per scope class ByteBuddy cannot
      // transform, which is otherwise silent without verbose (#121).
      agentBuilder
          .with(TransformFailureListener.INSTANCE)
          .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
          .ignore(ElementMatchers.none()) // instrument JDK classes too
          // --- every public static open overload on the public API class ---
          // open() and open(Joiner) delegate to open(Joiner, UnaryOperator), so one call leaves
          // several advised frames with the same scope. ScopeOpenAdvice acts on the first exit for
          // an instance only (AgentState.OPENED_SCOPES), so the overload list and which overload
          // the JDK delegates to do not matter.
          .type(ElementMatchers.named("java.util.concurrent.StructuredTaskScope"))
          .transform(
              (b, typeDescription, classLoader, module, protectionDomain) ->
                  b.visit(
                      openAdvice.on(
                          ElementMatchers.named("open")
                              .and(ElementMatchers.isStatic())
                              .and(ElementMatchers.isPublic()))))
          // --- constructor advice: captures the configured name into a ThreadLocal ---
          .type(ElementMatchers.named("java.util.concurrent.StructuredTaskScopeImpl"))
          .transform(
              (b, typeDescription, classLoader, module, protectionDomain) ->
                  b.visit(
                      constructorAdvice.on(
                          ElementMatchers.isConstructor()
                              .and(ElementMatchers.takesArguments(3))
                              .and(
                                  ElementMatchers.takesArgument(
                                      2, ElementMatchers.named("java.lang.String"))))))
          // --- fork() and close() on both the API class and its concrete impl ---
          .type(forkAndCloseMatcher)
          .transform(
              (b, typeDescription, classLoader, module, protectionDomain) ->
                  b.visit(
                          forkAdvice.on(
                              ElementMatchers.named("fork")
                                  .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                  .and(ElementMatchers.isPublic())
                                  // Match only fork(Callable); fork(Runnable) delegates to it
                                  // internally so it is covered automatically.
                                  .and(
                                      ElementMatchers.takesArgument(
                                          0,
                                          ElementMatchers.named("java.util.concurrent.Callable")))))
                      .visit(
                          closeAdvice.on(
                              ElementMatchers.named("close")
                                  .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                  .and(ElementMatchers.isPublic()))))
          .installOn(inst);
    }

    /**
     * {@code Advice.to(type)}, reading the advice class's bytes from its own loader first and from
     * {@code second} second. Under the original name, or with {@code -Xbootclasspath/a:}, the
     * bootstrap loader finds them in the jar the JVM put on the bootstrap class path before the VM
     * started, and {@code second} is the system class loader, exactly as before. A jar appended at
     * runtime by {@link BootstrapEntry} defines classes, but its resources are not visible through
     * the bootstrap loader; {@code second} is then that jar itself, read through {@code
     * ClassFileLocator.ForJarFile}. It is never the system class loader under a renamed jar: that
     * loader searches {@code -cp} first, and an advice class there would be inlined in place of the
     * agent's, or, with another method name or descriptor, make the JDK reject {@code
     * StructuredTaskScope} with a {@code ClassFormatError} (#123).
     */
    static Advice advice(Class<?> type, ClassFileLocator second) {
      return Advice.to(
          type,
          new ClassFileLocator.Compound(
              ClassFileLocator.ForClassLoader.of(type.getClassLoader()), second));
    }
  }
}
