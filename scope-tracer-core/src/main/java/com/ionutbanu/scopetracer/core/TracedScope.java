package com.ionutbanu.scopetracer.core;

import com.ionutbanu.scopetracer.core.events.ScopeClosedEvent;
import com.ionutbanu.scopetracer.core.events.ScopeOpenedEvent;
import com.ionutbanu.scopetracer.core.events.TaskCancelledEvent;
import com.ionutbanu.scopetracer.core.events.TaskFailedEvent;
import com.ionutbanu.scopetracer.core.events.TaskForkedEvent;
import com.ionutbanu.scopetracer.core.events.TaskSucceededEvent;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Configuration;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * A {@link StructuredTaskScope} wrapper that records each lifecycle moment of the scope and its
 * forked tasks as JFR events. The joiner controls how subtask results are combined, what {@link
 * #join()} returns and which exception it throws; the default is {@link
 * Joiner#awaitAllSuccessfulOrThrow()}, whose {@code join()} throws {@link ExecutionException}.
 *
 * <p>{@code TracedScope} mirrors {@link StructuredTaskScope}'s public API: the same type parameters
 * {@code <T, R, R_X>}, the same four static {@code open} factories with a leading {@code String
 * name}, and the same instance methods with the same signatures. Replacing {@code
 * StructuredTaskScope.open(...)} with {@code TracedScope.open("name", ...)} compiles and behaves
 * the same; the only difference is the events. It adds {@link #fork(String, Callable)}, which
 * labels a task, and {@link #name()}.
 *
 * <p>Six event types are emitted (see {@code com.ionutbanu.scopetracer.core.events}): scope opened,
 * task forked, task succeeded, task failed, task cancelled, scope closed. Every event carries:
 *
 * <ul>
 *   <li>{@code scopeId} — a globally unique monotonic id per scope instance; the primary key used
 *       by the parser to correlate events even when multiple scopes share the same name,
 *   <li>{@code scopeName} — the {@code name} argument given to {@code open},
 *   <li>{@code taskId} — a per-scope monotonic id starting at 1 ({@code 0} on scope-level events),
 *   <li>{@code threadName} — the carrier/virtual thread that produced the event.
 * </ul>
 *
 * Timestamps are recorded by JFR itself via {@code Event.startTime}.
 *
 * <h2>The name and the configuration</h2>
 *
 * <p>Every factory takes a {@code name}, which must be non-null and non-blank; otherwise {@code
 * open} throws {@link IllegalArgumentException} before anything is opened or emitted. The
 * underlying scope is configured with {@code withName(name)} and a virtual-thread factory first,
 * and the caller's configuration function, if any, is applied to that result, so whatever it sets
 * (a thread factory, a timeout, even a different {@code withName}) takes effect exactly as on a
 * plain scope. Every event carries the {@code name} argument as {@code scopeName}, whatever the
 * configuration function does.
 *
 * <h2>Default joiner — fail-fast on first failure</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("checkout-flow")) {
 *   Subtask<Quote> pricing = scope.fork(() -> pricingService.quote(cart));
 *   Subtask<Reservation> inventory = scope.fork(() -> inventoryService.reserve(cart));
 *   scope.join(); // throws ExecutionException if either subtask failed
 *   return new Checkout(pricing.get(), inventory.get());
 * }
 * }</pre>
 *
 * <h2>Custom joiner — race to first success</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("service-race", Joiner.<Response>anySuccessfulOrThrow())) {
 *   scope.fork(() -> primaryService.call(request));
 *   scope.fork(() -> fallbackService.call(request));
 *   Response first = scope.join(); // first successful result; losers are cancelled
 * }
 * }</pre>
 *
 * <h2>Custom joiner — collect all results</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("fan-out", Joiner.<Response>allSuccessfulOrThrow())) {
 *   scope.fork(() -> serviceA.call());
 *   scope.fork(() -> serviceB.call());
 *   List<Response> results = scope.join(); // all results, in the order they were forked
 * }
 * }</pre>
 *
 * <h2>Custom joiner — wait for every task, whatever its outcome</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("best-effort", Joiner.<Response>allUntil(s -> false))) {
 *   scope.fork(() -> serviceA.call());
 *   scope.fork(() -> serviceB.call());
 *   List<Subtask<Response>> subtasks = scope.join(); // never throws for a failed subtask
 * }
 * }</pre>
 *
 * <h2>Configuration — a timeout</h2>
 *
 * <pre>{@code
 * try (var scope = TracedScope.open("deadline", cfg -> cfg.withTimeout(Duration.ofSeconds(5)))) {
 *   Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
 *   scope.join(); // throws if the timeout expires first
 *   return quote.get();
 * }
 * }</pre>
 *
 * <p>Instances are not thread-safe beyond the guarantees of {@link StructuredTaskScope}: {@link
 * #fork} may be called from the owner thread or any active subtask of this scope; {@link #join} and
 * {@link #close} are owner-only.
 *
 * @param <T> the result type of the tasks forked in the scope, as in {@link StructuredTaskScope}:
 *     the joiner accepts {@code Subtask<? extends T>} and {@link #fork} returns a {@code
 *     Subtask<U>} for some {@code U extends T}.
 * @param <R> the result type produced by the joiner on {@link #join()}.
 * @param <R_X> the exception type {@link #join()} throws when the joiner's outcome is an exception,
 *     as in {@link StructuredTaskScope}; {@link ExecutionException} for the built-in joiners.
 */
public final class TracedScope<T, R, R_X extends Throwable> implements AutoCloseable {

  /** Global counter; each new {@code TracedScope} instance gets a unique monotonic ID. */
  private static final AtomicLong SCOPE_ID_COUNTER = new AtomicLong();

  private final String name;
  private final long scopeId;
  private final StructuredTaskScope<T, R, R_X> scope;
  private final AtomicLong taskIdCounter = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  // ---- Static factories: StructuredTaskScope's four, each with a leading name -------------

  /**
   * Opens a traced scope with the given name, joiner and configuration function. Mirrors {@link
   * StructuredTaskScope#open(Joiner, UnaryOperator)}.
   *
   * <p>The underlying scope's configuration is {@code withName(name)} and a virtual-thread factory,
   * with {@code configFunction} applied to it, so a thread factory, a timeout or a name set by the
   * function takes effect as on a plain scope. Events carry {@code name} as {@code scopeName}.
   *
   * <pre>{@code
   * try (var scope =
   *     TracedScope.open(
   *         "race",
   *         Joiner.<Response>anySuccessfulOrThrow(),
   *         cfg -> cfg.withThreadFactory(factory))) {
   *   scope.fork(() -> primaryService.call(request));
   *   scope.fork(() -> fallbackService.call(request));
   *   Response first = scope.join();
   * }
   * }</pre>
   *
   * @param <T> the result type of the tasks forked in the scope.
   * @param <R> the result type produced by the joiner.
   * @param <R_X> the exception type {@link #join()} throws when the joiner's outcome is an
   *     exception.
   * @param name scope name, recorded on every event; must be non-null and non-blank.
   * @param joiner the joiner to use; must be non-null.
   * @param configFunction a function applied to the scope's configuration; must be non-null.
   * @return a new open {@code TracedScope}.
   * @throws IllegalArgumentException if {@code name} is null or blank.
   * @throws NullPointerException if {@code joiner} or {@code configFunction} is null.
   */
  public static <T, R, R_X extends Throwable> TracedScope<T, R, R_X> open(
      String name,
      Joiner<? super T, ? extends R, R_X> joiner,
      UnaryOperator<Configuration> configFunction) {
    requireName(name);
    // The JDK's own order: the joiner is checked first, then the function is applied.
    Objects.requireNonNull(joiner);
    Objects.requireNonNull(configFunction);
    StructuredTaskScope<T, R, R_X> scope =
        StructuredTaskScope.open(
            joiner,
            cfg ->
                configFunction.apply(
                    cfg.withName(name).withThreadFactory(Thread.ofVirtual().factory())));
    return new TracedScope<>(name, scope);
  }

  /**
   * Opens a traced scope with the given name and joiner, on virtual threads. Mirrors {@link
   * StructuredTaskScope#open(Joiner)}. The joiner controls how {@link #join()} waits for subtasks,
   * what value it returns and which exception it throws.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("race", Joiner.<Response>anySuccessfulOrThrow())) {
   *   scope.fork(() -> primaryService.call(request));
   *   scope.fork(() -> fallbackService.call(request));
   *   Response first = scope.join();
   * }
   * }</pre>
   *
   * @param <T> the result type of the tasks forked in the scope.
   * @param <R> the result type produced by the joiner.
   * @param <R_X> the exception type {@link #join()} throws when the joiner's outcome is an
   *     exception.
   * @param name scope name, recorded on every event; must be non-null and non-blank.
   * @param joiner the joiner to use; must be non-null.
   * @return a new open {@code TracedScope}.
   * @throws IllegalArgumentException if {@code name} is null or blank.
   * @throws NullPointerException if {@code joiner} is null.
   */
  public static <T, R, R_X extends Throwable> TracedScope<T, R, R_X> open(
      String name, Joiner<? super T, ? extends R, R_X> joiner) {
    return open(name, joiner, UnaryOperator.identity());
  }

  /**
   * Opens a traced scope with the given name and configuration function, using {@link
   * Joiner#awaitAllSuccessfulOrThrow()}. Mirrors {@link StructuredTaskScope#open(UnaryOperator)}.
   * {@link #join()} throws {@link ExecutionException} if any subtask fails.
   *
   * <pre>{@code
   * try (var scope =
   *     TracedScope.open("checkout", cfg -> cfg.withThreadFactory(Thread.ofPlatform().factory()))) {
   *   Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
   *   scope.join();
   *   return quote.get();
   * }
   * }</pre>
   *
   * @param <T> the result type of the tasks forked in the scope.
   * @param name scope name, recorded on every event; must be non-null and non-blank.
   * @param configFunction a function applied to the scope's configuration; must be non-null.
   * @return a new open {@code TracedScope<T, Void, ExecutionException>}.
   * @throws IllegalArgumentException if {@code name} is null or blank.
   * @throws NullPointerException if {@code configFunction} is null.
   */
  public static <T> TracedScope<T, Void, ExecutionException> open(
      String name, UnaryOperator<Configuration> configFunction) {
    return open(name, Joiner.<T>awaitAllSuccessfulOrThrow(), configFunction);
  }

  /**
   * Opens a traced scope with the given name, on virtual threads, using {@link
   * Joiner#awaitAllSuccessfulOrThrow()}. Mirrors {@link StructuredTaskScope#open()}. {@link
   * #join()} throws {@link ExecutionException} if any subtask fails.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("checkout")) {
   *   Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
   *   scope.join();
   *   return quote.get();
   * }
   * }</pre>
   *
   * @param <T> the result type of the tasks forked in the scope.
   * @param name scope name, recorded on every event; must be non-null and non-blank.
   * @return a new open {@code TracedScope<T, Void, ExecutionException>}.
   * @throws IllegalArgumentException if {@code name} is null or blank.
   */
  public static <T> TracedScope<T, Void, ExecutionException> open(String name) {
    return open(name, Joiner.<T>awaitAllSuccessfulOrThrow(), UnaryOperator.identity());
  }

  private static void requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-null and non-blank");
    }
  }

  // ---- The one constructor: private, called once the underlying scope is open ------------

  private TracedScope(String name, StructuredTaskScope<T, R, R_X> scope) {
    this.name = name;
    this.scope = scope;
    this.scopeId = SCOPE_ID_COUNTER.incrementAndGet();
    var event = new ScopeOpenedEvent();
    event.scopeId = this.scopeId;
    event.scopeName = name;
    event.taskId = 0L;
    event.threadName = Thread.currentThread().getName();
    event.commit();
  }

  // ---- Instance methods ----------------------------------------------------------------

  /**
   * Returns the {@code name} given to {@code open}, the {@code scopeName} of every event this scope
   * emits.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("checkout")) {
   *   String name = scope.name(); // "checkout"
   *   scope.join();
   * }
   * }</pre>
   *
   * @return the scope name. Never null or blank.
   */
  public String name() {
    return name;
  }

  /**
   * Forks a value-returning task into the scope. Mirrors {@link
   * StructuredTaskScope#fork(Callable)}. Emits a "task forked" JFR event on the calling thread, and
   * exactly one of "task succeeded", "task failed", or "task cancelled" on the task's own thread
   * when it terminates.
   *
   * <p>The forked event is stamped with an auto-derived task label: the {@link Callable}'s simple
   * class name when {@code task} is a real user class, or {@code SimpleClass#method:line} of the
   * call site otherwise (e.g. {@code "OrderService#checkout:42"} for a lambda forked inside that
   * method). For full control over the label use {@link #fork(String, Callable)}.
   *
   * <p>Cancellation is reported when the task observes scope shutdown (e.g. a sibling failed or the
   * winning joiner shut down the scope) before completing on its own.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("lookup")) {
   *   Subtask<Quote> quote = scope.fork(() -> pricingService.quote(cart));
   *   scope.join();
   *   return quote.get();
   * }
   * }</pre>
   *
   * @param task the callable to run as a structured subtask; must be non-null.
   * @param <U> result type of the subtask, a subtype of the scope's {@code T}.
   * @return a {@link Subtask} handle whose value is observable after {@link #join()}.
   * @throws NullPointerException if {@code task} is null; nothing is emitted.
   */
  public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
    Objects.requireNonNull(task);
    var callSite = TaskNameDeriver.deriveCallSite(task);
    return doFork(TaskNameDeriver.format(callSite), task, callSite);
  }

  /**
   * Forks a task that returns no result into the scope. Mirrors {@link
   * StructuredTaskScope#fork(Runnable)}: the subtask's result is {@code null}. Traced like {@link
   * #fork(Callable)}: a "task forked" event, then exactly one of "task succeeded", "task failed" or
   * "task cancelled".
   *
   * <p>The label and call site are always those of the calling frame, {@code
   * SimpleClass#method:line}, also when {@code task} is a named {@link Runnable} class. That is
   * what the agent records for a plain scope's {@code fork(Runnable)}, which hands the agent only
   * the JDK's own wrapper around the {@code Runnable}, and both producers stamp the same call site.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("notify")) {
   *   scope.fork(() -> auditLog.record(cart));
   *   scope.join();
   * }
   * }</pre>
   *
   * @param task the runnable to run as a structured subtask; must be non-null.
   * @param <U> result type of the subtask, a subtype of the scope's {@code T}; its value is {@code
   *     null}.
   * @return a {@link Subtask} handle whose value, {@code null}, is observable after {@link
   *     #join()}.
   * @throws NullPointerException if {@code task} is null; nothing is emitted.
   */
  public <U extends T> Subtask<U> fork(Runnable task) {
    Objects.requireNonNull(task);
    // What StructuredTaskScope.fork(Runnable) does: run the task, the result is null. The call
    // site is derived from this wrapper, as the agent derives it from the JDK's, so it is the
    // calling frame whatever the Runnable's class.
    Callable<U> callable =
        () -> {
          task.run();
          return null;
        };
    var callSite = TaskNameDeriver.deriveCallSite(callable);
    return doFork(TaskNameDeriver.format(callSite), callable, callSite);
  }

  /**
   * Forks a value-returning task into the scope with an explicit human-readable label. Not in
   * {@link StructuredTaskScope}; otherwise the same as {@link #fork(Callable)}.
   *
   * <p>The label appears in the rendered HTML report next to the per-scope task index. Use this
   * overload when the auto-derived label from {@link #fork(Callable)} (the lambda's enclosing
   * method name, e.g. {@code OrderService#checkout:42}) is less informative than the operation name
   * the task represents (e.g. {@code "findUser"}).
   *
   * <p>The fork's source location (class, enclosing method, and line) is captured independently of
   * this label and is unaffected by it — tooling such as the IntelliJ plugin's click-to-source
   * navigation uses it directly, so supplying an explicit label never loses navigability.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("checkout")) {
   *   Subtask<User> user = scope.fork("findUser", () -> userService.findById(id));
   *   Subtask<Cart> cart = scope.fork("loadCart", () -> cartService.load(id));
   *   scope.join();
   *   return new Session(user.get(), cart.get());
   * }
   * }</pre>
   *
   * @param taskName label for the task; may be {@code null} or blank, in which case the report
   *     falls back to the per-scope index.
   * @param task the callable to run as a structured subtask; must be non-null.
   * @param <U> result type of the subtask, a subtype of the scope's {@code T}.
   * @return a {@link Subtask} handle whose value is observable after {@link #join()}.
   * @throws NullPointerException if {@code task} is null; nothing is emitted.
   */
  public <U extends T> Subtask<U> fork(String taskName, Callable<? extends U> task) {
    Objects.requireNonNull(task);
    return doFork(taskName, task, TaskNameDeriver.deriveCallSite(task));
  }

  private <U extends T> Subtask<U> doFork(
      String taskName, Callable<? extends U> task, TaskNameDeriver.CallSiteInfo callSite) {
    long id = taskIdCounter.incrementAndGet();

    var forked = new TaskForkedEvent();
    forked.scopeId = this.scopeId;
    forked.scopeName = name;
    forked.taskId = id;
    forked.taskName = taskName;
    forked.threadName = Thread.currentThread().getName();
    if (callSite != null) {
      forked.callSiteClassName = callSite.className();
      forked.callSiteMethodName = callSite.methodName();
      forked.callSiteLine = callSite.line();
    }
    forked.commit();

    return scope.fork(
        () -> {
          try {
            U result = task.call();
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
            ev.exceptionStackTrace = TaskFailedEvent.formatStackTrace(e);
            ev.commit();
            throw e;
          }
        });
  }

  /**
   * Waits for the joiner's completion condition and returns the joiner's result. Mirrors {@link
   * StructuredTaskScope#join()}. The exact semantics depend on the joiner given to {@code open}:
   *
   * <ul>
   *   <li>{@link Joiner#awaitAllSuccessfulOrThrow()} (default) — waits for all tasks, throws {@link
   *       ExecutionException} on the first failure; returns {@code null}.
   *   <li>{@link Joiner#anySuccessfulOrThrow()} — returns as soon as one task succeeds, cancels the
   *       rest; returns that task's result. Throws {@link ExecutionException} if every task fails.
   *   <li>{@link Joiner#allSuccessfulOrThrow()} — waits for all tasks, throws {@link
   *       ExecutionException} on any failure; returns a {@code List} of all results in fork order.
   *   <li>{@link Joiner#allUntil(java.util.function.Predicate)} — waits for all tasks, or until the
   *       predicate cancels the scope; returns the {@code List} of subtasks and never throws for a
   *       failed subtask.
   * </ul>
   *
   * <p>The built-in joiners also take a function that maps the failure to a different exception
   * type, which then becomes {@code R_X}.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("lookup", Joiner.<Response>anySuccessfulOrThrow())) {
   *   scope.fork(() -> primaryService.call(request));
   *   scope.fork(() -> fallbackService.call(request));
   *   Response found = scope.join();
   * } catch (ExecutionException e) {
   *   // both calls failed; e.getCause() is the first failure
   * }
   *
   * try (var scope =
   *     TracedScope.open(
   *         "io", Joiner.<Response, IOException>allSuccessfulOrThrow(IOException::new))) {
   *   scope.fork(() -> serviceA.call());
   *   List<Response> results = scope.join();
   * } catch (IOException e) {
   *   // a call failed; e.getCause() is its exception
   * }
   * }</pre>
   *
   * @return the joiner's result ({@code R}).
   * @throws R_X if the joiner's outcome is an exception, for example {@link ExecutionException}
   *     when a subtask failed under a built-in joiner.
   * @throws InterruptedException if the owner thread is interrupted while waiting.
   */
  public R join() throws R_X, InterruptedException {
    return scope.join();
  }

  /**
   * Returns {@code true} if the scope is cancelled: the joiner cancelled it, the timeout expired,
   * or it was closed before {@link #join()} completed. Mirrors {@link
   * StructuredTaskScope#isCancelled()} and only delegates; it emits no event.
   *
   * <pre>{@code
   * try (var scope = TracedScope.open("race", Joiner.<Response>anySuccessfulOrThrow())) {
   *   scope.fork(() -> primaryService.call(request));
   *   scope.fork(() -> fallbackService.call(request));
   *   Response first = scope.join();
   *   boolean losersCancelled = scope.isCancelled(); // true: the first success cancelled it
   * }
   * }</pre>
   *
   * @return {@code true} if the scope is cancelled.
   */
  public boolean isCancelled() {
    return scope.isCancelled();
  }

  /**
   * Closes the scope, cancelling and joining any still-running subtasks. Mirrors {@link
   * StructuredTaskScope#close()}. Emits a "scope closed" JFR event after all subtasks have
   * terminated.
   *
   * <p>Idempotent: subsequent calls are no-ops and emit no further events.
   *
   * <pre>{@code
   * var scope = TracedScope.open("manual");
   * try {
   *   scope.fork(() -> auditLog.record(cart));
   *   scope.join();
   * } finally {
   *   scope.close(); // or use try-with-resources
   * }
   * }</pre>
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
