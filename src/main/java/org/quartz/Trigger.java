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

package org.quartz;

import java.io.Serializable;
import java.time.Instant;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;

/**
 * The base interface with properties common to all <code>Trigger</code>s - use {@link
 * TriggerBuilder} to instantiate an actual Trigger.
 *
 * <p><code>Triggers</code>s have a {@link TriggerKey} associated with them, which should uniquely
 * identify them within a single <code>{@link Scheduler}</code>.
 *
 * <p><code>Trigger</code>s are the 'mechanism' by which <code>Job</code>s are scheduled. Many
 * <code>Trigger</code>s can point to the same <code>Job</code>, but a single <code>Trigger</code>
 * can only point to one <code>Job</code>.
 *
 * <p>Triggers can 'send' parameters/data to <code>Job</code>s by placing contents into the <code>
 * JobDataMap</code> on the <code>Trigger</code>.
 *
 * @see TriggerBuilder
 * @see JobDataMap
 * @see JobExecutionContext
 * @see TriggerUtils
 * @see SimpleTrigger
 * @see CronTrigger
 * @see CalendarIntervalTrigger
 * @author James House
 */
public interface Trigger extends Serializable, Cloneable, Comparable<Trigger> {

  long serialVersionUID = -3904243490805975570L;

  /** The state of a trigger in the job store, as reported by {@link Scheduler#getTriggerState}. */
  enum TriggerState {
    /** There is no such trigger. */
    NONE,
    /** The trigger waits for its next fire time, or is being fired. */
    NORMAL,
    /** The trigger is paused: it does not fire until resumed. */
    PAUSED,
    /** The trigger will not fire again. */
    COMPLETE,
    /**
     * The trigger is in error, for example because its job class could not be loaded; it does not
     * fire until {@link Scheduler#resetTriggerFromErrorState reset}.
     */
    ERROR,
    /**
     * The trigger's job is running and does not allow concurrent execution ({@link
     * DisallowConcurrentExecution}): it fires once that execution ends.
     */
    BLOCKED
  }

  /**
   * What the scheduler does with a trigger once its job has executed, as decided by {@link
   * org.quartz.spi.OperableTrigger#executionComplete}.
   */
  enum CompletedExecutionInstruction {
    /** No further instruction. */
    NOOP,
    /**
     * Execute the job again immediately. Unless the job is recovering, the execution context is
     * re-used, so the job can see what its last execution put in it.
     */
    RE_EXECUTE_JOB,
    /** Put this trigger in the {@link TriggerState#COMPLETE} state. */
    SET_TRIGGER_COMPLETE,
    /** Delete this trigger. */
    DELETE_TRIGGER,
    /** Put every trigger of the same job in the {@link TriggerState#COMPLETE} state. */
    SET_ALL_JOB_TRIGGERS_COMPLETE,
    /** Put this trigger in the {@link TriggerState#ERROR} state. */
    SET_TRIGGER_ERROR,
    /** Put every trigger of the same job in the {@link TriggerState#ERROR} state. */
    SET_ALL_JOB_TRIGGERS_ERROR
  }

  /**
   * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire situation, the <code>
   * updateAfterMisfire()</code> method will be called on the <code>Trigger</code> to determine the
   * mis-fire instruction, which logic will be trigger-implementation-dependent.
   *
   * <p>In order to see if this instruction fits your needs, you should look at the documentation
   * for the <code>updateAfterMisfire()</code> method on the particular <code>Trigger</code>
   * implementation you are using.
   *
   * @see org.quartz.impl.triggers.SimpleTriggerImpl#updateAfterMisfire(Calendar)
   * @see org.quartz.impl.triggers.CronTriggerImpl#updateAfterMisfire(Calendar)
   * @see org.quartz.impl.triggers.DailyTimeIntervalTriggerImpl#updateAfterMisfire(Calendar)
   * @see org.quartz.impl.triggers.CalendarIntervalTriggerImpl#updateAfterMisfire(Calendar)
   */
  int MISFIRE_INSTRUCTION_SMART_POLICY = 0;

