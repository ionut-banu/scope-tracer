package com.ionutbanu.scopetracer.analyzer.model;

/**
 * The source location of a {@code fork()} call, captured independently of the task's display label
 * ({@link TaskRecord#taskName()}) — populated even when the label was supplied explicitly.
 *
 * <p>Read from {@code TaskForked}'s {@code callSite*} fields, which {@code TracedScope} and the
 * agent stamp the same way. Recordings without the fields (0.1.0, 0.2.0) or with them unset (the
 * agent before 0.3.0) have no call site.
 *
 * @param className fully qualified name of the class, as the JVM binary name, uncut: the class
 *     whose method called {@code fork} for a lambda or method reference, or the {@link
 *     java.util.concurrent.Callable}'s own class when it's a named user class ({@code
 *     com.acme.Plain$Named}, {@code com.acme.Plain$1}; no dot in the unnamed package); always
 *     present.
 * @param methodName enclosing method name; present only when derived from a caller stack frame
 *     (lambda/method reference), {@code null} for a named {@code Callable} class.
 * @param line source line of the caller frame; {@code null} when unknown or not applicable.
 */
public record CallSite(String className, String methodName, Integer line) {}
