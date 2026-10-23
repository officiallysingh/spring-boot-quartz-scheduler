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

import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.DateBuilder.IntervalUnit;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.ScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.utils.Instants;
import org.quartz.utils.LenientDateTime;

/**
 * A concrete implementation of DailyTimeIntervalTrigger that is used to fire a <code>
 * {@link org.quartz.JobDetail}</code> based upon daily repeating time intervals.
 *
 * <p>The trigger will fire every N (see {@link #setRepeatInterval(int)} ) seconds, minutes or hours
 * (see {@link #setRepeatIntervalUnit(org.quartz.DateBuilder.IntervalUnit)}) during a given time
 * window on specified days of the week.
 *
 * <p>For example#1, a trigger can be set to fire every 72 minutes between 8:00 and 11:00 everyday.
 * It's fire times would be 8:00, 9:12, 10:24, then next day would repeat: 8:00, 9:12, 10:24 again.
 *
 * <p>For example#2, a trigger can be set to fire every 23 minutes between 9:20 and 16:47 Monday
 * through Friday.
 *
 * <p>On each day, the starting fire time is reset to startTimeOfDay value, and then it will add
 * repeatInterval value to it until the endTimeOfDay is reached. If you set daysOfWeek values, then
 * fire time will only occur during those week days period. Again, remember this trigger will reset
 * fire time each day with startTimeOfDay, regardless of your interval or endTimeOfDay!
 *
 * <p>The default values for fields if not set are: startTimeOfDay defaults to 00:00:00, the
 * endTimeOfDay default to 23:59:59, and daysOfWeek is default to every day. The startTime default
 * to current time-stamp now, while endTime has not value.
 *
 * <p>If startTime is before startTimeOfDay, then startTimeOfDay will be used and startTime has no
 * affect other than to specify the first day of firing. Else if startTime is after startTimeOfDay,
 * then the first fire time for that day will be the next interval after the startTime. For example,
 * if you set startingTimeOfDay=9am, endingTimeOfDay=11am, interval=15 mins, and startTime=9:33am,
 * then the next fire time will be 9:45pm. Note also that if you do not set startTime value, the
 * trigger builder will default to current time, and current time maybe before or after the
 * startTimeOfDay! So be aware how you set your startTime.
 *
 * <p>This trigger also supports "repeatCount" feature to end the trigger fire time after a certain
 * number of count is reached. Just as the SimpleTrigger, setting repeatCount=0 means trigger will
 * fire once only! Setting any positive count then the trigger will repeat count + 1 times. Unlike
 * SimpleTrigger, the default value of repeatCount of this trigger is set to REPEAT_INDEFINITELY
 * instead of 0 though.
 *
 * @see DailyTimeIntervalTrigger
 * @see DailyTimeIntervalScheduleBuilder
 * @since 2.1.0
 * @author James House
 * @author Zemian Deng &lt;saltnlight5@gmail.com&gt;
 */
