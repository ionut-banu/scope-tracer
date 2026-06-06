package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.agent.advice.ForkAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeCloseAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeConstructorAdvice;
import com.ionutbanu.scopetracer.agent.advice.ScopeOpenAdvice;
import com.ionutbanu.scopetracer.analyzer.HtmlRenderer;
import com.ionutbanu.scopetracer.analyzer.JfrParser;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Java agent entry point for zero-code-change tracing of {@code StructuredTaskScope}.
 *
 * <p>Attach with {@code -javaagent:scope-tracer-agent-*-agent.jar}. The agent retransforms {@code
 * java.util.concurrent.StructuredTaskScope} to inject JFR event emission at every lifecycle moment
 * (scope open/close, task fork/succeed/fail/cancel). The resulting {@code .jfr} recording is
 * compatible with the standard {@code scope-tracer-analyzer} pipeline.
 *
 * <p><b>Auto-HTML generation:</b> When a JFR recording that was started with a destination file
 * (e.g. {@code jcmd JFR.start filename=/tmp/orders.jfr}) transitions to {@code STOPPED}, the agent
 * automatically parses the file and writes an HTML report alongside it ({@code /tmp/orders.html}).
 * A message is printed to {@code stderr}:
 *
 * <pre>{@code
 * [scope-tracer] HTML report written to /tmp/orders.html
 * }</pre>
 *
 * <p>If the recording contains no scope-tracer events the HTML step is skipped.
 *
 * <p><b>Agent arguments</b> ({@code -javaagent:agent.jar=key=value,...}):
 *
 * <ul>
 *   <li>{@code html=false} — disable auto-HTML generation.
 *   <li>{@code verbose} — print every instrumented class to {@code stderr} (same as {@code
 *       -Dscopetracer.agent.verbose=true}).
 *   <li>{@code output.dir=<path>} — write the HTML report to this directory instead of alongside
 *       the {@code .jfr} file. The directory is created if it does not exist; non-writable paths
 *       cause the HTML step to be skipped with a warning.
 *   <li>{@code output.suffix=<ext>} — filename suffix used when replacing {@code .jfr} (default
 *       {@code .html}). Useful for namespacing reports (e.g. {@code output.suffix=.report.html}).
 *   <li>{@code min.scopes=<N>} — skip auto-HTML generation if the recording contains fewer than N
 *       scopes. Default is 1 (any non-empty recording produces a report).
 *   <li>{@code include.name=<glob>} — only capture scopes whose name matches the glob. Patterns
 *       support {@code *}, {@code **} (both ≡ {@code .*}), and {@code ?}. Not path-aware: {@code
 *       checkout-*} matches {@code checkout-payment} and {@code checkout-foo-bar} alike.
 *   <li>{@code exclude.name=<glob>} — drop scopes whose name matches the glob. Exclude wins over
 *       include.
 *   <li>{@code include.package=<glob>} — only capture scopes opened from a class whose
 *       fully-qualified package matches the glob (e.g. {@code include.package=com.acme.**}). Fails
 *       closed: scopes opened from frames the stack walker cannot identify are dropped.
 *   <li>{@code exclude.package=<glob>} — drop scopes whose call-site package matches the glob.
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
 * <p><b>Scope naming:</b> When a scope is opened with {@code Config.withName("my-scope")}, that
 * name is used as-is. When no name is configured, the name is derived from the call-site stack
 * frame (format: {@code SimpleClassName#methodName}).
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>JDK 26+ with {@code --enable-preview} (StructuredTaskScope is a preview API).
 *   <li>The agent fat-jar is listed on {@code Boot-Class-Path} in its own manifest — this is
 *       pre-configured by the Maven build.
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
    // Allow ByteBuddy to instrument Java 26 class files even though this version of
    // ByteBuddy officially supports up to Java 23. Remove once ByteBuddy adds Java 26 support.
    System.setProperty("net.bytebuddy.experimental", "true");

    AgentConfig config = AgentConfig.parse(args);
    AgentState.FILTER = config.filter();

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
              .with(AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly());
    } else {
      agentBuilder = redefinable;
    }
    agentBuilder
        .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
        .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
        .ignore(ElementMatchers.none()) // instrument JDK classes too
        // --- open() on the public API class ---
        .type(ElementMatchers.named("java.util.concurrent.StructuredTaskScope"))
        .transform(
            (b, typeDescription, classLoader, module, protectionDomain) ->
                b.visit(
                    Advice.to(ScopeOpenAdvice.class)
                        .on(
                            ElementMatchers.named("open")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.isPublic()))))
        // --- constructor advice: captures the configured name into a ThreadLocal ---
        .type(ElementMatchers.named("java.util.concurrent.StructuredTaskScopeImpl"))
        .transform(
            (b, typeDescription, classLoader, module, protectionDomain) ->
                b.visit(
                    Advice.to(ScopeConstructorAdvice.class)
                        .on(
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
                        Advice.to(ForkAdvice.class)
                            .on(
                                ElementMatchers.named("fork")
                                    .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                    .and(ElementMatchers.isPublic())
                                    // Match only fork(Callable); fork(Runnable) delegates to it
                                    // internally so it is covered automatically.
                                    .and(
                                        ElementMatchers.takesArgument(
                                            0,
                                            ElementMatchers.named(
                                                "java.util.concurrent.Callable")))))
                    .visit(
                        Advice.to(ScopeCloseAdvice.class)
                            .on(
                                ElementMatchers.named("close")
                                    .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                    .and(ElementMatchers.isPublic()))))
        .installOn(inst);

    // Register a FlightRecorderListener that auto-generates an HTML report whenever a
    // recording with a destination file transitions to STOPPED (e.g. after jcmd JFR.stop).
    // The listener fires for ALL recordings; the maybeWriteHtml guard skips any that contain
    // no scope-tracer events, so unrelated JFR recordings produce no spurious output.
    // FlightRecorderListener has only default methods (not a functional interface), so we
    // use an anonymous class and override only recordingStateChanged.
    if (config.generateHtml()) {
      FlightRecorder.addListener(
          new FlightRecorderListener() {
            @Override
            public void recordingStateChanged(Recording recording) {
              if (recording.getState() != RecordingState.STOPPED) return;
              Path dest = recording.getDestination();
              if (dest == null) return; // no destination → no-ops (e.g. programmatic dumps)
              // Run I/O on a separate thread to avoid blocking the JFR callback thread.
              new Thread(() -> maybeWriteHtml(dest, config), "scope-tracer-html-gen").start();
            }
          });
    }
  }

  private static void maybeWriteHtml(Path dest, AgentConfig config) {
    try {
      var model = JfrParser.parse(dest);
      if (model.scopes().size() < config.minScopes()) {
        AgentLog.debug(
            "skipping auto-HTML: "
                + model.scopes().size()
                + " scope(s) is below min.scopes="
                + config.minScopes());
        return;
      }
      Path html = resolveHtmlPath(dest, config);
      Path parent = html.getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
      }
      Files.writeString(html, HtmlRenderer.render(model));
      AgentLog.info("HTML report written to " + html.toAbsolutePath());
    } catch (Exception e) {
      AgentLog.warn("auto-HTML generation failed for " + dest, e);
    }
  }

  private static Path resolveHtmlPath(Path dest, AgentConfig config) {
    String filename = dest.getFileName().toString();
    String htmlFilename =
        filename.endsWith(".jfr")
            ? filename.substring(0, filename.length() - 4) + config.outputSuffix()
            : filename + config.outputSuffix();
    return config.outputDir() != null
        ? config.outputDir().resolve(htmlFilename)
        : dest.resolveSibling(htmlFilename);
  }
}
