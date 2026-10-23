package org.quartz.spi;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of firing one trigger in {@link JobStore#triggersFired}: either a {@link
 * TriggerFiredBundle} (or {@code null} when the trigger, its job or calendar no longer exists, or
 * it was not in the acquired state), or the exception that firing it raised.
 *
 * @author lorban
 */
public class TriggerFiredResult {

  private @Nullable TriggerFiredBundle triggerFiredBundle;

  private @Nullable Exception exception;

  /**
   * A trigger that was fired, or could not be ({@code null}).
   *
   * @param triggerFiredBundle what the scheduler needs to run the job, or {@code null}
   */
  public TriggerFiredResult(@Nullable TriggerFiredBundle triggerFiredBundle) {
    this.triggerFiredBundle = triggerFiredBundle;
  }

  /**
   * A trigger whose firing failed.
   *
   * @param exception the reason
   */
  public TriggerFiredResult(Exception exception) {
    this.exception = exception;
  }

  /**
   * What the scheduler needs to run the job.
   *
   * @return the bundle, or {@code null} if the trigger was not fired
   */
  public @Nullable TriggerFiredBundle getTriggerFiredBundle() {
    return triggerFiredBundle;
  }

  /**
   * Why firing the trigger failed.
   *
   * @return the exception, or {@code null} if it did not fail
   */
  public @Nullable Exception getException() {
    return exception;
  }
}