public class DailyTimeIntervalTriggerImpl extends AbstractTrigger<DailyTimeIntervalTrigger>
    implements DailyTimeIntervalTrigger, CoreTrigger {

  private static final long serialVersionUID = -632667786771388749L;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constants.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */
  private static final int YEAR_TO_GIVEUP_SCHEDULING_AT = Year.now().getValue() + 100;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  // set by the constructors or setStartTime before the trigger is used
  @SuppressWarnings("NullAway.Init")
  private Instant startTime;

  private @Nullable Instant endTime = null;

  private @Nullable Instant nextFireTime = null;

  private @Nullable Instant previousFireTime = null;

  private int repeatCount = REPEAT_INDEFINITELY;

  private int repeatInterval = 1;

  private IntervalUnit repeatIntervalUnit = IntervalUnit.MINUTE;

  private @Nullable Set<Integer> daysOfWeek;

  private @Nullable TimeOfDay startTimeOfDay;

  private @Nullable TimeOfDay endTimeOfDay;

  private int timesTriggered = 0;

  private boolean complete = false;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** Create a <code>DailyTimeIntervalTrigger</code> with no settings. */
  public DailyTimeIntervalTriggerImpl() {
    super();
  }

  /**
   * Create a <code>DailyTimeIntervalTrigger</code> that will occur immediately, and repeat at the
   * given interval.
   *
   * @param startTimeOfDay The <code>TimeOfDay</code> that the repeating should begin occurring.
   * @param endTimeOfDay The <code>TimeOfDay</code> that the repeating should stop occurring.
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   * @throws IllegalArgumentException if an invalid IntervalUnit is given, or the repeat interval is
   *     zero or less.
   */
  public DailyTimeIntervalTriggerImpl(
      String name,
      TimeOfDay startTimeOfDay,
      TimeOfDay endTimeOfDay,
      IntervalUnit intervalUnit,
      int repeatInterval) {
    this(name, null, startTimeOfDay, endTimeOfDay, intervalUnit, repeatInterval);
  }

  /**
   * Create a <code>DailyTimeIntervalTrigger</code> that will occur immediately, and repeat at the
   * given interval.
   *
   * @param startTimeOfDay The <code>TimeOfDay</code> that the repeating should begin occurring.
   * @param endTimeOfDay The <code>TimeOfDay</code> that the repeating should stop occurring.
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   * @throws IllegalArgumentException if an invalid IntervalUnit is given, or the repeat interval is
   *     zero or less.
   */
  public DailyTimeIntervalTriggerImpl(
      String name,
      @Nullable String group,
      TimeOfDay startTimeOfDay,
      TimeOfDay endTimeOfDay,
      IntervalUnit intervalUnit,
      int repeatInterval) {
    this(
        name,
        group,
        Instants.now(),
        null,
        startTimeOfDay,
        endTimeOfDay,
        intervalUnit,
        repeatInterval);
  }

  /**
   * Create a <code>DailyTimeIntervalTrigger</code> that will occur at the given time, and repeat at
   * the given interval until the given end time.
   *
   * @param startTime the time for the <code>Trigger</code> to fire.
   * @param endTime the time for the <code>Trigger</code> to quit repeat firing, or {@code null} for
   *     none.
   * @param startTimeOfDay The <code>TimeOfDay</code> that the repeating should begin occurring.
   * @param endTimeOfDay The <code>TimeOfDay</code> that the repeating should stop occurring.
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   * @param repeatInterval the pause between repeat firings.
   * @throws IllegalArgumentException if an invalid IntervalUnit is given, or the repeat interval is
   *     zero or less.
   */
  public DailyTimeIntervalTriggerImpl(
      String name,
      Instant startTime,
      @Nullable Instant endTime,
      TimeOfDay startTimeOfDay,
      TimeOfDay endTimeOfDay,
      IntervalUnit intervalUnit,
      int repeatInterval) {
    this(
        name, null, startTime, endTime, startTimeOfDay, endTimeOfDay, intervalUnit, repeatInterval);
  }

  /**
   * Create a <code>DailyTimeIntervalTrigger</code> that will occur at the given time, and repeat at
   * the given interval until the given end time.
   *
   * @param startTime the time for the <code>Trigger</code> to fire.
   * @param endTime the time for the <code>Trigger</code> to quit repeat firing, or {@code null} for
   *     none.
   * @param startTimeOfDay The <code>TimeOfDay</code> that the repeating should begin occurring.
   * @param endTimeOfDay The <code>TimeOfDay</code> that the repeating should stop occurring.
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   * @param repeatInterval the pause between repeat firings.
   * @throws IllegalArgumentException if an invalid IntervalUnit is given, or the repeat interval is
   *     zero or less.
   */
  public DailyTimeIntervalTriggerImpl(
      String name,
      @Nullable String group,
      Instant startTime,
      @Nullable Instant endTime,
      TimeOfDay startTimeOfDay,
      TimeOfDay endTimeOfDay,
      IntervalUnit intervalUnit,
      int repeatInterval) {
    super(name, group);

    setStartTime(startTime);
    setEndTime(endTime);
    setRepeatIntervalUnit(intervalUnit);
    setRepeatInterval(repeatInterval);
    setStartTimeOfDay(startTimeOfDay);
    setEndTimeOfDay(endTimeOfDay);
  }

  /**
   * Create a <code>DailyTimeIntervalTrigger</code> that will occur at the given time, fire the
   * identified <code>Job</code> and repeat at the given interval until the given end time.
   *
   * @param startTime the time for the <code>Trigger</code> to fire.
   * @param endTime the time for the <code>Trigger</code> to quit repeat firing, or {@code null} for
   *     none.
   * @param startTimeOfDay The <code>TimeOfDay</code> that the repeating should begin occurring.
   * @param endTimeOfDay The <code>TimeOfDay</code> that the repeating should stop occurring.
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   * @param repeatInterval the pause between repeat firings.
   * @throws IllegalArgumentException if an invalid IntervalUnit is given, or the repeat interval is
   *     zero or less.
   */
  public DailyTimeIntervalTriggerImpl(
      String name,
      @Nullable String group,
      String jobName,
      @Nullable String jobGroup,
      Instant startTime,
      @Nullable Instant endTime,
      TimeOfDay startTimeOfDay,
      TimeOfDay endTimeOfDay,
      IntervalUnit intervalUnit,
      int repeatInterval) {
    super(name, group, jobName, jobGroup);

    setStartTime(startTime);
    setEndTime(endTime);
    setRepeatIntervalUnit(intervalUnit);
    setRepeatInterval(repeatInterval);
    setStartTimeOfDay(startTimeOfDay);
    setEndTimeOfDay(endTimeOfDay);
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Get the time at which the <code>DailyTimeIntervalTrigger</code> should occur. It defaults to
   * the getStartTimeOfDay of current day.
   */
  @Override
  public Instant getStartTime() {
    if (startTime == null) {
      startTime = Instants.now();
    }
    return startTime;
  }

  /**
   * Set the time at which the <code>DailyTimeIntervalTrigger</code> should occur.
   *
   * @exception IllegalArgumentException if startTime is <code>null</code>.
   */
  @Override
  public void setStartTime(Instant startTime) {
    if (startTime == null) {
      throw new IllegalArgumentException("Start time cannot be null");
    }

    Instant eTime = getEndTime();
    if (eTime != null && eTime.isBefore(startTime)) {
      throw new IllegalArgumentException("End time cannot be before start time");
    }

    this.startTime = Instants.toMillis(startTime);
  }

  /**
   * Get the time at which the <code>DailyTimeIntervalTrigger</code> should quit repeating.
   *
   * @see #getFinalFireTime()
   */
  @Override
  public @Nullable Instant getEndTime() {
    return endTime;
  }

  /**
   * Set the time at which the <code>DailyTimeIntervalTrigger</code> should quit repeating (and be
   * automatically deleted).
   *
   * @exception IllegalArgumentException if endTime is before start time.
   */
  @Override
  public void setEndTime(@Nullable Instant endTime) {
    Instant sTime = getStartTime();
    if (sTime != null && endTime != null && sTime.isAfter(endTime)) {
      throw new IllegalArgumentException("End time cannot be before start time");
    }

    this.endTime = Instants.toMillis(endTime);
  }

  public IntervalUnit getRepeatIntervalUnit() {
    return repeatIntervalUnit;
  }

  /**
   * Set the interval unit - the time unit on with the interval applies.
   *
   * @param intervalUnit The repeat interval unit. The only intervals that are valid for this type
   *     of trigger are {@link IntervalUnit#SECOND}, {@link IntervalUnit#MINUTE}, and {@link
   *     IntervalUnit#HOUR}.
   */
  public void setRepeatIntervalUnit(IntervalUnit intervalUnit) {
    if (repeatIntervalUnit == null
        || !((repeatIntervalUnit.equals(IntervalUnit.SECOND)
            || repeatIntervalUnit.equals(IntervalUnit.MINUTE)
            || repeatIntervalUnit.equals(IntervalUnit.HOUR))))
      throw new IllegalArgumentException(
          "Invalid repeat IntervalUnit (must be SECOND, MINUTE or HOUR).");
    this.repeatIntervalUnit = intervalUnit;
  }

  public int getRepeatInterval() {
    return repeatInterval;
  }

  /**
   * set the time interval that will be added to the <code>DailyTimeIntervalTrigger</code>'s fire
   * time (in the set repeat interval unit) in order to calculate the time of the next trigger
   * repeat.
   *
   * @exception IllegalArgumentException if repeatInterval is &lt; 1
   */
  public void setRepeatInterval(int repeatInterval) {
    if (repeatInterval < 1) {
      throw new IllegalArgumentException("Repeat interval must be >= 1");
    }

    this.repeatInterval = repeatInterval;
  }

  public int getTimesTriggered() {
    return timesTriggered;
  }

  /** Set the number of times the <code>DailyTimeIntervalTrigger</code> has already fired. */
  public void setTimesTriggered(int timesTriggered) {
    this.timesTriggered = timesTriggered;
  }

  @Override
  protected boolean validateMisfireInstruction(int misfireInstruction) {
    return misfireInstruction >= MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY
        && misfireInstruction <= MISFIRE_INSTRUCTION_DO_NOTHING;
  }

  /**
   * Updates the <code>DailyTimeIntervalTrigger</code>'s state based on the MISFIRE_INSTRUCTION_XXX
   * that was selected when the <code>DailyTimeIntervalTrigger</code> was created.
   *
   * <p>If the misfire instruction is set to MISFIRE_INSTRUCTION_SMART_POLICY, then the following
   * scheme will be used:
   *
   * <ul>
   *   <li>The instruction will be interpreted as <code>MISFIRE_INSTRUCTION_FIRE_ONCE_NOW</code>
   * </ul>
   */
  @Override
  public void updateAfterMisfire(org.quartz.@Nullable Calendar cal) {
    updateAfterMisfire(cal, Instants.now());
  }

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
      // fire once now...
      setNextFireTime(now);
      // the new fire time afterward will magically preserve the original
      // time of day for firing for day/week/month interval triggers,
      // because of the way getFireTimeAfter() works - in its always restarting
      // computation from the start time.
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
    timesTriggered++;
    previousFireTime = nextFireTime;
    nextFireTime = getFireTimeAfter(nextFireTime);

    while (nextFireTime != null && calendar != null && !calendar.isTimeIncluded(nextFireTime)) {

      nextFireTime = getFireTimeAfter(nextFireTime);

      if (nextFireTime == null) break;

      // avoid infinite loop
      if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
        nextFireTime = null;
      }
    }

    if (nextFireTime == null) {
      complete = true;
    }
  }

  /**
   * @see org.quartz.impl.triggers.AbstractTrigger#updateWithNewCalendar(org.quartz.Calendar,
   *     java.time.Duration)
   */
  @Override
  public void updateWithNewCalendar(
      org.quartz.@Nullable Calendar calendar, Duration misfireThreshold) {
    updateWithNewCalendar(calendar, misfireThreshold, Instants.now());
  }

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
      if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
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

    nextFireTime = getFireTimeAfter(Instant.ofEpochMilli(getStartTime().toEpochMilli() - 1000L));

    // Check calendar for date-time exclusion
    while (nextFireTime != null && calendar != null && !calendar.isTimeIncluded(nextFireTime)) {

      nextFireTime = getFireTimeAfter(nextFireTime);

      if (nextFireTime == null) break;

      // avoid infinite loop
      if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
        return null;
      }
    }

    return nextFireTime;
  }

  private LenientDateTime createCalendarTime(Instant dateTime) {
    return new LenientDateTime(dateTime, ZoneId.systemDefault());
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
    return nextFireTime;
  }

  /**
   * Returns the previous time at which the <code>DailyTimeIntervalTrigger</code> fired. If the
   * trigger has not yet fired, <code>null</code> will be returned.
   */
  @Override
  public @Nullable Instant getPreviousFireTime() {
    return previousFireTime;
  }

  /**
   * Set the next time at which the <code>DailyTimeIntervalTrigger</code> should fire.
   *
   * <p><b>This method should not be invoked by client code.</b>
   */
  public void setNextFireTime(@Nullable Instant nextFireTime) {
    this.nextFireTime = Instants.toMillis(nextFireTime);
  }

  /**
   * Set the previous time at which the <code>DailyTimeIntervalTrigger</code> fired.
   *
   * <p><b>This method should not be invoked by client code.</b>
   */
  public void setPreviousFireTime(@Nullable Instant previousFireTime) {
    this.previousFireTime = Instants.toMillis(previousFireTime);
  }

  /**
   * Returns the next time at which the <code>DailyTimeIntervalTrigger</code> will fire, after the
   * given time. If the trigger will not fire after the given time, <code>null</code> will be
   * returned.
   */
  @Override
  public @Nullable Instant getFireTimeAfter(@Nullable Instant afterTime) {
    // Check if trigger has completed or not.
    if (complete) {
      return null;
    }

    // Check repeatCount limit
    if (repeatCount != REPEAT_INDEFINITELY && timesTriggered > repeatCount) {
      return null;
    }

    // a. Increment afterTime by a second, so that we are comparing against a time after it!
    if (afterTime == null) {
      afterTime = Instant.ofEpochMilli(System.currentTimeMillis() + 1000L);
    } else {
      afterTime = Instant.ofEpochMilli(afterTime.toEpochMilli() + 1000L);
    }

    // make sure afterTime is at least startTime
    if (afterTime.isBefore(startTime)) afterTime = startTime;

    // b.Check to see if afterTime is after endTimeOfDay or not. If yes, then we need to advance to
    // next day as well.
    boolean afterTimePastEndTimeOfDay = false;
    if (endTimeOfDay != null) {
      afterTimePastEndTimeOfDay =
          afterTime.toEpochMilli() > endTimeOfDay.getTimeOfDayForDate(afterTime).toEpochMilli();
    }
    // c. now we need to move to the next valid day of week if either:
    // the given time is past the end time of day, or given time is not on a valid day of week
    Instant fireTime = advanceToNextDayOfWeekIfNecessary(afterTime, afterTimePastEndTimeOfDay);
    if (fireTime == null) return null;

    // d. Calculate and save fireTimeEndDate variable for later use
    Instant fireTimeEndDate;
    if (endTimeOfDay == null)
      fireTimeEndDate = new TimeOfDay(23, 59, 59).getTimeOfDayForDate(fireTime);
    else fireTimeEndDate = endTimeOfDay.getTimeOfDayForDate(fireTime);

    // e. Check fireTime against startTime or startTimeOfDay to see which go first.
    Instant fireTimeStartDate = getStartTimeOfDay().getTimeOfDayForDate(fireTime);
    if (fireTime.isBefore(fireTimeStartDate)) {
      return fireTimeStartDate;
    }

    // f. Continue to calculate the fireTime by incremental unit of intervals.
    // recall that if fireTime was less that fireTimeStartDate, we didn't get this far
    long fireMillis = fireTime.toEpochMilli();
    long startMillis = fireTimeStartDate.toEpochMilli();
    long secondsAfterStart = (fireMillis - startMillis) / 1000L;
    long repeatLong = getRepeatInterval();
    LenientDateTime sTime = createCalendarTime(fireTimeStartDate);
    IntervalUnit repeatUnit = getRepeatIntervalUnit();
    if (repeatUnit.equals(IntervalUnit.SECOND)) {
      long jumpCount = secondsAfterStart / repeatLong;
      if (secondsAfterStart % repeatLong != 0) jumpCount++;
      sTime.add(LenientDateTime.SECOND, getRepeatInterval() * (int) jumpCount);
      fireTime = sTime.toInstant();
    } else if (repeatUnit.equals(IntervalUnit.MINUTE)) {
      long jumpCount = secondsAfterStart / (repeatLong * 60L);
      if (secondsAfterStart % (repeatLong * 60L) != 0) jumpCount++;
      sTime.add(LenientDateTime.MINUTE, getRepeatInterval() * (int) jumpCount);
      fireTime = sTime.toInstant();
    } else if (repeatUnit.equals(IntervalUnit.HOUR)) {
      long jumpCount = secondsAfterStart / (repeatLong * 60L * 60L);
      if (secondsAfterStart % (repeatLong * 60L * 60L) != 0) jumpCount++;
      sTime.add(LenientDateTime.HOUR_OF_DAY, getRepeatInterval() * (int) jumpCount);
      fireTime = sTime.toInstant();
    }

    // g. Ensure this new fireTime is within the day, or else we need to advance to next day.
    if (fireTime.isAfter(fireTimeEndDate)) {
      fireTime = advanceToNextDayOfWeekIfNecessary(fireTime, isSameDay(fireTime, fireTimeEndDate));
      // make sure we hit the startTimeOfDay on the new day
      fireTime = getStartTimeOfDay().getTimeOfDayForDate(fireTime);
    }

    // i. Return calculated fireTime.
    return fireTime;
  }

  private boolean isSameDay(Instant d1, Instant d2) {

    LenientDateTime c1 = createCalendarTime(d1);
    LenientDateTime c2 = createCalendarTime(d2);

    return c1.get(LenientDateTime.YEAR) == c2.get(LenientDateTime.YEAR)
        && c1.get(LenientDateTime.DAY_OF_YEAR) == c2.get(LenientDateTime.DAY_OF_YEAR);
  }

  /**
   * Given fireTime time determine if it is on a valid day of week. If so, simply return it
   * unaltered, if not, advance to the next valid week day, and set the time of day to the start
   * time of day
   *
   * @param fireTime - given next fireTime.
   * @param forceToAdvanceNextDay - flag to whether to advance day without check existing week day.
   *     This scenario can happen when a caller determine fireTime has passed the endTimeOfDay that
   *     fireTime should move to next day anyway.
   * @return a next day fireTime.
   */
  private @Nullable Instant advanceToNextDayOfWeekIfNecessary(
      Instant fireTime, boolean forceToAdvanceNextDay) {
    // a. Advance or adjust to next dayOfWeek if need to first, starting next day with
    // startTimeOfDay.
    TimeOfDay sTimeOfDay = getStartTimeOfDay();
    Instant fireTimeStartDate = sTimeOfDay.getTimeOfDayForDate(fireTime);
    LenientDateTime fireTimeStartDateCal = createCalendarTime(fireTimeStartDate);
    int dayOfWeekOfFireTime = fireTimeStartDateCal.get(LenientDateTime.DAY_OF_WEEK);

    // b2. We need to advance to another day if isAfterTimePassEndTimeOfDay is true, or dayOfWeek is
    // not set.
    Set<Integer> daysOfWeekToFire = getDaysOfWeek();
    if (forceToAdvanceNextDay || !daysOfWeekToFire.contains(dayOfWeekOfFireTime)) {
      // Advance one day at a time until next available date.
      for (int i = 1; i <= 7; i++) {
        fireTimeStartDateCal.add(LenientDateTime.DATE, 1);
        dayOfWeekOfFireTime = fireTimeStartDateCal.get(LenientDateTime.DAY_OF_WEEK);
        if (daysOfWeekToFire.contains(dayOfWeekOfFireTime)) {
          fireTime = fireTimeStartDateCal.toInstant();
          break;
        }
      }
    }

    // Check fireTime not pass the endTime
    Instant eTime = getEndTime();
    if (eTime != null && fireTime.toEpochMilli() > eTime.toEpochMilli()) {
      return null;
    }

    return fireTime;
  }

  /**
   * Returns the final time at which the <code>DailyTimeIntervalTrigger</code> will fire, if there
   * is no end time set, null will be returned.
   *
   * <p>Note that the return time may be in the past.
   */
  @Override
  public @Nullable Instant getFinalFireTime() {
    if (complete || getEndTime() == null) {
      return null;
    }

    // We have an endTime, we still need to check to see if there is a endTimeOfDay if that's
    // applicable.
    Instant eTime = getEndTime();
    if (endTimeOfDay != null) {
      Instant endTimeOfDayDate = endTimeOfDay.getTimeOfDayForDate(eTime);
      if (eTime.toEpochMilli() < endTimeOfDayDate.toEpochMilli()) {
        eTime = endTimeOfDayDate;
      }
    }
    return eTime;
  }

  /** Determines whether or not the <code>DailyTimeIntervalTrigger</code> will occur again. */
  @Override
  public boolean mayFireAgain() {
    return (getNextFireTime() != null);
  }

  /**
   * Validates whether the properties of the <code>JobDetail</code> are valid for submission into a
   * <code>Scheduler</code>.
   *
   * @throws IllegalStateException if a required property (such as Name, Group, Class) is not set.
   */
  @Override
  public void validate() throws SchedulerException {
    super.validate();

    if (repeatIntervalUnit == null
        || !(repeatIntervalUnit.equals(IntervalUnit.SECOND)
            || repeatIntervalUnit.equals(IntervalUnit.MINUTE)
            || repeatIntervalUnit.equals(IntervalUnit.HOUR)))
      throw new SchedulerException("Invalid repeat IntervalUnit (must be SECOND, MINUTE or HOUR).");
    if (repeatInterval < 1) {
      throw new SchedulerException("Repeat Interval cannot be zero.");
    }

    // Ensure interval does not exceed 24 hours
    long secondsInHour = 24 * 60 * 60L;
    if (repeatIntervalUnit == IntervalUnit.SECOND && repeatInterval > secondsInHour) {
      throw new SchedulerException(
          "repeatInterval can not exceed 24 hours ("
              + secondsInHour
              + " seconds). Given "
              + repeatInterval);
    }
    if (repeatIntervalUnit == IntervalUnit.MINUTE && repeatInterval > secondsInHour / 60L) {
      throw new SchedulerException(
          "repeatInterval can not exceed 24 hours ("
              + secondsInHour / 60L
              + " minutes). Given "
              + repeatInterval);
    }
    if (repeatIntervalUnit == IntervalUnit.HOUR && repeatInterval > 24) {
      throw new SchedulerException(
          "repeatInterval can not exceed 24 hours. Given " + repeatInterval + " hours.");
    }

    // Ensure timeOfDay is in order.
    // NOTE: We allow startTimeOfDay to be set equal to endTimeOfDay so the repeatCount can be
    // set to 1.
    if (getEndTimeOfDay() != null
        && !getStartTimeOfDay().equals(getEndTimeOfDay())
        && !getStartTimeOfDay().before(getEndTimeOfDay())) {
      throw new SchedulerException(
          "StartTimeOfDay "
              + startTimeOfDay
              + " should not come after endTimeOfDay "
              + endTimeOfDay);
    }
  }

  /** {@inheritDoc} */
  public Set<Integer> getDaysOfWeek() {
    if (daysOfWeek == null) {
      daysOfWeek = DailyTimeIntervalScheduleBuilder.ALL_DAYS_OF_THE_WEEK;
    }
    return daysOfWeek;
  }

  /**
   * Set the days of the week the trigger fires on.
   *
   * @param daysOfWeek the days, {@link org.quartz.DateBuilder#SUNDAY} (1) to {@link
   *     org.quartz.DateBuilder#SATURDAY} (7)
   * @throws IllegalArgumentException if the set is {@code null} or empty
   */
  public void setDaysOfWeek(Set<Integer> daysOfWeek) {
    if (daysOfWeek == null || daysOfWeek.isEmpty())
      throw new IllegalArgumentException(
          "DaysOfWeek set must be a set that contains at least one day.");

    this.daysOfWeek = daysOfWeek;
  }

  /** {@inheritDoc} */
  public TimeOfDay getStartTimeOfDay() {
    if (startTimeOfDay == null) {
      startTimeOfDay = new TimeOfDay(0, 0, 0);
    }
    return startTimeOfDay;
  }

  /**
   * Set the time of day at which the trigger starts firing each day.
   *
   * @param startTimeOfDay the start time of day
   * @throws IllegalArgumentException if it is {@code null} or after the end time of day
   */
  public void setStartTimeOfDay(TimeOfDay startTimeOfDay) {
    if (startTimeOfDay == null) {
      throw new IllegalArgumentException("Start time of day cannot be null");
    }

    TimeOfDay eTime = getEndTimeOfDay();
    if (eTime != null && eTime.before(startTimeOfDay)) {
      throw new IllegalArgumentException("End time of day cannot be before start time of day");
    }

    this.startTimeOfDay = startTimeOfDay;
  }

  /** {@inheritDoc} */
  // null only for an incomplete trigger whose end time of day is not set yet, as in Quartz
  @SuppressWarnings("NullAway")
  public TimeOfDay getEndTimeOfDay() {
    return endTimeOfDay;
  }

  /**
   * Set the time of day after which the trigger stops firing each day.
   *
   * @param endTimeOfDay the end time of day
   * @throws IllegalArgumentException if it is {@code null} or before the start time of day
   */
  public void setEndTimeOfDay(TimeOfDay endTimeOfDay) {
    if (endTimeOfDay == null) throw new IllegalArgumentException("End time of day cannot be null");

    // the field, not getStartTimeOfDay(), which would also set the default start of 00:00
    TimeOfDay sTime = startTimeOfDay;
    if (sTime != null && endTimeOfDay.before(sTime)) {
      throw new IllegalArgumentException("End time of day cannot be before start time of day");
    }
    this.endTimeOfDay = endTimeOfDay;
  }

  /**
   * Get a {@link ScheduleBuilder} that is configured to produce a schedule identical to this
   * trigger's schedule.
   *
   * @see #getTriggerBuilder()
   */
  @Override
  public ScheduleBuilder<DailyTimeIntervalTrigger> getScheduleBuilder() {

    DailyTimeIntervalScheduleBuilder cb =
        DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
            .withInterval(getRepeatInterval(), getRepeatIntervalUnit())
            .onDaysOfTheWeek(getDaysOfWeek())
            .startingDailyAt(getStartTimeOfDay())
            .endingDailyAt(getEndTimeOfDay());

    switch (getMisfireInstruction()) {
      case MISFIRE_INSTRUCTION_DO_NOTHING:
        cb.withMisfireHandlingInstructionDoNothing();
        break;
      case MISFIRE_INSTRUCTION_FIRE_ONCE_NOW:
        cb.withMisfireHandlingInstructionFireAndProceed();
        break;
    }

    return cb;
  }

  /** This trigger has no additional properties besides what's defined in this class. */
  public boolean hasAdditionalProperties() {
    return false;
  }

  public int getRepeatCount() {
    return repeatCount;
  }

  /**
   * Set how many times the trigger repeats after its first firing.
   *
   * @param repeatCount the repeat count, or {@link #REPEAT_INDEFINITELY}
   * @throws IllegalArgumentException if it is negative and not {@link #REPEAT_INDEFINITELY}
   */
  public void setRepeatCount(int repeatCount) {
    if (repeatCount < 0 && repeatCount != REPEAT_INDEFINITELY) {
      throw new IllegalArgumentException(
          "Repeat count must be >= 0, use the " + "constant REPEAT_INDEFINITELY for infinite.");
    }

    this.repeatCount = repeatCount;
  }
}
