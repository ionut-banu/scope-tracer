package com.ionutbanu.scopetracer.analyzer.model;

/**
 * The source location of a {@code fork()} call, captured independently of the task's display
 * label ({@link TaskRecord#taskName()}) — populated even when the label was supplied explicitly.
 *
 * @param className simple name of the declaring class (the lambda's enclosing class, or the
 *     {@link java.util.concurrent.Callable}'s own class when it's a named user class); always
 *     present.
 * @param methodName enclosing method name; present only when derived from a caller stack frame
 *     (lambda/method reference), {@code null} for a named {@code Callable} class.
 * @param line source line of the caller frame; {@code null} when unknown or not applicable.
 */
public record CallSite(String className, String methodName, Integer line) {}
