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
 * forked tasks as JFR events. On the first subtask failure the scope is cancelled and {@link
 * #join()} re-throws that failure wrapped in a {@link StructuredTaskScope.FailedException}.
 *
 * <p>Six event types are emitted (see {@code dev.scopetracer.core.events}): scope opened, task
 * forked, task succeeded, task failed, task cancelled, scope closed. Every event carries:
 *
 * <ul>
 *   <li>{@code scopeName} — the name supplied at construction,
 *   <li>{@code taskId} — a per-scope monotonic id starting at 1 ({@code 0} on scope-level events),
 *   <li>{@code threadName} — the carrier/virtual thread that produced the event.
 * </ul>
 *
 * Timestamps are recorded by JFR itself via {@code Event.startTime}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (var scope = new TracedScope("checkout-flow")) {
 *     Subtask<Quote>       pricing   = scope.fork(() -> pricingService.quote(cart));
 *     Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
 *     scope.join(); // throws FailedException if either subtask failed
 *     return new Checkout(pricing.get(), inventory.get());
 * }
 * }</pre>
 *
 * <p>Instances are not thread-safe beyond the guarantees of {@link StructuredTaskScope}: {@link
 * #fork} may be called from the owner thread or any active subtask of this scope; {@link #join} and
 * {@link #close} are owner-only.
 */
public final class TracedScope implements AutoCloseable {

  /** Global counter; each new {@code TracedScope} instance gets a unique monotonic ID. */
  private static final AtomicLong SCOPE_ID_COUNTER = new AtomicLong();

  private final String name;
  private final long scopeId;
  private final StructuredTaskScope<Object, Void> scope;
  private final AtomicLong taskIdCounter = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Opens a traced scope with the given name and virtual threads. Emits a "scope opened" JFR event.
   *
   * @param name human-readable scope name, recorded on every emitted event; must be non-null and
   *     non-blank.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public TracedScope(String name) {
    this(name, Thread.ofVirtual().factory());
  }

  /**
   * Opens a traced scope with the given name and a caller-supplied {@link ThreadFactory}. Useful
   * for tests that pin tasks to platform threads.
   *
   * @param name scope name; must be non-null and non-blank.
   * @param factory thread factory used by the underlying scope; must be non-null.
   * @throws IllegalArgumentException if {@code name} is blank.
   */
  public TracedScope(String name, ThreadFactory factory) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-null and non-blank");
    }
    this.name = name;
    this.scopeId = SCOPE_ID_COUNTER.incrementAndGet();
    this.scope =
        StructuredTaskScope.open(
            Joiner.awaitAllSuccessfulOrThrow(),
            config -> config.withName(name).withThreadFactory(factory));
    var event = new ScopeOpenedEvent();
    event.scopeId = this.scopeId;
    event.scopeName = name;
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }

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
   * <p>Cancellation is reported when the task observes scope shutdown (e.g. a sibling failed)
   * before completing on its own.
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
            ev.commit();
            throw e;
          }
        });
  }

  /**
   * Waits for all forked subtasks to complete, fail, or be cancelled. If any subtask failed, the
   * failure is re-thrown as a {@link StructuredTaskScope.FailedException} (unchecked). Returns this
   * scope for fluent use.
   *
   * @return this {@code TracedScope}.
   * @throws InterruptedException if the owner thread is interrupted while waiting.
   * @throws StructuredTaskScope.FailedException if any subtask failed.
   */
  public TracedScope join() throws InterruptedException {
    scope.join();
    return this;
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
