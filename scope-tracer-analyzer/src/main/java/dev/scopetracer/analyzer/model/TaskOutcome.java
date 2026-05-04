package dev.scopetracer.analyzer.model;

/**
 * The terminal outcome of a forked subtask.
 *
 * <pre>{@code
 * switch (task.outcome()) {
 *   case TaskOutcome.Success s        -> System.out.println("ok");
 *   case TaskOutcome.Failed f         -> System.out.println("failed: " + f.exceptionType());
 *   case TaskOutcome.Cancelled c      -> System.out.println("cancelled");
 *   case null                         -> System.out.println("incomplete recording");
 * }
 * }</pre>
 */
public sealed interface TaskOutcome
    permits TaskOutcome.Success, TaskOutcome.Failed, TaskOutcome.Cancelled {

  /** The subtask returned normally. */
  record Success() implements TaskOutcome {}

  /**
   * The subtask threw an exception.
   *
   * @param exceptionType fully-qualified class name of the thrown exception.
   * @param exceptionMessage value of {@link Throwable#getMessage()}; {@code null} when the
   *     exception carries no message.
   */
  record Failed(String exceptionType, String exceptionMessage) implements TaskOutcome {}

  /** The subtask was interrupted by scope shutdown before completing on its own. */
  record Cancelled() implements TaskOutcome {}
}
