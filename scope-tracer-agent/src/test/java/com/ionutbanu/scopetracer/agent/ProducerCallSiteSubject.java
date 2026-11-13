package com.ionutbanu.scopetracer.agent;

import com.ionutbanu.scopetracer.core.TracedScope;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import jdk.jfr.Recording;

/**
 * The same program run by two producers, for {@link ProducerCallSiteIT}: with {@code args[1] ==
 * "agent"} it uses plain {@link StructuredTaskScope}s and is run with {@code -javaagent}; with
 * {@code "traced"} it uses {@link TracedScope} and is run without the agent (never both in one
 * JVM). The recording is written to {@code args[0]}.
 *
 * <p>Every fork is one statement on one source line that calls either {@code TracedScope.fork} or
 * {@code StructuredTaskScope.fork}, so the user frame each producer finds - class, method and line
 * - is the same, and the IT can require the call-site fields to be equal rather than similar.
 *
 * <p>Prints {@code explicitLine=<n>} for the {@code TracedScope}-only fork with an explicit name,
 * which the agent cannot make; the IT checks that fork against that line.
 */
public final class ProducerCallSiteSubject {

  static final String EXPLICIT = "explicit";

  private static boolean traced;
  private static int explicitLine;

  private ProducerCallSiteSubject() {}

  public static void main(String[] args) throws Exception {
    traced = "traced".equals(args[1]);
    try (var recording = new Recording()) {
      recording.enable("com.ionutbanu.scopetracer.*");
      recording.start();
      var subject = new ProducerCallSiteSubject();
      subject.forkEveryKind();
      subject.failFast();
      subject.anySuccessful();
      subject.nested();
      subject.forkRunnables();
      recording.stop();
      recording.dump(Path.of(args[0]));
    }
    System.out.println("explicitLine=" + explicitLine);
  }

  /** Lambda, method reference, named, local and anonymous {@code Callable}s, nested callers. */
  private void forkEveryKind() throws Exception {
    final class Local implements Callable<Integer> {
      @Override
      public Integer call() {
        return 2;
      }
    }
    try (var s = Scope.open("producer-kinds")) {
      Callable<Object> viaAnonymous =
          new Callable<>() {
            @Override
            public Object call() {
              return traced ? s.ts.fork(() -> 5) : s.ps.fork(() -> 5);
            }
          };
      viaAnonymous.call();
      var lambda = traced ? s.ts.fork(() -> 1) : s.ps.fork(() -> 1);
      var ref = traced ? s.ts.fork(this::work) : s.ps.fork(this::work);
      var named = traced ? s.ts.fork(new Named()) : s.ps.fork(new Named());
      var local = traced ? s.ts.fork(new Local()) : s.ps.fork(new Local());
      var anonymous = traced ? s.ts.fork(anonymousCallable()) : s.ps.fork(anonymousCallable());
      Inner.fork(s);
      if (traced) {
        var explicit = s.ts.fork(at(EXPLICIT), () -> 6);
      }
      s.join();
    }
  }

  /** A failed task and the sibling it cancels, under the default fail-fast joiner. */
  private void failFast() throws Exception {
    var st = new CountDownLatch(1);
    try (var s = Scope.open("producer-fail-fast")) {
      var failing = traced ? s.ts.fork(() -> fail(st)) : s.ps.fork(() -> fail(st));
      var blocked = traced ? s.ts.fork(() -> block(st)) : s.ps.fork(() -> block(st));
      try {
        s.join();
        throw new IllegalStateException("join should have thrown");
      } catch (ExecutionException expected) {
        // the failing task's exception; the sibling has been cancelled
      }
    }
  }

  /** {@code anySuccessfulOrThrow}: one winner, two losers cancelled while running. */
  private void anySuccessful() throws Exception {
    var ls = new CountDownLatch(2);
    try (var s = Scope.openAny("producer-any")) {
      var winner = traced ? s.ts.fork(() -> win(ls)) : s.ps.fork(() -> win(ls));
      var loserA = traced ? s.ts.fork(() -> block(ls)) : s.ps.fork(() -> block(ls));
      var loserB = traced ? s.ts.fork(() -> block(ls)) : s.ps.fork(() -> block(ls));
      s.join();
    }
  }

