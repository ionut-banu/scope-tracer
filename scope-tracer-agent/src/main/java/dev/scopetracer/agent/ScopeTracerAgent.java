package dev.scopetracer.agent;

import dev.scopetracer.agent.advice.ForkAdvice;
import dev.scopetracer.agent.advice.ScopeCloseAdvice;
import dev.scopetracer.agent.advice.ScopeConstructorAdvice;
import dev.scopetracer.agent.advice.ScopeOpenAdvice;
import dev.scopetracer.analyzer.HtmlRenderer;
import dev.scopetracer.analyzer.JfrParser;
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
 * [scope-tracer] HTML → /tmp/orders.html
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
 * </ul>
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
 *   <li>Do not combine with {@link dev.scopetracer.core.TracedScope} — duplicate events will be
 *       emitted for the same scope.
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

    // Parse agent arguments.
    boolean verbose = Boolean.getBoolean("scopetracer.agent.verbose");
    boolean generateHtml = true;
    if (args != null) {
      for (String token : args.split(",")) {
        String[] kv = token.split("=", 2);
        switch (kv[0].trim()) {
          case "verbose" -> verbose = true;
          case "html" -> generateHtml = kv.length < 2 || !kv[1].trim().equalsIgnoreCase("false");
          default -> {} // ignore unknown keys
        }
      }
    }

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
    if (verbose) {
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
    // Register a FlightRecorderListener that auto-generates an HTML report whenever a
    // recording with a destination file transitions to STOPPED (e.g. after jcmd JFR.stop).
    // The listener fires for ALL recordings; the maybeWriteHtml guard skips any that contain
    // no scope-tracer events, so unrelated JFR recordings produce no spurious output.
    // FlightRecorderListener has only default methods (not a functional interface), so we
    // use an anonymous class and override only recordingStateChanged.
    if (generateHtml) {
      FlightRecorder.addListener(
          new FlightRecorderListener() {
            @Override
            public void recordingStateChanged(Recording recording) {
              if (recording.getState() != RecordingState.STOPPED) return;
              Path dest = recording.getDestination();
              if (dest == null) return; // no destination → no-ops (e.g. programmatic dumps)
              // Run I/O on a separate thread to avoid blocking the JFR callback thread.
              new Thread(() -> maybeWriteHtml(dest), "scope-tracer-html-gen").start();
            }
          });
    }
  }

  private static void maybeWriteHtml(Path dest) {
    try {
      var model = JfrParser.parse(dest);
      if (model.scopes().isEmpty()) return; // not a scope-tracer recording — skip
      String filename = dest.getFileName().toString();
      Path html =
          dest.resolveSibling(
              filename.endsWith(".jfr") ? filename.replace(".jfr", ".html") : filename + ".html");
      Files.writeString(html, HtmlRenderer.render(model));
      System.err.println("[scope-tracer] HTML → " + html.toAbsolutePath());
    } catch (Exception e) {
      System.err.println("[scope-tracer] auto-HTML error: " + e.getMessage());
    }
  }
}
