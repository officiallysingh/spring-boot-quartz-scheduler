/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */

package org.quartz.impl.triggers;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.ScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.utils.Instants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A concrete <code>{@link Trigger}</code> that is used to fire a <code>{@link org.quartz.JobDetail}
 * </code> at given moments in time, defined with Unix 'cron-like' definitions.
 *
 * @author Sharada Jambula, James House
 * @author Contributions from Mads Henderson
 */
public class CronTriggerImpl extends AbstractTrigger<CronTrigger> implements CronTrigger {

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constants.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** Required for serialization support: custom subclasses are stored Java-serialized. */
  private static final long serialVersionUID = -8644953146451592766L;

  private static final Logger LOGGER = LoggerFactory.getLogger(CronTriggerImpl.class);

  protected static final int YEAR_TO_GIVEUP_SCHEDULING_AT = CronExpression.MAX_YEAR;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private @Nullable CronExpression cronEx = null;

  // null until setStartTime, or bind() when the trigger is scheduled
  private @Nullable Instant startTime;

  private @Nullable Instant endTime = null;
  private @Nullable Instant nextFireTime = null;
  private @Nullable Instant previousFireTime = null;
  private transient @Nullable ZoneId timeZone = null;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a <code>CronTrigger</code> with no settings.
   *
   * <p>The start time and the time zone are unset: the scheduler sets them to "now" by its clock
   * and to its default zone when the trigger is scheduled ({@link #bind(java.time.Clock, ZoneId)}).
   */
  public CronTriggerImpl() {
    super();
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  @Override
  public Object clone() {
    CronTriggerImpl copy = (CronTriggerImpl) super.clone();
    if (cronEx != null) {
      copy.setCronExpression(new CronExpression(cronEx));
    }
    return copy;
  }

  /**
   * Set the cron expression; the trigger's time zone is kept.
   *
   * @param cronExpression the expression
   * @throws ParseException if the expression is not valid
   */
  public void setCronExpression(String cronExpression) throws ParseException {
    this.cronEx = new CronExpression(cronExpression);
    this.cronEx.setTimeZone(this.timeZone);
  }

  // null only for an incomplete trigger whose expression is not set yet, as in Quartz
  @SuppressWarnings("NullAway")
  public String getCronExpression() {
    return cronEx == null ? null : cronEx.getCronExpression();
  }

  /**
   * Set the CronExpression to the given one. A time zone set on the passed-in CronExpression
   * over-rides any that was already set on the Trigger; an expression without one takes the
   * trigger's.
   */
  public void setCronExpression(CronExpression cronExpression) {
    this.cronEx = cronExpression;
    if (cronExpression.hasTimeZone()) {
      this.timeZone = cronExpression.getTimeZone();
    } else {
      cronExpression.setTimeZone(this.timeZone);
    }
  }

  /** Get the time at which the <code>CronTrigger</code> should occur. */
  @Override
  public @Nullable Instant getStartTime() {
    return this.startTime;
  }

  @Override
  public void setStartTime(Instant startTime) {
    if (startTime == null) {
      throw new IllegalArgumentException("Start time cannot be null");
    }

    Instant eTime = getEndTime();
    if (eTime != null && eTime.isBefore(startTime)) {
      throw new IllegalArgumentException("End time cannot be before start time");
    }

    // round off milliseconds: cron fire times are whole seconds
    this.startTime = startTime.truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * Get the time at which the <code>CronTrigger</code> should quit repeating - even if repeatCount
   * isn't yet satisfied.
   *
   * @see #getFinalFireTime()
   */
  @Override
  public @Nullable Instant getEndTime() {
    return this.endTime;
  }

  @Override
  public void setEndTime(@Nullable Instant endTime) {
    Instant sTime = getStartTime();
    if (sTime != null && endTime != null && sTime.isAfter(endTime)) {
      throw new IllegalArgumentException("End time cannot be before start time");
    }

    this.endTime = Instants.toMillis(endTime);
  }

  /**
   * Returns the next time at which the <code>Trigger</code> is scheduled to fire. If the trigger
   * will not fire again, <code>null</code> will be returned. Note that the time returned can
   * possibly be in the past, if the time that was computed for the trigger to next fire has already
   * arrived, but the scheduler has not yet been able to fire the trigger (which would likely be due
   * to lack of resources e.g. threads).
   *
   * <p>The value returned is not guaranteed to be valid until after the <code>Trigger</code> has
   * been added to the scheduler.
   */
  @Override
  public @Nullable Instant getNextFireTime() {
    return this.nextFireTime;
  }

  /**
   * Returns the previous time at which the <code>CronTrigger</code> fired. If the trigger has not
   * yet fired, <code>null</code> will be returned.
   */
  @Override
  public @Nullable Instant getPreviousFireTime() {
    return this.previousFireTime;
  }

  /**
   * Sets the next time at which the <code>CronTrigger</code> will fire. <b>This method should not
   * be invoked by client code.</b>
   */
  public void setNextFireTime(@Nullable Instant nextFireTime) {
    this.nextFireTime = Instants.toMillis(nextFireTime);
  }

  /**
   * Set the previous time at which the <code>CronTrigger</code> fired.
   *
   * <p><b>This method should not be invoked by client code.</b>
   */
  public void setPreviousFireTime(@Nullable Instant previousFireTime) {
    this.previousFireTime = Instants.toMillis(previousFireTime);
  }

  /**
   * {@inheritDoc}
   *
   * <p>UTC until a zone is set or the trigger is scheduled: the scheduler then applies its default
   * zone ({@link org.quartz.Scheduler#getTimeZone()}) to a trigger whose zone was not set
   * explicitly, and the zone is stored with the trigger.
   */
  public ZoneId getTimeZone() {
    if (cronEx != null) {
      return cronEx.getTimeZone();
    }
    return timeZone != null ? timeZone : ZoneOffset.UTC;
  }

  /**
   * Sets the time zone for which the <code>cronExpression</code> of this <code>CronTrigger</code>
   * will be resolved.
   *
   * <p>If {@link #setCronExpression(CronExpression)} is called after this method with an expression
   * that has a time zone, that zone will "win". However if {@link #setCronExpression(String)} is
   * called after this method, the time zone applied by this method will remain in effect, since the
   * String cron expression does not carry a time zone!
   *
   * @param timeZone the zone; {@code null} to unset it, so that the scheduler applies its default
   *     zone when the trigger is scheduled
   */
  public void setTimeZone(@Nullable ZoneId timeZone) {
    if (cronEx != null) {
      cronEx.setTimeZone(timeZone);
    }
    this.timeZone = timeZone;
  }

  @Override
  protected void applyDefaultTimeZone(ZoneId defaultTimeZone) {
    if (timeZone == null) {
      setTimeZone(defaultTimeZone);
    }
  }

  /**
   * Returns the next time at which the <code>CronTrigger</code> will fire, after the given time. If
   * the trigger will not fire after the given time, <code>null</code> will be returned.
   *
   * <p>Note that the date returned is NOT validated against the related org.quartz.Calendar (if
   * any)
   */
  @Override
  public @Nullable Instant getFireTimeAfter(@Nullable Instant afterTime) {
    if (afterTime == null) {
      afterTime = now();
    }

    Instant startTime = requireStartTime();
    if (startTime.isAfter(afterTime)) {
      afterTime = startTime.minusSeconds(1);
    }

    if (getEndTime() != null && (afterTime.compareTo(getEndTime()) >= 0)) {
      return null;
    }

    Instant pot = getTimeAfter(afterTime);
    if (getEndTime() != null && pot != null && pot.isAfter(getEndTime())) {
      return null;
    }

    return pot;
  }

  /**
   * The last time the trigger fires at or before its end time, not validated against its calendar;
   * {@code null} for a trigger without an end time, which has no final fire time.
   *
   * <p>Note that the return time *may* be in the past.
   */
  @Override
  public @Nullable Instant getFinalFireTime() {
    if (getEndTime() == null) {
      return null;
    }
    Instant resultTime = getTimeBefore(getEndTime().plusSeconds(1));

    Instant startTime = getStartTime();
    if ((resultTime != null) && (startTime != null) && (resultTime.isBefore(startTime))) {
      return null;
    }

    return resultTime;
  }

  /** Determines whether or not the <code>CronTrigger</code> will occur again. */
  @Override
  public boolean mayFireAgain() {
    return (getNextFireTime() != null);
  }

  @Override
  protected boolean validateMisfireInstruction(int misfireInstruction) {
    return misfireInstruction >= MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY
        && misfireInstruction <= MISFIRE_INSTRUCTION_DO_NOTHING;
  }

  /**
   * Updates the <code>CronTrigger</code>'s state based on the MISFIRE_INSTRUCTION_XXX that was
   * selected when the <code>CronTrigger</code> was created.
   *
   * <p>If the misfire instruction is set to MISFIRE_INSTRUCTION_SMART_POLICY, then the following
   * scheme will be used:
   *
   * <ul>
   *   <li>The instruction will be interpreted as <code>MISFIRE_INSTRUCTION_FIRE_ONCE_NOW</code>
   * </ul>
   */
  @Override
  public void updateAfterMisfire(org.quartz.@Nullable Calendar cal, Instant now) {
    int instr = getMisfireInstruction();

    if (instr == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) return;

    if (instr == MISFIRE_INSTRUCTION_SMART_POLICY) {
      instr = MISFIRE_INSTRUCTION_FIRE_ONCE_NOW;
    }

    if (instr == MISFIRE_INSTRUCTION_DO_NOTHING) {
      Instant newFireTime = getFireTimeAfter(now);
      while (newFireTime != null && cal != null && !cal.isTimeIncluded(newFireTime)) {
        newFireTime = getFireTimeAfter(newFireTime);
      }
      setNextFireTime(newFireTime);
    } else if (instr == MISFIRE_INSTRUCTION_FIRE_ONCE_NOW) {
      setNextFireTime(now);
    }
  }

  /**
   * Called when the <code>{@link Scheduler}</code> has decided to 'fire' the trigger (execute the
   * associated <code>Job</code>), in order to give the <code>Trigger</code> a chance to update
   * itself for its next triggering (if any).
   *
   * @see #executionComplete(JobExecutionContext, JobExecutionException)
   */
  @Override
  public void triggered(org.quartz.@Nullable Calendar calendar) {
    previousFireTime = nextFireTime;
    nextFireTime = getFireTimeAfter(nextFireTime);

    while (nextFireTime != null && calendar != null && !calendar.isTimeIncluded(nextFireTime)) {
      nextFireTime = getFireTimeAfter(nextFireTime);
    }
  }

  /**
   * @see AbstractTrigger#updateWithNewCalendar(org.quartz.Calendar, java.time.Duration,
   *     java.time.Instant)
   */
  @Override
  public void updateWithNewCalendar(
      org.quartz.@Nullable Calendar calendar, Duration misfireThreshold, Instant now) {
    nextFireTime = getFireTimeAfter(previousFireTime);

    if (nextFireTime == null || calendar == null) {
      return;
    }

    while (nextFireTime != null && !calendar.isTimeIncluded(nextFireTime)) {

      nextFireTime = getFireTimeAfter(nextFireTime);

      if (nextFireTime == null) break;

      // avoid infinite loop
      // Use gregorian only because the constant is based on Gregorian
      if (nextFireTime.atZone(getTimeZone()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
        nextFireTime = null;
      }

      if (nextFireTime != null && nextFireTime.isBefore(now)) {
        long diff = now.toEpochMilli() - nextFireTime.toEpochMilli();
        if (diff >= misfireThreshold.toMillis()) {
          nextFireTime = getFireTimeAfter(nextFireTime);
        }
      }
    }
  }

  /**
   * Called by the scheduler at the time a <code>Trigger</code> is first added to the scheduler, in
   * order to have the <code>Trigger</code> compute its first fire time, based on any associated
   * calendar.
   *
   * <p>After this method has been called, <code>getNextFireTime()</code> should return a valid
   * answer.
   *
   * @return the first time at which the <code>Trigger</code> will be fired by the scheduler, which
   *     is also the same value <code>getNextFireTime()</code> will return (until after the first
   *     firing of the <code>Trigger</code>).
   */
  @Override
  public @Nullable Instant computeFirstFireTime(org.quartz.@Nullable Calendar calendar) {
    nextFireTime = getFireTimeAfter(requireStartTime().minusSeconds(1));

    while (nextFireTime != null && calendar != null && !calendar.isTimeIncluded(nextFireTime)) {
      nextFireTime = getFireTimeAfter(nextFireTime);
    }

    return nextFireTime;
  }

  // null only for an incomplete trigger whose expression is not set yet, as in Quartz
  @SuppressWarnings("NullAway")
  public String getExpressionSummary() {
    return cronEx == null ? null : cronEx.getExpressionSummary();
  }

  /**
   * Get a {@link ScheduleBuilder} that is configured to produce a schedule identical to this
   * trigger's schedule.
   *
   * @see #getTriggerBuilder()
   */
  @Override
  public ScheduleBuilder<CronTrigger> getScheduleBuilder() {

    CronScheduleBuilder cb =
        CronScheduleBuilder.cronSchedule(getCronExpression()).inTimeZone(getTimeZone());

    int misfireInstruction = getMisfireInstruction();
    switch (misfireInstruction) {
      case MISFIRE_INSTRUCTION_SMART_POLICY:
        break;
      case MISFIRE_INSTRUCTION_DO_NOTHING:
        cb.withMisfireHandlingInstructionDoNothing();
        break;
      case MISFIRE_INSTRUCTION_FIRE_ONCE_NOW:
        cb.withMisfireHandlingInstructionFireAndProceed();
        break;
      case MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY:
        cb.withMisfireHandlingInstructionIgnoreMisfires();
        break;
      default:
        LOGGER.warn(
            "Unrecognized misfire policy {}. Derived builder will use the default cron trigger behavior (MISFIRE_INSTRUCTION_FIRE_ONCE_NOW)",
            misfireInstruction);
    }

    return cb;
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Computation Functions
  //
  ////////////////////////////////////////////////////////////////////////////

  protected @Nullable Instant getTimeAfter(Instant afterTime) {
    return (cronEx == null) ? null : cronEx.getNextValidTimeAfter(afterTime);
  }

  /**
   * NOT YET IMPLEMENTED: Returns the time before the given time that this <code>CronTrigger</code>
   * will fire.
   */
  protected @Nullable Instant getTimeBefore(Instant eTime) {
    return (cronEx == null) ? null : cronEx.getTimeBefore(eTime);
  }
}
