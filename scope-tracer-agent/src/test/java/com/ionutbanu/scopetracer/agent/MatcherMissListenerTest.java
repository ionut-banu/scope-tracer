package com.ionutbanu.scopetracer.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.StubMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The decision logic of issue #130, without a patched JDK: {@link MatcherMissListener} against
 * {@link TypeDescription}s of synthetic types built with ByteBuddy (criterion 11), {@link
 * FirstOpenCheck} against plain objects, and the one line per class they share with {@link
 * TransformFailureListener}. That the listener is installed and sees the real JDK classes is {@code
 * AgentMatcherMissIT}'s job.
 */
class MatcherMissListenerTest {

  private static final String SCOPE = TransformFailureListener.SCOPE;
  private static final String SCOPE_IMPL = TransformFailureListener.SCOPE_IMPL;
  private static final String LINE = "[scope-tracer] WARN cannot instrument ";
  private static final String INCOMPLETE = ", the trace will be incomplete: ";
  private static final String NO_MATCH = "no method matched ";

  /** A member a synthetic scope class may declare. */
  enum Member {
    /** {@code public static open()}. */
    OPEN,
    /** {@code private <init>(Joiner, ThreadFactory, String)}. */
    CONSTRUCTOR,
    /** {@code public fork(Callable)}, with code. */
    FORK,
    /** {@code public close()}, with code. */
    CLOSE,
    /** {@code public abstract fork(Callable)}. */
    ABSTRACT_FORK,
    /** {@code public abstract close()}. */
    ABSTRACT_CLOSE,
    /** {@code private <init>(Joiner, ThreadFactory, String, int)}: the patched JDK's. */
    CONSTRUCTOR_4,
    /** {@code public fork(Runnable)}. */
    FORK_RUNNABLE
  }

  /** The four advices, each with the member it needs and its reason. */
  enum AdviceCase {
    OPEN(SCOPE, Member.OPEN, "open (public static)"),
    CONSTRUCTOR(SCOPE_IMPL, Member.CONSTRUCTOR, "the constructor (Joiner, ThreadFactory, String)"),
    FORK(SCOPE_IMPL, Member.FORK, "fork(Callable)"),
    CLOSE(SCOPE_IMPL, Member.CLOSE, "close()");

    final String type;
    final Member member;
    final String label;

    AdviceCase(String type, Member member, String label) {
      this.type = type;
      this.member = member;
      this.label = label;
    }
  }

  /** What each class needs for every advice to match. */
  private static final Set<Member> FULL_SCOPE = EnumSet.of(Member.OPEN);

  private static final Set<Member> FULL_IMPL =
      EnumSet.of(Member.CONSTRUCTOR, Member.FORK, Member.CLOSE, Member.FORK_RUNNABLE);

