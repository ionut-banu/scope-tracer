package com.ionutbanu.scopetracer.plugin.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The source location of a fork, in one of two forms the navigator must not confuse:
 *
 * <ul>
 *   <li><b>exact</b> - from the structured {@code callSite} of the analyzer's JSON (recordings that
 *       carry {@code TaskForked}'s {@code callSite*} fields). {@code className} is the fully
 *       qualified JVM binary name, uncut ({@code com.acme.Plain$Named}, {@code com.acme.Plain$1});
 *       a class in the unnamed package has no dot, so a dot cannot tell the forms apart.
 *   <li><b>short name</b> - parsed from a {@code SimpleClassName[#methodName[:line]]} task label
 *       (recordings without the fields). {@code className} is a simple name that may match several
 *       classes.
 * </ul>
 *
 * @param className the binary name when {@code exact}, otherwise a simple name; always present.
 * @param methodName present only when the call site came from a lambda/method-reference caller
 *     frame; {@code null} for a named {@code Callable} class.
 * @param line present only when the call site has a known source line; {@code null} otherwise.
 * @param exact {@code true} for a structured call site, {@code false} for one parsed from a label.
 */
public record CallSite(String className, String methodName, Integer line, boolean exact) {

  /** A short-name call site, as parsed from a task label. */
  public CallSite(String className, String methodName, Integer line) {
    this(className, methodName, line, false);
  }

  /** An exact call site, from the structured {@code callSite} of the JSON. */
  public static CallSite exact(String className, String methodName, Integer line) {
    return new CallSite(className, methodName, line, true);
  }

  /** This call site marked exact. */
  public CallSite asExact() {
    return exact ? this : exact(className, methodName, line);
  }

  /**
   * The fully qualified source names to look up for an exact call site, best first. The binary name
   * is cut before its first anonymous or local class segment (a {@code $} followed by a digit:
   * {@code $1}, {@code $1Local}), since those have no name to look up and the enclosing class is
   * the nearest one that does; then {@code $} becomes {@code .} for nested classes. So {@code
   * com.acme.Plain$Named} gives {@code com.acme.Plain.Named}, {@code com.acme.Plain$1} and {@code
   * com.acme.Plain$1Local} give {@code com.acme.Plain}. When the cut name still has a {@code $} it
   * is offered second as it is, for a class whose own name contains one.
   *
   * @return the names to try in order; empty for a short-name call site.
   */
  public List<String> lookupNames() {
    if (!exact) {
      return List.of();
    }
    String named = className;
    int lastDot = className.lastIndexOf('.');
    for (int i = className.indexOf('$', lastDot + 1); i >= 0; i = className.indexOf('$', i + 1)) {
      if (i + 1 < className.length() && Character.isDigit(className.charAt(i + 1))) {
        named = className.substring(0, i);
        break;
      }
    }
    var names = new ArrayList<String>();
    names.add(named.replace('$', '.'));
    if (named.indexOf('$') >= 0) {
      names.add(named);
    }
    return List.copyOf(names);
  }
}
