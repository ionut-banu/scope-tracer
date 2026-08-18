package com.ionutbanu.scopetracer.plugin.model;

/**
 * A parsed {@code SimpleClassName[#methodName[:line]]} label, as produced by
 * {@code TaskNameDeriver}/{@code ScopeNameDeriver} in scope-tracer-core/agent.
 *
 * @param className always present.
 * @param methodName present only when the label was derived from a lambda/method-reference call
 *     site; {@code null} for a named {@code Callable} class.
 * @param line present only when the label was derived from a call site with a known source line;
 *     {@code null} otherwise.
 */
public record CallSite(String className, String methodName, Integer line) {}
