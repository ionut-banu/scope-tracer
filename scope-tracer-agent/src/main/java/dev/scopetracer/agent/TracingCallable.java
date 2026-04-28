package dev.scopetracer.agent;

import dev.scopetracer.core.events.TaskCancelledEvent;
import dev.scopetracer.core.events.TaskFailedEvent;
import dev.scopetracer.core.events.TaskSucceededEvent;
import java.util.concurrent.Callable;

/**
 * Wraps a user-supplied {@link Callable} to emit JFR task-completion events when the task finishes.
 *
 * <p>Emits exactly one of:
 *
 * <ul>
 *   <li>{@code TaskSucceededEvent} — task returned normally.
 *   <li>{@code TaskFailedEvent} — task threw a non-{@link InterruptedException} exception.
 *   <li>{@code TaskCancelledEvent} — task observed scope shutdown via {@link InterruptedException}.
 * </ul>
 *
 * <p>This class must be accessible from the bootstrap classloader (it is shaded into the agent
 * fat-jar which is listed on {@code Boot-Class-Path}).
 *
 * @param <T> result type of the wrapped callable.
 */
public final class TracingCallable<T> implements Callable<T> {

  private final Callable<? extends T> delegate;
  private final String scopeName;
  private final long taskId;

  /**
   * Creates a new tracing wrapper.
   *
   * @param delegate the original callable; must be non-null.
   * @param scopeName the scope name from {@link AgentState}; recorded on every event.
   * @param taskId the task ID assigned by the fork advice.
   */
  public TracingCallable(Callable<? extends T> delegate, String scopeName, long taskId) {
    this.delegate = delegate;
    this.scopeName = scopeName;
    this.taskId = taskId;
  }

  @Override
  public T call() throws Exception {
    try {
      T result = delegate.call();
      TaskSucceededEvent ev = new TaskSucceededEvent();
      ev.scopeName = scopeName;
      ev.taskId = taskId;
      ev.threadName = Thread.currentThread().getName();
      ev.commit();
      return result;
    } catch (InterruptedException e) {
      TaskCancelledEvent ev = new TaskCancelledEvent();
      ev.scopeName = scopeName;
      ev.taskId = taskId;
      ev.threadName = Thread.currentThread().getName();
      ev.commit();
      Thread.currentThread().interrupt();
      throw e;
    } catch (Exception e) {
      TaskFailedEvent ev = new TaskFailedEvent();
      ev.scopeName = scopeName;
      ev.taskId = taskId;
      ev.threadName = Thread.currentThread().getName();
      ev.exceptionType = e.getClass().getName();
      ev.commit();
      throw e;
    }
  }
}
