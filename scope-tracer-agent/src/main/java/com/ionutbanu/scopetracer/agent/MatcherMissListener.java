package com.ionutbanu.scopetracer.agent;

import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Prints one {@code [scope-tracer] WARN cannot instrument <class>, the trace will be incomplete: no
 * method matched <advice>} line when ByteBuddy transformed {@code
 * java.util.concurrent.StructuredTaskScope} or {@code java.util.concurrent.StructuredTaskScopeImpl}
 * but one of the agent's advices matched no method with code in it (issue #130). ByteBuddy reports
 * such a transformation as a success, exactly like a good one, so without this a JDK that changed
 * an advised method's signature gave a quietly partial trace:
 *
 * <pre>{@code
 * [scope-tracer] WARN cannot instrument java.util.concurrent.StructuredTaskScopeImpl, the trace will be incomplete: no method matched the constructor (Joiner, ThreadFactory, String)
 * }</pre>
 *
 * <p>The check runs when ByteBuddy reports the transformation ({@code onTransformation}), on the
 * thread that loads or retransforms the class: it applies the advices' own matchers, {@link #OPEN},
 * {@link #CONSTRUCTOR}, {@link #FORK} and {@link #CLOSE}, to the methods of the type ByteBuddy just
 * transformed, counting only methods that are not abstract. {@code premain} does no work for it. On
 * {@code StructuredTaskScope} only {@code open} is checked: its {@code fork} and {@code close} are
 * abstract on JDK 27, and the advice applied to them there weaves nothing. Several misses in one
 * class are joined on one line, in the order above.
 *
 * <p>It does not see every JDK change: one that every matcher still matches but that changes what
 * the advice sees ({@code fork(Runnable)} no longer calling {@code fork(Callable)}, the
 * constructor's {@code String} no longer being the name) gives no line, and a renamed {@code
 * StructuredTaskScopeImpl} is never transformed at all ({@link FirstOpenCheck} reports that one).
 *
 * <p>It shares {@link CannotInstrument} with {@link TransformFailureListener}, so a class gets at
 * most one {@code cannot instrument} line per JVM, from either. It only prints: it never retries,
 * retransforms or disables anything, keeps no {@code Class} or {@code ClassLoader} reference, and
 * never throws.
 */
final class MatcherMissListener extends AgentBuilder.Listener.Adapter {

  /** {@code ScopeOpenAdvice}: every public static {@code open} on {@code StructuredTaskScope}. */
  static final ElementMatcher.Junction<MethodDescription> OPEN =
      ElementMatchers.<MethodDescription>named("open")
          .and(ElementMatchers.isStatic())
          .and(ElementMatchers.isPublic());

  /** {@code ScopeConstructorAdvice}: the impl's 3-argument constructor ending in a name. */
  static final ElementMatcher.Junction<MethodDescription> CONSTRUCTOR =
      ElementMatchers.<MethodDescription>isConstructor()
          .and(ElementMatchers.takesArguments(3))
          .and(ElementMatchers.takesArgument(2, ElementMatchers.named("java.lang.String")));

  /**
   * {@code ForkAdvice}: {@code fork(Callable)} only; {@code fork(Runnable)} delegates to it inside
   * the JDK, so it is covered without an advice of its own.
   */
  static final ElementMatcher.Junction<MethodDescription> FORK =
      ElementMatchers.<MethodDescription>named("fork")
          .and(ElementMatchers.not(ElementMatchers.isStatic()))
          .and(ElementMatchers.isPublic())
          .and(
              ElementMatchers.takesArgument(
                  0, ElementMatchers.named("java.util.concurrent.Callable")));

  /** {@code ScopeCloseAdvice}: the public instance {@code close}. */
  static final ElementMatcher.Junction<MethodDescription> CLOSE =
      ElementMatchers.<MethodDescription>named("close")
          .and(ElementMatchers.not(ElementMatchers.isStatic()))
          .and(ElementMatchers.isPublic());

  static final String NO_MATCH = "no method matched ";
  static final String OPEN_LABEL = "open (public static)";
  static final String CONSTRUCTOR_LABEL = "the constructor (Joiner, ThreadFactory, String)";
  static final String FORK_LABEL = "fork(Callable)";
  static final String CLOSE_LABEL = "close()";

  /** A method the advice weaves into: one ByteBuddy's {@code Advice} does not skip. */
  private static final ElementMatcher.Junction<MethodDescription> HAS_CODE =
      ElementMatchers.not(ElementMatchers.isAbstract());

  /** The one the agent installs, sharing its lines with {@link TransformFailureListener}. */
  static final MatcherMissListener INSTANCE = new MatcherMissListener(CannotInstrument.SHARED);

  private final CannotInstrument lines;

  MatcherMissListener(CannotInstrument lines) {
    this.lines = lines;
  }

  @Override
  public void onTransformation(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      DynamicType dynamicType) {
    try {
      check(typeDescription);
    } catch (Throwable ignored) {
      // a listener that throws would end up in ByteBuddy's own error handling; lose the line
    }
  }

  /** Prints the line for {@code type} when an advice matched nothing in it. */
  void check(TypeDescription type) {
    if (type == null) return;
    String name = type.getName();
    if (!TransformFailureListener.SCOPE.equals(name)
        && !TransformFailureListener.SCOPE_IMPL.equals(name)) {
      return;
    }
    // ByteBuddy reports the class again on every retransformation; once is enough
    if (lines.isReported(name)) return;
    List<String> misses = misses(type);
    if (!misses.isEmpty()) lines.warn(name, String.join(", ", misses));
  }

  /**
   * Every {@code no method matched <advice>} reason for {@code type}, in the order {@code open},
   * the constructor, {@code fork}, {@code close}; empty when each advice matched a method with
   * code, and for a class other than the two scope classes.
   */
  static List<String> misses(TypeDescription type) {
    var misses = new ArrayList<String>();
    String name = type.getName();
    if (TransformFailureListener.SCOPE.equals(name)) {
      if (noMatch(type, OPEN)) misses.add(NO_MATCH + OPEN_LABEL);
    } else if (TransformFailureListener.SCOPE_IMPL.equals(name)) {
      if (noMatch(type, CONSTRUCTOR)) misses.add(NO_MATCH + CONSTRUCTOR_LABEL);
      if (noMatch(type, FORK)) misses.add(NO_MATCH + FORK_LABEL);
      if (noMatch(type, CLOSE)) misses.add(NO_MATCH + CLOSE_LABEL);
    }
    return misses;
  }

  private static boolean noMatch(TypeDescription type, ElementMatcher<MethodDescription> advice) {
    return type.getDeclaredMethods().filter(HAS_CODE.and(advice)).isEmpty();
  }
}
