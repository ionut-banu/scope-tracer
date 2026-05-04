package dev.scopetracer.core;

import dev.scopetracer.core.events.ScopeClosedEvent;
import dev.scopetracer.core.events.ScopeOpenedEvent;
import dev.scopetracer.core.events.TaskCancelledEvent;
import dev.scopetracer.core.events.TaskFailedEvent;
import dev.scopetracer.core.events.TaskForkedEvent;
import dev.scopetracer.core.events.TaskSucceededEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link StructuredTaskScope} wrapper that records each lifecycle moment of the scope and its
 * forked tasks as JFR events. The joiner controls how subtask results are combined and what {@link
 * #join()} returns; the default is {@link Joiner#awaitAllSuccessfulOrThrow()}.
 *
 * <p>Six event types are emitted (see {@code dev.scopetracer.core.events}): scope opened, task
 * forked, task succeeded, task failed, task cancelled, scope closed. Every event carries:
 *
 * <ul>
 *   <li>{@code scopeId} — a globally unique monotonic id per scope instance; the primary key used
 *       by the parser to correlate events even when multiple scopes share the same name,
 *   <li>{@code scopeName} — the name supplied at construction,
 *   <li>{@code taskId} — a per-scope monotonic id starting at 1 ({@code 0} on scope-level events),
 *   <li>{@code threadName} — the carrier/virtual thread that produced the event.
 * </ul>
 *
 * Timestamps are recorded by JFR itself via {@code Event.startTime}.
 *
 * <h2>Default joiner — fail-fast on first failure</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("checkout-flow")) {
 *     Subtask<Quote>       pricing   = scope.fork(() -> pricingService.quote(cart));
 *     Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
 *     scope.join(); // throws FailedException if either subtask failed
 *     return new Checkout(pricing.get(), inventory.get());
 * }
 * }</pre>
 *
 * <h2>Custom joiner — race to first success</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("service-race", Joiner.anySuccessfulOrThrow())) {
 *     scope.fork(() -> primaryService.call(request));
 *     scope.fork(() -> fallbackService.call(request));
 *     Response first = scope.join(); // returns first successful result; losers are cancelled
 * }
 * }</pre>
 *
 * <h2>Custom joiner — collect all results</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("fan-out", Joiner.allSuccessfulOrThrow())) {
 *     scope.fork(() -> serviceA.call());
 *     scope.fork(() -> serviceB.call());
 *     List<Response> results = scope.join(); // all results in completion order
 * }
 * }</pre>
 *
 * <p>Instances are not thread-safe beyond the guarantees of {@link StructuredTaskScope}: {@link
 * #fork} may be called from the owner thread or any active subtask of this scope; {@link #join} and
 * {@link #close} are owner-only.
 *
 * @param <R> the result type produced by the joiner on {@link #join()}.
 */
public final class TracedScope<R> implements AutoCloseable {

  /** Global counter; each new {@code TracedScope} instance gets a unique monotonic ID. */
  private static final AtomicLong SCOPE_ID_COUNTER = new AtomicLong();

  private final String name;
  private final long scopeId;
  private final StructuredTaskScope<Object, R> scope;
  private final AtomicLong taskIdCounter = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  // ---- Static factories (preferred API) ----------------------------------------

  /**
   * Opens a traced scope with the given name and virtual threads, using {@link
   * Joiner#awaitAllSuccessfulOrThrow()}. {@link #join()} throws {@link
   * StructuredTaskScope.FailedException} if any subtask fails.
   *
   * @param name human-readable scope name; must be non-null and non-blank.
   * @return a new {@code TracedScope<Void>}.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public static TracedScope<Void> open(String name) {
    return new TracedScope<>(
        name, Thread.ofVirtual().factory(), Joiner.awaitAllSuccessfulOrThrow());
  }

  /**
   * Opens a traced scope with the given name and a caller-supplied {@link ThreadFactory}, using
   * {@link Joiner#awaitAllSuccessfulOrThrow()}. Useful for tests that need platform threads.
   *
   * @param name scope name; must be non-null and non-blank.
   * @param factory thread factory used by the underlying scope; must be non-null.
   * @return a new {@code TracedScope<Void>}.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public static TracedScope<Void> open(String name, ThreadFactory factory) {
    return new TracedScope<>(name, factory, Joiner.awaitAllSuccessfulOrThrow());
  }

  /**
   * Opens a traced scope with a custom joiner and virtual threads. The joiner controls how {@link
   * #join()} waits for subtasks and what value it returns.
   *
   * <p>Example — race to first success:
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("race", Joiner.anySuccessfulOrThrow())) {
   *     scope.fork(() -> primary.call());
   *     scope.fork(() -> fallback.call());
   *     Response first = scope.join();
   * }
   * }</pre>
   *
   * @param <R> the result type produced by the joiner.
   * @param name scope name; must be non-null and non-blank.
   * @param joiner the joiner to use; must be non-null.
   * @return a new {@code TracedScope<R>}.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public static <R> TracedScope<R> open(String name, Joiner<? super Object, ? extends R> joiner) {
    return new TracedScope<>(name, Thread.ofVirtual().factory(), joiner);
  }

  /**
   * Opens a traced scope with a custom joiner and a caller-supplied {@link ThreadFactory}.
   *
   * @param <R> the result type produced by the joiner.
   * @param name scope name; must be non-null and non-blank.
   * @param joiner the joiner to use; must be non-null.
   * @param factory thread factory used by the underlying scope; must be non-null.
   * @return a new {@code TracedScope<R>}.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public static <R> TracedScope<R> open(
      String name, Joiner<? super Object, ? extends R> joiner, ThreadFactory factory) {
    return new TracedScope<>(name, factory, joiner);
  }

  // ---- Legacy constructors (kept for source-level backwards compatibility) ------

  /**
   * Opens a traced scope with the given name and virtual threads. Prefer {@link #open(String)} for
   * new code — it returns the properly typed {@code TracedScope<Void>} without raw-type warnings.
   *
   * @param name human-readable scope name; must be non-null and non-blank.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  @SuppressWarnings("unchecked")
  public TracedScope(String name) {
    this(
        name,
        Thread.ofVirtual().factory(),
        (Joiner<? super Object, ? extends R>) Joiner.awaitAllSuccessfulOrThrow());
  }

  /**
   * Opens a traced scope with the given name and a caller-supplied {@link ThreadFactory}. Prefer
   * {@link #open(String, ThreadFactory)} for new code.
   *
   * @param name scope name; must be non-null and non-blank.
   * @param factory thread factory used by the underlying scope; must be non-null.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  @SuppressWarnings("unchecked")
  public TracedScope(String name, ThreadFactory factory) {
    this(name, factory, (Joiner<? super Object, ? extends R>) Joiner.awaitAllSuccessfulOrThrow());
  }

  // ---- Canonical constructor ----------------------------------------------------

  private TracedScope(
      String name, ThreadFactory factory, Joiner<? super Object, ? extends R> joiner) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-null and non-blank");
    }
    this.name = name;
    this.scopeId = SCOPE_ID_COUNTER.incrementAndGet();
    this.scope =
        StructuredTaskScope.open(
            joiner, config -> config.withName(name).withThreadFactory(factory));
    var event = new ScopeOpenedEvent();
    event.scopeId = this.scopeId;
    event.scopeName = name;
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }

  // ---- Public API ---------------------------------------------------------------

  /**
   * @return the scope name supplied at construction. Never null or blank.
   */
  public String name() {
    return name;
  }

  /**
   * Forks a value-returning task into the scope. Emits a "task forked" JFR event on the calling
   * thread, and exactly one of "task succeeded", "task failed", or "task cancelled" on the task's
   * own thread when it terminates.
   *
   * <p>Cancellation is reported when the task observes scope shutdown (e.g. a sibling failed or the
   * winning joiner shut down the scope) before completing on its own.
   *
   * @param task the callable to run as a structured subtask; must be non-null.
   * @param <T> result type of the subtask.
   * @return a {@link Subtask} handle whose value is observable after {@link #join()}.
   */
  public <T> Subtask<T> fork(Callable<? extends T> task) {
    long id = taskIdCounter.incrementAndGet();

    var forked = new TaskForkedEvent();
    forked.scopeId = this.scopeId;
    forked.scopeName = name;
    forked.taskId = id;
    forked.threadName = Thread.currentThread().getName();
    forked.commit();

    return scope.fork(
        () -> {
          try {
            T result = task.call();
            var ev = new TaskSucceededEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.commit();
            return result;
          } catch (InterruptedException e) {
            var ev = new TaskCancelledEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.commit();
            Thread.currentThread().interrupt();
            throw e;
          } catch (Exception e) {
            var ev = new TaskFailedEvent();
            ev.scopeId = this.scopeId;
            ev.scopeName = name;
            ev.taskId = id;
            ev.threadName = Thread.currentThread().getName();
            ev.exceptionType = e.getClass().getName();
            ev.exceptionMessage = e.getMessage();
            ev.commit();
            throw e;
          }
        });
  }

  /**
   * Waits for the joiner's completion condition and returns the joiner's result. The exact
   * semantics depend on the joiner supplied at construction:
   *
   * <ul>
   *   <li>{@link Joiner#awaitAllSuccessfulOrThrow()} (default) — waits for all tasks, throws {@link
   *       StructuredTaskScope.FailedException} on the first failure; returns {@code null}.
   *   <li>{@link Joiner#awaitAll()} — waits for all tasks regardless of outcome; returns {@code
   *       null}.
   *   <li>{@link Joiner#anySuccessfulOrThrow()} — returns as soon as one task succeeds, cancels the
   *       rest; returns that task's result.
   *   <li>{@link Joiner#allSuccessfulOrThrow()} — waits for all tasks, throws on any failure;
   *       returns a {@code List} of all results in completion order.
   * </ul>
   *
   * @return the joiner's result ({@code R}).
   * @throws InterruptedException if the owner thread is interrupted while waiting.
   * @throws StructuredTaskScope.FailedException if the joiner throws on subtask failure.
   */
  public R join() throws InterruptedException {
    return scope.join();
  }

  /**
   * Closes the scope, cancelling and joining any still-running subtasks. Emits a "scope closed" JFR
   * event after all subtasks have terminated.
   *
   * <p>Idempotent: subsequent calls are no-ops and emit no further events.
   */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      scope.close();
      var event = new ScopeClosedEvent();
      event.scopeId = this.scopeId;
      event.scopeName = name;
      event.taskId = 0L;
      event.threadName = Thread.currentThread().getName();
      event.commit();
    }
  }
}