  /**
   * Instructs the <code>{@link Scheduler}</code> that the <code>Trigger</code> will never be
   * evaluated for a misfire situation, and that the scheduler will simply try to fire it as soon as
   * it can, and then update the Trigger as if it had fired at the proper time.
   *
   * <p>NOTE: if a trigger uses this instruction, and it has missed several of its scheduled
   * firings, then several rapid firings may occur as the trigger attempt to catch back up to where
   * it would have been. For example, a SimpleTrigger that fires every 15 seconds which has misfired
   * for 5 minutes will fire 20 times once it gets the chance to fire.
   */
  int MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY = -1;

  /** The default value for priority. */
  int DEFAULT_PRIORITY = 5;

  /**
   * The key identifying this trigger: its name and group.
   *
   * @return the trigger's key
   */
  TriggerKey getKey();

  /**
   * The key of the job this trigger fires.
   *
   * @return the job's key
   */
  JobKey getJobKey();

  /**
   * Return the description given to the <code>Trigger</code> instance by its creator (if any).
   *
   * @return null if no description was set.
   */
  @Nullable String getDescription();

  /**
   * Get the name of the <code>{@link Calendar}</code> associated with this Trigger.
   *
   * @return <code>null</code> if there is no associated Calendar.
   */
  @Nullable String getCalendarName();

  /**
   * Get the <code>JobDataMap</code> that is associated with the <code>Trigger</code>.
   *
   * <p>Changes made to this map during job execution are not re-persisted, and in fact typically
   * result in an <code>IllegalStateException</code>.
   */
  JobDataMap getJobDataMap();

  /**
   * The priority of a <code>Trigger</code> acts as a tiebreaker such that if two <code>Trigger
   * </code>s have the same scheduled fire time, then the one with the higher priority will get
   * first access to a worker thread.
   *
   * <p>If not explicitly set, the default value is <code>5</code>.
   *
   * @see #DEFAULT_PRIORITY
   */
  int getPriority();

  /**
   * Used by the <code>{@link Scheduler}</code> to determine whether or not it is possible for this
   * <code>Trigger</code> to fire again.
   *
   * <p>If the returned value is <code>false</code> then the <code>Scheduler</code> may remove the
   * <code>Trigger</code> from the <code>{@link org.quartz.spi.JobStore}</code>.
   */
  boolean mayFireAgain();

  /** Get the time at which the <code>Trigger</code> should occur. */
  Instant getStartTime();

  /**
   * Get the time at which the <code>Trigger</code> should quit repeating - regardless of any
   * remaining repeats (based on the trigger's particular repeat settings).
   *
   * @see #getFinalFireTime()
   */
  @Nullable Instant getEndTime();

  /**
   * Returns the next time at which the <code>Trigger</code> is scheduled to fire. If the trigger
   * will not fire again, <code>null</code> will be returned. Note that the time returned can
   * possibly be in the past, if the time that was computed for the trigger to next fire has already
   * arrived, but the scheduler has not yet been able to fire the trigger (which would likely be due
   * to lack of resources e.g. threads).
   *
   * <p>The value returned is not guaranteed to be valid until after the <code>Trigger</code> has
   * been added to the scheduler.
   *
   * @see TriggerUtils#computeFireTimesBetween(org.quartz.spi.OperableTrigger, Calendar,
   *     java.time.Instant, java.time.Instant)
   */
  @Nullable Instant getNextFireTime();

  /**
   * Returns the previous time at which the <code>Trigger</code> fired. If the trigger has not yet
   * fired, <code>null</code> will be returned.
   */
  @Nullable Instant getPreviousFireTime();

  /**
   * Returns the next time at which the <code>Trigger</code> will fire, after the given time. If the
   * trigger will not fire after the given time, <code>null</code> will be returned.
   */
  @Nullable Instant getFireTimeAfter(@Nullable Instant afterTime);

