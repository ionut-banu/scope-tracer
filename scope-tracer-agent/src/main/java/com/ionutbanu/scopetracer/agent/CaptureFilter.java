package com.ionutbanu.scopetracer.agent;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Capture filter applied to every {@code StructuredTaskScope} instance at open time. When a scope
 * is filtered out, the agent skips ALL events for that scope (open, every fork, every completion,
 * close) — the filter decision is propagated downstream by simply <b>not</b> registering the scope
 * in {@link AgentState#SCOPE_STATES}; the existing null-state short-circuits in {@code ForkAdvice},
 * {@code ScopeCloseAdvice}, and {@code TracingCallable} do the rest.
 *
 * <p>Decision order (first reason to reject wins):
 *
 * <ol>
 *   <li>{@code excludeName} matches the scope name ⇒ reject.
 *   <li>{@code excludePackage} matches the caller package ⇒ reject.
 *   <li>{@code includeName} is configured but does not match ⇒ reject.
 *   <li>{@code includePackage} is configured but there is no caller package, or it does not match ⇒
 *       reject. Missing caller package fails closed by design.
 *   <li>{@code sampleRate < 1.0} and {@link ThreadLocalRandom#nextDouble()} is at or above the rate
 *       ⇒ reject. Sampling is evaluated last so out-of-scope traffic does not consume the sample
 *       budget.
 * </ol>
 *
 * <p>{@link #PASSTHROUGH} is the no-op default used when no filter args were supplied; its {@link
 * #shouldCapture(String, String)} method always returns {@code true} via the short-circuit checks
 * below.
 *
 * <p>This class must be accessible from the bootstrap classloader.
 */
public record CaptureFilter(
    Pattern includeName,
    Pattern excludeName,
    Pattern includePackage,
    Pattern excludePackage,
    double sampleRate) {

  /** No-op filter — captures every scope. */
  public static final CaptureFilter PASSTHROUGH = new CaptureFilter(null, null, null, null, 1.0);

  /** Returns {@code true} when no rules are configured and sampling is disabled. */
  public boolean isPassthrough() {
    return includeName == null
        && excludeName == null
        && includePackage == null
        && excludePackage == null
        && sampleRate >= 1.0;
  }

  /** Returns {@code true} when at least one package rule is configured. */
  public boolean hasPackageRules() {
    return includePackage != null || excludePackage != null;
  }

  /**
   * Returns {@code true} when the scope should be captured.
   *
   * @param name the resolved scope name (configured via {@code Config.withName(...)} or derived
   *     from the call-site).
   * @param callerPackage the package of the call-site class, or empty string if the stack walk
   *     produced no user frame.
   */
  public boolean shouldCapture(String name, String callerPackage) {
    if (excludeName != null && excludeName.matcher(name).matches()) return false;
    if (excludePackage != null
        && callerPackage != null
        && !callerPackage.isEmpty()
        && excludePackage.matcher(callerPackage).matches()) {
      return false;
    }
    if (includeName != null && !includeName.matcher(name).matches()) return false;
    if (includePackage != null) {
      if (callerPackage == null || callerPackage.isEmpty()) return false;
      if (!includePackage.matcher(callerPackage).matches()) return false;
    }
    if (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() >= sampleRate) return false;
    return true;
  }
}