  /** A scope opened in a task of a scope opened in a task: forks made on virtual threads. */
  private void nested() throws Exception {
    try (var s = Scope.open("producer-outer")) {
      var outer = traced ? s.ts.fork(this::middle) : s.ps.fork(this::middle);
      s.join();
    }
  }

  /**
   * {@code fork(Runnable)}: a lambda and a named {@code Runnable} class. The agent sees only the
   * JDK's internal wrapper, so this checks that {@code TracedScope.fork(Runnable)} stamps what the
   * agent stamps.
   */
  private void forkRunnables() throws Exception {
    Runnable lambda = () -> {};
    try (var s = Scope.open("producer-runnables")) {
      var viaLambda = traced ? s.ts.fork(lambda) : s.ps.fork(lambda);
      var viaNamed = traced ? s.ts.fork(new NamedRunnable()) : s.ps.fork(new NamedRunnable());
      s.join();
    }
  }

  private Integer middle() throws Exception {
    try (var s = Scope.open("producer-middle")) {
      var middle = traced ? s.ts.fork(this::inner) : s.ps.fork(this::inner);
      s.join();
    }
    return 7;
  }

  private Integer inner() throws Exception {
    try (var s = Scope.open("producer-inner")) {
      var inner = traced ? s.ts.fork(() -> 8) : s.ps.fork(() -> 8);
      s.join();
    }
    return 8;
  }

  private Integer work() {
    return 3;
  }

  private static Callable<Integer> anonymousCallable() {
    return new Callable<>() {
      @Override
      public Integer call() {
        return 4;
      }
    };
  }

  private static Integer fail(CountDownLatch siblingStarted) throws InterruptedException {
    siblingStarted.await();
    throw new IllegalStateException("boom");
  }

  private static Integer win(CountDownLatch losersStarted) throws InterruptedException {
    losersStarted.await();
    return 9;
  }

  /** Counts down {@code started}, then waits until the scope interrupts it. */
  private static Integer block(CountDownLatch started) throws InterruptedException {
    started.countDown();
    new CountDownLatch(1).await();
    return 0;
  }

  /** Records the line it is called from, and returns {@code name}. */
  private static String at(String name) {
    explicitLine =
        StackWalker.getInstance().walk(f -> f.skip(1).findFirst()).orElseThrow().getLineNumber();
    return name;
  }

  /** A named {@code Callable} that is a nested class. */
  static final class Named implements Callable<Integer> {
    @Override
    public Integer call() {
      return 10;
    }
  }

  /** A named {@code Runnable} that is a nested class. */
  static final class NamedRunnable implements Runnable {
    @Override
    public void run() {}
  }

  /** Forks from a method of a nested class. */
  static final class Inner {
    static Object fork(Scope s) {
      return traced ? s.ts.fork(() -> 11) : s.ps.fork(() -> 11);
    }
  }

  /** One scope, opened by {@code TracedScope} or by a plain {@code StructuredTaskScope}. */
  static final class Scope implements AutoCloseable {
    final TracedScope<Object, ?, ? extends Exception> ts;
    final StructuredTaskScope<Object, ?, ? extends Exception> ps;

    private Scope(
        TracedScope<Object, ?, ? extends Exception> ts,
        StructuredTaskScope<Object, ?, ? extends Exception> ps) {
      this.ts = ts;
      this.ps = ps;
    }

    static Scope open(String name) {
      return traced
          ? new Scope(TracedScope.open(name), null)
          : new Scope(null, StructuredTaskScope.open());
    }

    static Scope openAny(String name) {
      return traced
          ? new Scope(TracedScope.open(name, Joiner.anySuccessfulOrThrow()), null)
          : new Scope(null, StructuredTaskScope.open(Joiner.anySuccessfulOrThrow()));
    }

    Object join() throws Exception {
      return ts != null ? ts.join() : ps.join();
    }

    @Override
    public void close() {
      if (ts != null) ts.close();
      else ps.close();
    }
  }
}