  /** A type named {@code name} that declares exactly {@code members} (and no default ctor). */
  static TypeDescription type(String name, Set<Member> members) {
    boolean isAbstract =
        members.contains(Member.ABSTRACT_FORK) || members.contains(Member.ABSTRACT_CLOSE);
    DynamicType.Builder<Object> b =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(Object.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
            .name(name)
            .modifiers(Modifier.PUBLIC | (isAbstract ? Modifier.ABSTRACT : 0));
    for (Member m : members) {
      b =
          switch (m) {
            case OPEN ->
                b.defineMethod("open", Object.class, Visibility.PUBLIC, Ownership.STATIC)
                    .intercept(FixedValue.nullValue());
            case CONSTRUCTOR ->
                b.defineConstructor(Visibility.PRIVATE)
                    .withParameters(
                        StructuredTaskScope.Joiner.class, ThreadFactory.class, String.class)
                    .intercept(objectConstructor());
            case CONSTRUCTOR_4 ->
                b.defineConstructor(Visibility.PRIVATE)
                    .withParameters(
                        StructuredTaskScope.Joiner.class,
                        ThreadFactory.class,
                        String.class,
                        int.class)
                    .intercept(objectConstructor());
            case FORK ->
                b.defineMethod("fork", Object.class, Visibility.PUBLIC)
                    .withParameters(Callable.class)
                    .intercept(FixedValue.nullValue());
            case FORK_RUNNABLE ->
                b.defineMethod("fork", Object.class, Visibility.PUBLIC)
                    .withParameters(Runnable.class)
                    .intercept(FixedValue.nullValue());
            case CLOSE ->
                b.defineMethod("close", void.class, Visibility.PUBLIC)
                    .intercept(StubMethod.INSTANCE);
            case ABSTRACT_FORK ->
                b.defineMethod("fork", Object.class, Visibility.PUBLIC)
                    .withParameters(Callable.class)
                    .withoutCode();
            case ABSTRACT_CLOSE ->
                b.defineMethod("close", void.class, Visibility.PUBLIC).withoutCode();
          };
    }
    return b.toTypeDescription();
  }

  private static MethodCall objectConstructor() {
    try {
      return MethodCall.invoke(Object.class.getConstructor());
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private static Set<Member> fullFor(String type) {
    return EnumSet.copyOf(type.equals(SCOPE) ? FULL_SCOPE : FULL_IMPL);
  }

  /** Runs {@code body} with stderr captured; the lines printed. Fails if {@code body} throws. */
  private static List<String> stderrOf(Runnable body) {
    var bytes = new ByteArrayOutputStream();
    PrintStream original = System.err;
    System.setErr(new PrintStream((OutputStream) bytes, true, StandardCharsets.UTF_8));
    try {
      assertThatCode(body::run).doesNotThrowAnyException();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private static void transformed(MatcherMissListener listener, TypeDescription type) {
    listener.onTransformation(type, null, null, false, null);
  }

  private static List<String> linesFor(TypeDescription type) {
    return stderrOf(() -> transformed(new MatcherMissListener(new CannotInstrument()), type));
  }

  // --- every advice matching ---

  @Test
  void eachClassWithEveryAdvicedMethodPrintsNothing() {
    assertThat(linesFor(type(SCOPE, FULL_SCOPE))).isEmpty();
    assertThat(linesFor(type(SCOPE_IMPL, FULL_IMPL))).isEmpty();
  }

  // --- each of the four advices matching nothing ---

  @ParameterizedTest
  @EnumSource(AdviceCase.class)
  void anAdviceThatMatchedNothingGivesItsReason(AdviceCase advice) {
    Set<Member> members = fullFor(advice.type);
    members.remove(advice.member);

    assertThat(linesFor(type(advice.type, members)))
        .containsExactly(LINE + advice.type + INCOMPLETE + NO_MATCH + advice.label);
  }

  @Test
  void aConstructorWithAFourthParameterIsAMiss() {
    Set<Member> members = fullFor(SCOPE_IMPL);
    members.remove(Member.CONSTRUCTOR);
    members.add(Member.CONSTRUCTOR_4);

    assertThat(linesFor(type(SCOPE_IMPL, members)))
        .containsExactly(
            LINE
                + SCOPE_IMPL
                + INCOMPLETE
                + NO_MATCH
                + "the constructor (Joiner, ThreadFactory, String)");
  }

  @Test
  void forkRunnableAloneDoesNotCountAsForkCallable() {
    Set<Member> members = fullFor(SCOPE_IMPL);
    members.remove(Member.FORK);

    assertThat(linesFor(type(SCOPE_IMPL, members)))
        .containsExactly(LINE + SCOPE_IMPL + INCOMPLETE + NO_MATCH + "fork(Callable)");
  }

  // --- several misses in one class ---

  @Test
  void twoMissesInOneClassGiveOneLineWithBothReasons() {
    var members = EnumSet.of(Member.CONSTRUCTOR, Member.FORK_RUNNABLE);

    assertThat(linesFor(type(SCOPE_IMPL, members)))
        .containsExactly(
            LINE + SCOPE_IMPL + INCOMPLETE + NO_MATCH + "fork(Callable), " + NO_MATCH + "close()");
  }

  @Test
  void allThreeImplMissesAreJoinedInOrder() {
    assertThat(linesFor(type(SCOPE_IMPL, EnumSet.noneOf(Member.class))))
        .containsExactly(
            LINE
                + SCOPE_IMPL
                + INCOMPLETE
                + NO_MATCH
                + "the constructor (Joiner, ThreadFactory, String), "
                + NO_MATCH
                + "fork(Callable), "
                + NO_MATCH
                + "close()");
  }

  // --- abstract methods: the JDK 27 interface ---

  @Test
  void abstractForkAndCloseAreNoMatchOnTheImpl() {
    var members = EnumSet.of(Member.CONSTRUCTOR, Member.ABSTRACT_FORK, Member.ABSTRACT_CLOSE);

    assertThat(linesFor(type(SCOPE_IMPL, members)))
        .containsExactly(
            LINE + SCOPE_IMPL + INCOMPLETE + NO_MATCH + "fork(Callable), " + NO_MATCH + "close()");
  }

  @Test
  void abstractForkAndCloseOnStructuredTaskScopeAreNotReported() {
    var members = EnumSet.of(Member.OPEN, Member.ABSTRACT_FORK, Member.ABSTRACT_CLOSE);

    assertThat(linesFor(type(SCOPE, members))).isEmpty();
  }

  @Test
  void theRealJdk27ClassesPrintNothing() throws Exception {
    var scope = TypeDescription.ForLoadedType.of(StructuredTaskScope.class);
    var impl = TypeDescription.ForLoadedType.of(Class.forName(SCOPE_IMPL));

    assertThat(MatcherMissListener.misses(scope)).isEmpty();
    assertThat(MatcherMissListener.misses(impl)).isEmpty();
  }

  // --- other classes ---

  @Test
  void aThirdClassAndNullPrintNothing() {
    var listener = new MatcherMissListener(new CannotInstrument());
    List<String> lines =
        stderrOf(
            () -> {
              transformed(
                  listener,
                  type("java.util.concurrent.ConcurrentHashMap", EnumSet.noneOf(Member.class)));
              transformed(listener, null);
            });

    assertThat(lines).isEmpty();
  }

  // --- never throws ---

  @Test
  void aTypeDescriptionThatThrowsEverywhereIsSwallowed() {
    var hostile =
        (TypeDescription)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {TypeDescription.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getName")) return SCOPE_IMPL;
                  throw new IllegalStateException("hostile " + method.getName());
                });

    assertThat(linesFor(hostile)).isEmpty();
  }

  // --- at most once per class ---

  @Test
  void aClassReportedAgainOnRetransformationIsNamedOnce() {
    var listener = new MatcherMissListener(new CannotInstrument());
    TypeDescription impl = type(SCOPE_IMPL, EnumSet.of(Member.FORK, Member.CLOSE));
    TypeDescription scope = type(SCOPE, EnumSet.noneOf(Member.class));
    List<String> lines =
        stderrOf(
            () -> {
              for (int i = 0; i < 3; i++) {
                transformed(listener, impl);
                transformed(listener, scope);
              }
            });

    assertThat(lines)
        .containsExactly(
            LINE
                + SCOPE_IMPL
                + INCOMPLETE
                + NO_MATCH
                + "the constructor (Joiner, ThreadFactory, String)",
            LINE + SCOPE + INCOMPLETE + NO_MATCH + "open (public static)");
  }

  @Test
  void eightThreadsReportingTheSameClassPrintOneLine() {
    var listener = new MatcherMissListener(new CannotInstrument());
    TypeDescription impl = type(SCOPE_IMPL, EnumSet.of(Member.FORK, Member.CLOSE));

    List<String> lines = stderrOf(() -> onEightThreads(() -> transformed(listener, impl)));

    assertThat(lines)
        .containsExactly(
            LINE
                + SCOPE_IMPL
                + INCOMPLETE
                + NO_MATCH
                + "the constructor (Joiner, ThreadFactory, String)");
  }

  @Test
  void aByteBuddyErrorFirstLeavesTheMatcherMissSilent() {
    var lines = new CannotInstrument();
    var failures = new TransformFailureListener(lines);
    var misses = new MatcherMissListener(lines);
    TypeDescription impl = type(SCOPE_IMPL, EnumSet.of(Member.FORK, Member.CLOSE));

    List<String> printed =
        stderrOf(
            () -> {
              failures.onError(SCOPE_IMPL, null, null, false, new IllegalStateException("x"));
              transformed(misses, impl);
            });

    assertThat(printed)
        .containsExactly(LINE + SCOPE_IMPL + INCOMPLETE + "IllegalStateException: x");
  }

  @Test
  void aMatcherMissFirstLeavesTheByteBuddyErrorSilent() {
    var lines = new CannotInstrument();
    var failures = new TransformFailureListener(lines);
    var misses = new MatcherMissListener(lines);
    TypeDescription impl = type(SCOPE_IMPL, EnumSet.of(Member.FORK, Member.CLOSE));

    List<String> printed =
        stderrOf(
            () -> {
              transformed(misses, impl);
              failures.onError(SCOPE_IMPL, null, null, true, new IllegalStateException("x"));
            });

    assertThat(printed)
        .containsExactly(
            LINE
                + SCOPE_IMPL
                + INCOMPLETE
                + NO_MATCH
                + "the constructor (Joiner, ThreadFactory, String)");
  }

  @Test
  void theInstalledListenersShareOneState() throws Exception {
    var failureState = TransformFailureListener.class.getDeclaredField("lines");
    var missState = MatcherMissListener.class.getDeclaredField("lines");
    var openState = FirstOpenCheck.class.getDeclaredField("lines");
    var openInstance = FirstOpenCheck.class.getDeclaredField("INSTANCE");
    for (var f : List.of(failureState, missState, openState, openInstance)) f.setAccessible(true);

    assertThat(failureState.get(TransformFailureListener.INSTANCE))
        .isSameAs(CannotInstrument.SHARED);
    assertThat(missState.get(MatcherMissListener.INSTANCE)).isSameAs(CannotInstrument.SHARED);
    assertThat(openState.get(openInstance.get(null))).isSameAs(CannotInstrument.SHARED);
  }

  // --- the first-open check ---

  private static final String RENAMED_REASON =
      "StructuredTaskScope.open returned it, and the agent instruments only " + SCOPE_IMPL;

  @Test
  void aRealScopeOfTheInstrumentedClassPrintsNothing() throws Exception {
    var check = new FirstOpenCheck(new CannotInstrument());
    List<String> lines;
    try (var scope = StructuredTaskScope.open()) {
      assertThat(scope.getClass().getName()).isEqualTo(SCOPE_IMPL);
      lines = stderrOf(() -> check.check(scope));
      scope.join();
    }

    assertThat(lines).isEmpty();
  }

  @Test
  void anotherClassOnTheFirstOpenIsNamedOnce() {
    var check = new FirstOpenCheck(new CannotInstrument());
    List<String> lines =
        stderrOf(
            () -> {
              check.check(null); // not an open: does not use up the check
              check.check(new ConcurrentLinkedRenamedScope());
              check.check(new ConcurrentLinkedRenamedScope());
              check.check(new Object());
            });

    assertThat(lines)
        .containsExactly(
            LINE + ConcurrentLinkedRenamedScope.class.getName() + INCOMPLETE + RENAMED_REASON);
  }

  @Test
  void onlyTheFirstOpenIsChecked() throws Exception {
    var check = new FirstOpenCheck(new CannotInstrument());
    List<String> lines;
    try (var scope = StructuredTaskScope.open()) {
      lines =
          stderrOf(
              () -> {
                check.check(scope);
                check.check(new ConcurrentLinkedRenamedScope());
              });
      scope.join();
    }

    assertThat(lines).isEmpty();
  }

  @Test
  void eightThreadsOnTheFirstOpenPrintOneLine() {
    var check = new FirstOpenCheck(new CannotInstrument());
    List<String> lines =
        stderrOf(() -> onEightThreads(() -> check.check(new ConcurrentLinkedRenamedScope())));

    assertThat(lines)
        .containsExactly(
            LINE + ConcurrentLinkedRenamedScope.class.getName() + INCOMPLETE + RENAMED_REASON);
  }

  @Test
  void aClassNameWithALineBreakIsEscapedOnOneLine() throws Exception {
    Class<?> odd =
        new ByteBuddy()
            .with(TypeValidation.DISABLED)
            .subclass(Object.class)
            .name("odd.Scope\nImpl")
            .make()
            .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
            .getLoaded();
    Object scope = odd.getConstructor().newInstance();
    assertThat(scope.getClass().getName()).contains("\n");
    var check = new FirstOpenCheck(new CannotInstrument());

    List<String> lines = stderrOf(() -> check.check(scope));

    assertThat(lines).containsExactly(LINE + "odd.Scope\\nImpl" + INCOMPLETE + RENAMED_REASON);
  }

  @Test
  void aLongClassNameIsCutAsAnArgumentValueIs() {
    String name = "x".repeat(150);
    assertThat(AgentConfig.shown(name)).hasSize(103);
    var lines = new CannotInstrument();
    // the check passes the escaped, cut name to the shared lines
    List<String> printed =
        stderrOf(() -> lines.warn(AgentConfig.shown(name), FirstOpenCheck.REASON));

    assertThat(printed)
        .containsExactly(LINE + "x".repeat(100) + "..." + INCOMPLETE + RENAMED_REASON);
  }

  @Test
  void theEntryPointNeverThrows() {
    assertThatCode(() -> FirstOpenCheck.onOpen(null)).doesNotThrowAnyException();
  }

  /** A class that is not {@code StructuredTaskScopeImpl}, standing in for a renamed one. */
  static final class ConcurrentLinkedRenamedScope {}

  /** Runs {@code body} on 8 platform threads released at once, and waits for them all. */
  private static void onEightThreads(Runnable body) {
    var start = new CountDownLatch(1);
    var threads = new ArrayList<Thread>();
    for (int i = 0; i < 8; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    body.run();
                  }));
    }
    start.countDown();
    for (Thread t : threads) {
      try {
        t.join(10_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
