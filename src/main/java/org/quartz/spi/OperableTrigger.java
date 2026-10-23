package org.quartz.spi;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/**
 * A {@link MutableTrigger} the scheduler and job stores operate: computing fire times, reacting to
 * firings, misfires and calendar changes. Not for use by applications.
 */
public interface OperableTrigger extends MutableTrigger {

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>Called when the <code>{@link Scheduler}</code> has decided to 'fire' the trigger (execute
   * the associated <code>Job</code>), in order to give the <code>Trigger</code> a chance to update
   * itself for its next triggering (if any).
   *
   * @see #executionComplete(JobExecutionContext, JobExecutionException)
   */
  void triggered(@Nullable Calendar calendar);

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>Called by the scheduler at the time a <code>Trigger</code> is first added to the scheduler,
   * in order to have the <code>Trigger</code> compute its first fire time, based on any associated
   * calendar.
   *
   * <p>After this method has been called, <code>getNextFireTime()</code> should return a valid
   * answer.
   *
   * @return the first time at which the <code>Trigger</code> will be fired by the scheduler, which
   *     is also the same value <code>getNextFireTime()</code> will return (until after the first
   *     firing of the <code>Trigger</code>).
   */
  @Nullable Instant computeFirstFireTime(@Nullable Calendar calendar);

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>Called after the <code>{@link Scheduler}</code> has executed the <code>
   * {@link org.quartz.JobDetail}</code> associated with the <code>Trigger</code> in order to get
   * the final instruction code from the trigger.
   *
   * @param context is the <code>JobExecutionContext</code> that was used by the <code>Job</code>'s
   *     <code>execute(xx)</code> method.
   * @param result is the <code>JobExecutionException</code> thrown by the <code>Job</code>, if any
   *     (may be null).
   * @return one of the <code>CompletedExecutionInstruction</code> constants.
   * @see CompletedExecutionInstruction
   * @see #triggered(Calendar)
   */
  CompletedExecutionInstruction executionComplete(
      JobExecutionContext context, @Nullable JobExecutionException result);

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>To be implemented by the concrete classes that extend this class.
   *
   * <p>The implementation should update the <code>Trigger</code>'s state based on the
   * MISFIRE_INSTRUCTION_XXX that was selected when the <code>Trigger</code> was created.
   */
  void updateAfterMisfire(@Nullable Calendar cal);

  /**
   * Updates this trigger after a misfire using the scheduler's current time.
   *
   * <p>The default delegates to the original method for compatibility with custom trigger
   * implementations. Implementations that calculate from "now" should override this method.
   */
  default void updateAfterMisfire(@Nullable Calendar cal, Instant now) {
    updateAfterMisfire(cal);
  }

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>To be implemented by the concrete class.
   *
   * <p>The implementation should update the <code>Trigger</code>'s state based on the given new
   * version of the associated <code>Calendar</code> (the state should be updated so that it's next
   * fire time is appropriate given the Calendar's new settings).
   *
   * @param cal
   */
  void updateWithNewCalendar(@Nullable Calendar cal, Duration misfireThreshold);

  /**
   * Updates this trigger after its calendar changes, using the scheduler's current time when
   * applying the misfire threshold.
   *
   * <p>The default delegates to the original method for compatibility with custom trigger
   * implementations.
   */
  default void updateWithNewCalendar(
      @Nullable Calendar cal, Duration misfireThreshold, Instant now) {
    updateWithNewCalendar(cal, misfireThreshold);
  }

  /**
   * Validates whether the properties of the <code>JobDetail</code> are valid for submission into a
   * <code>Scheduler</code>.
   *
   * @throws IllegalStateException if a required property (such as Name, Group, Class) is not set.
   */
  void validate() throws SchedulerException;

  /**
   * This method should not be used by the Quartz client.
   *
   * <p>Usable by <code>{@link org.quartz.spi.JobStore}</code> implementations, in order to
   * facilitate 'recognizing' instances of fired <code>Trigger</code> s as their jobs complete
   * execution.
   */
  void setFireInstanceId(@Nullable String id);

  /** This method should not be used by the Quartz client. */
  @Nullable String getFireInstanceId();

  /**
   * Set the next time the trigger fires; used by job stores when loading a trigger.
   *
   * @param nextFireTime the next fire time, or {@code null} if it will not fire again
   */
  void setNextFireTime(@Nullable Instant nextFireTime);

  /**
   * Set the time the trigger last fired; used by job stores when loading a trigger.
   *
   * @param previousFireTime the previous fire time, or {@code null}
   */
  void setPreviousFireTime(@Nullable Instant previousFireTime);
}
