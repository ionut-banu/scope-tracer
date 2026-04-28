package dev.scopetracer.agent;

import dev.scopetracer.agent.advice.ForkAdvice;
import dev.scopetracer.agent.advice.ScopeCloseAdvice;
import dev.scopetracer.agent.advice.ScopeOpenAdvice;
import java.lang.instrument.Instrumentation;
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
 *   <li>Scope names are derived from the call-site stack frame (format: {@code
 *       SimpleClassName#methodName}); they are not the names passed to {@code Config.withName()}.
 * </ul>
 */
public final class ScopeTracerAgent {

  private ScopeTracerAgent() {}

  /**
   * Agent premain hook — called by the JVM before the application's {@code main} method.
   *
   * @param args agent arguments (currently unused).
   * @param inst the JVM instrumentation handle.
   */
  public static void premain(String args, Instrumentation inst) {
    // Allow ByteBuddy to instrument Java 26 class files even though this version of
    // ByteBuddy officially supports up to Java 23. Remove once ByteBuddy adds Java 26 support.
    System.setProperty("net.bytebuddy.experimental", "true");

    // Fork/close are implemented in StructuredTaskScopeImpl (the non-public concrete class
    // returned by open()), not in the public StructuredTaskScope API class. We match the
    // public class for the static open() factory and the impl class for fork()/close().
    var forkAndCloseMatcher =
        ElementMatchers.namedOneOf(
            "java.util.concurrent.StructuredTaskScope",
            "java.util.concurrent.StructuredTaskScopeImpl");

    new AgentBuilder.Default()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.RedefinitionStrategy.Listener.StreamWriting.toSystemError())
        .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
        .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
        .with(AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly())
        .ignore(ElementMatchers.none()) // instrument JDK classes too
        // --- open() on the public API class ---
        .type(ElementMatchers.named("java.util.concurrent.StructuredTaskScope"))
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(
                    Advice.to(ScopeOpenAdvice.class)
                        .on(
                            ElementMatchers.named("open")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.isPublic()))))
        // --- fork() and close() on both the API class and its concrete impl ---
        .type(forkAndCloseMatcher)
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) ->
                builder
                    .visit(
                        Advice.to(ForkAdvice.class)
                            .on(
                                ElementMatchers.named("fork")
                                    .and(ElementMatchers.not(ElementMatchers.isStatic()))
                                    .and(ElementMatchers.isPublic())
                                    // Match only fork(Callable), not fork(Runnable)
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
  }
}
