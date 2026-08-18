package com.ionutbanu.scopetracer.plugin.model;

import java.util.regex.Pattern;

/**
 * Parses the {@code SimpleClassName[#methodName[:line]]} label convention shared by
 * scope-tracer-core's and scope-tracer-agent's {@code TaskNameDeriver}/{@code ScopeNameDeriver}.
 *
 * <p>Recordings produced after structured call-site capture was added carry a real {@link
 * CallSite} directly on {@link PluginTaskRecord#callSite()} — prefer {@link
 * #forTask(PluginTaskRecord)}, which uses that when present. {@link #parse(String)} remains the
 * fallback for older recordings, where an explicitly-named fork's call site is unrecoverable from
 * {@code taskName} alone (see the lowercase-rejection rule below).
 */
public final class CallSiteParser {

  private static final Pattern PATTERN =
      Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)(?:#([A-Za-z_$][A-Za-z0-9_$]*))?(?::(\\d+))?");

  private CallSiteParser() {}

  /**
   * Resolves the navigable {@link CallSite} for {@code task}: the structured {@link
   * PluginTaskRecord#callSite()} when present, otherwise a best-effort fallback that parses {@link
   * PluginTaskRecord#taskName()} via {@link #parse(String)}.
   *
   * @return the resolved {@link CallSite}, or {@code null} if neither source yields one.
   */
  public static CallSite forTask(PluginTaskRecord task) {
    if (task.callSite() != null) {
      return task.callSite();
    }
    return parse(task.taskName());
  }

  /** Returns the parsed {@link CallSite}, or {@code null} if {@code raw} doesn't match. */
  public static CallSite parse(String raw) {
    if (raw == null) {
      return null;
    }
    var matcher = PATTERN.matcher(raw);
    if (!matcher.matches()) {
      return null;
    }
    var className = matcher.group(1);
    var methodName = matcher.group(2);
    // A bare identifier (no #method suffix) is ambiguous: TaskNameDeriver emits it for a named
    // Callable class (PascalCase by convention), but scope.fork(String, Callable) emits an
    // arbitrary hand-picked label the same way (camelCase by convention — every example in the
    // README and real usage). Only the former is a real class worth a PSI lookup; treat a
    // lowercase-starting bare identifier as an unparseable label instead.
    if (methodName == null && !Character.isUpperCase(className.charAt(0))) {
      return null;
    }
    var line = matcher.group(3);
    return new CallSite(className, methodName, line == null ? null : Integer.valueOf(line));
  }
}