  /**
   * Returns the last time at which the <code>Trigger</code> will fire, if the Trigger will repeat
   * indefinitely, null will be returned.
   *
   * <p>Note that the return time *may* be in the past.
   */
  @Nullable Instant getFinalFireTime();

  /**
   * Get the instruction the <code>Scheduler</code> should be given for handling misfire situations
   * for this <code>Trigger</code>- the concrete <code>Trigger</code> type that you are using will
   * have defined a set of additional <code>MISFIRE_INSTRUCTION_XXX</code> constants that may be set
   * as this property's value.
   *
   * <p>If not explicitly set, the default value is <code>MISFIRE_INSTRUCTION_SMART_POLICY</code>.
   *
   * @see #MISFIRE_INSTRUCTION_SMART_POLICY
   * @see SimpleTrigger
   * @see CronTrigger
   */
  int getMisfireInstruction();

  /**
   * Get a {@link TriggerBuilder} that is configured to produce a <code>Trigger</code> identical to
   * this one.
   *
   * @see #getScheduleBuilder()
   */
  TriggerBuilder<? extends Trigger> getTriggerBuilder();

  /**
   * Get a {@link ScheduleBuilder} that is configured to produce a schedule identical to this
   * trigger's schedule.
   *
   * @see #getTriggerBuilder()
   */
  ScheduleBuilder<? extends Trigger> getScheduleBuilder();

  /**
   * Trigger equality is based upon the equality of the TriggerKey.
   *
   * @return true if the key of this Trigger equals that of the given Trigger.
   */
  boolean equals(@Nullable Object other);

  /**
   * Compares this trigger with another by their keys, sorting them in the natural (alphabetical)
   * order of their keys. To order by next fire time, use {@link TriggerTimeComparator}.
   *
   * @param other the trigger to compare with
   * @return negative, zero or positive as this trigger's key sorts before, equal to or after the
   *     other's
   */
  int compareTo(Trigger other);

  /**
   * A Comparator that compares trigger's next fire times, or in other words, sorts them according
   * to earliest next fire time. If the fire times are the same, then the triggers are sorted
   * according to priority (highest value first), if the priorities are the same, then they are
   * sorted by key.
   */
  class TriggerTimeComparator implements Comparator<Trigger>, Serializable {

    private static final long serialVersionUID = -3904243490805975570L;

    /**
     * Compares two triggers by their parts: earliest next fire time first (a trigger without one
     * sorts last), then highest priority, then key.
     *
     * @param nextFireTime1 the first trigger's next fire time, or {@code null}
     * @param priority1 the first trigger's priority
     * @param key1 the first trigger's key
     * @param nextFireTime2 the second trigger's next fire time, or {@code null}
     * @param priority2 the second trigger's priority
     * @param key2 the second trigger's key
     * @return negative, zero or positive as the first trigger sorts before, with or after the
     *     second
     */
    public static int compare(
        @Nullable Instant nextFireTime1,
        int priority1,
        TriggerKey key1,
        @Nullable Instant nextFireTime2,
        int priority2,
        TriggerKey key2) {
      if (nextFireTime1 != null || nextFireTime2 != null) {
        if (nextFireTime1 == null) {
          return 1;
        }

        if (nextFireTime2 == null) {
          return -1;
        }

        if (nextFireTime1.isBefore(nextFireTime2)) {
          return -1;
        }

        if (nextFireTime1.isAfter(nextFireTime2)) {
          return 1;
        }
      }

      int comp = priority2 - priority1;
      if (comp != 0) {
        return comp;
      }

      return key1.compareTo(key2);
    }

    public int compare(Trigger t1, Trigger t2) {
      return compare(
          t1.getNextFireTime(),
          t1.getPriority(),
          t1.getKey(),
          t2.getNextFireTime(),
          t2.getPriority(),
          t2.getKey());
    }
  }
}
