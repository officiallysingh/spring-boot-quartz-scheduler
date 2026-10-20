
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


import org.quartz.Calendar;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.ScheduleBuilder;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerUtils;
import org.quartz.utils.Instants;


/**
 * <p>
 * A concrete <code>{@link Trigger}</code> that is used to fire a <code>{@link org.quartz.JobDetail}</code>
 * at a given moment in time, and optionally repeated at a specified interval.
 * </p>
 * 
 * @see Trigger
 * @see CronTrigger
 * @see TriggerUtils
 * 
 * @author James House
 * @author contributions by Lieven Govaerts of Ebitec Nv, Belgium.
 */
public class SimpleTriggerImpl extends AbstractTrigger<SimpleTrigger> implements SimpleTrigger, CoreTrigger {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constants.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * Required for serialization support. Introduced in Quartz 1.6.1 to 
     * maintain compatibility after the introduction of hasAdditionalProperties
     * method. 
     * 
     * @see java.io.Serializable
     */
    private static final long serialVersionUID = -3735980074222850397L;

    private static final int YEAR_TO_GIVEUP_SCHEDULING_AT = Year.now().getValue() + 100;
    
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */
    
    private Instant startTime = null;

    private Instant endTime = null;

    private Instant nextFireTime = null;

    private Instant previousFireTime = null;

    private int repeatCount = 0;

    private Duration repeatInterval = Duration.ZERO;

    private int timesTriggered = 0;

    private final boolean complete = false;

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> with no settings.
     * </p>
     */
    public SimpleTriggerImpl() {
        super();
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur immediately, and
     * not repeat.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name) {
        this(name, (String)null);
    }
    
    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur immediately, and
     * not repeat.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, String group) {
        this(name, group, Instants.now(), null, 0, Duration.ZERO);
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur immediately, and
     * repeat at the given interval the given number of times.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, int repeatCount, Duration repeatInterval) {
        this(name, null, repeatCount, repeatInterval);
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur immediately, and
     * repeat at the given interval the given number of times.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, String group, int repeatCount,
            Duration repeatInterval) {
        this(name, group, Instants.now(), null, repeatCount, repeatInterval);
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur at the given time,
     * and not repeat.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, Instant startTime) {
        this(name, null, startTime);
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur at the given time,
     * and not repeat.
     * </p>
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, String group, Instant startTime) {
        this(name, group, startTime, null, 0, Duration.ZERO);
    }
    
    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur at the given time,
     * and repeat at the given interval the given number of times, or until
     * the given end time.
     * </p>
     * 
     * @param startTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to fire.
     * @param endTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to quit repeat firing.
     * @param repeatCount
     *          The number of times for the <code>Trigger</code> to repeat
     *          firing, use {@link #REPEAT_INDEFINITELY} for unlimited times.
     * @param repeatInterval
     *          The number of milliseconds to pause between the repeat firing.
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, Instant startTime,
            Instant endTime, int repeatCount, Duration repeatInterval) {
        this(name, null, startTime, endTime, repeatCount, repeatInterval);
    }
    
    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur at the given time,
     * and repeat at the given interval the given number of times, or until
     * the given end time.
     * </p>
     * 
     * @param startTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to fire.
     * @param endTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to quit repeat firing.
     * @param repeatCount
     *          The number of times for the <code>Trigger</code> to repeat
     *          firing, use {@link #REPEAT_INDEFINITELY} for unlimited times.
     * @param repeatInterval
     *          The number of milliseconds to pause between the repeat firing.
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, String group, Instant startTime,
            Instant endTime, int repeatCount, Duration repeatInterval) {
        super(name, group);

        setStartTime(startTime);
        setEndTime(endTime);
        setRepeatCount(repeatCount);
        setRepeatInterval(repeatInterval);
    }

    /**
     * <p>
     * Create a <code>SimpleTrigger</code> that will occur at the given time,
     * fire the identified <code>Job</code> and repeat at the given
     * interval the given number of times, or until the given end time.
     * </p>
     * 
     * @param startTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to fire.
     * @param endTime
     *          A <code>Date</code> set to the time for the <code>Trigger</code>
     *          to quit repeat firing.
     * @param repeatCount
     *          The number of times for the <code>Trigger</code> to repeat
     *          firing, use {@link #REPEAT_INDEFINITELY}for unlimited times.
     * @param repeatInterval
     *          The number of milliseconds to pause between the repeat firing.
     * 
     * @deprecated use a TriggerBuilder instead
     */
    @Deprecated
    public SimpleTriggerImpl(String name, String group, String jobName,
            String jobGroup, Instant startTime, Instant endTime, int repeatCount,
            Duration repeatInterval) {
        super(name, group, jobName, jobGroup);

        setStartTime(startTime);
        setEndTime(endTime);
        setRepeatCount(repeatCount);
        setRepeatInterval(repeatInterval);
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Get the time at which the <code>SimpleTrigger</code> should occur.
     * </p>
     */
    @Override
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * <p>
     * Set the time at which the <code>SimpleTrigger</code> should occur.
     * </p>
     * 
     * @exception IllegalArgumentException
     *              if startTime is <code>null</code>.
     */
    @Override
    public void setStartTime(Instant startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("Start time cannot be null");
        }

        Instant eTime = getEndTime();
        if (eTime != null && eTime.isBefore(startTime)) {
            throw new IllegalArgumentException(
                "End time cannot be before start time");    
        }

        this.startTime = Instants.toMillis(startTime);
    }

    /**
     * <p>
     * Get the time at which the <code>SimpleTrigger</code> should quit
     * repeating - even if repeatCount isn't yet satisfied.
     * </p>
     * 
     * @see #getFinalFireTime()
     */
    @Override
    public Instant getEndTime() {
        return endTime;
    }

    /**
     * <p>
     * Set the time at which the <code>SimpleTrigger</code> should quit
     * repeating (and be automatically deleted).
     * </p>
     * 
     * @exception IllegalArgumentException
     *              if endTime is before start time.
     */
    @Override
    public void setEndTime(Instant endTime) {
        Instant sTime = getStartTime();
        if (sTime != null && endTime != null && sTime.isAfter(endTime)) {
            throw new IllegalArgumentException(
                    "End time cannot be before start time");
        }

        this.endTime = Instants.toMillis(endTime);
    }

    /* (non-Javadoc)
     * @see org.quartz.SimpleTriggerI#getRepeatCount()
     */
    public int getRepeatCount() {
        return repeatCount;
    }

    /**
     * <p>
     * Set the number of time the <code>SimpleTrigger</code> should
     * repeat, after which it will be automatically deleted.
     * </p>
     * 
     * @see #REPEAT_INDEFINITELY
     * @exception IllegalArgumentException
     *              if repeatCount is &lt; 0
     */
    public void setRepeatCount(int repeatCount) {
        if (repeatCount < 0 && repeatCount != REPEAT_INDEFINITELY) {
            throw new IllegalArgumentException(
                    "Repeat count must be >= 0, use the "
                            + "constant REPEAT_INDEFINITELY for infinite.");
        }

        this.repeatCount = repeatCount;
    }

    /* (non-Javadoc)
     * @see org.quartz.SimpleTriggerI#getRepeatInterval()
     */
    public Duration getRepeatInterval() {
        return repeatInterval;
    }

    /**
     * <p>
     * Set the time interval (in milliseconds) at which the <code>SimpleTrigger</code>
     * should repeat.
     * </p>
     * 
     * @exception IllegalArgumentException
     *              if repeatInterval is &lt; 0
     */
    public void setRepeatInterval(Duration repeatInterval) {
        if (repeatInterval == null || repeatInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "Repeat interval must be >= 0");
        }

        this.repeatInterval = repeatInterval;
    }

    /**
     * <p>
     * Get the number of times the <code>SimpleTrigger</code> has already
     * fired.
     * </p>
     */
    public int getTimesTriggered() {
        return timesTriggered;
    }

    /**
     * <p>
     * Set the number of times the <code>SimpleTrigger</code> has already
     * fired.
     * </p>
     */
    public void setTimesTriggered(int timesTriggered) {
        this.timesTriggered = timesTriggered;
    }

    @Override
    protected boolean validateMisfireInstruction(int misfireInstruction) {
        if (misfireInstruction < MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) {
            return false;
        }

        return misfireInstruction <= MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT;
    }

    /**
     * <p>
     * Updates the <code>SimpleTrigger</code>'s state based on the
     * MISFIRE_INSTRUCTION_XXX that was selected when the <code>SimpleTrigger</code>
     * was created.
     * </p>
     * 
     * <p>
     * If the misfire instruction is set to MISFIRE_INSTRUCTION_SMART_POLICY,
     * then the following scheme will be used: </p>
     * <ul>
     * <li>If the Repeat Count is <code>0</code>, then the instruction will
     * be interpreted as <code>MISFIRE_INSTRUCTION_FIRE_NOW</code>.</li>
     * <li>If the Repeat Count is <code>REPEAT_INDEFINITELY</code>, then
     * the instruction will be interpreted as <code>MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT</code>.
     * <b>WARNING:</b> using MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT 
     * with a trigger that has a non-null end-time may cause the trigger to 
     * never fire again if the end-time arrived during the misfire time span. 
     * </li>
     * <li>If the Repeat Count is <code>&gt; 0</code>, then the instruction
     * will be interpreted as <code>MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT</code>.
     * </li>
     * </ul>
     */
    @Override
    public void updateAfterMisfire(Calendar cal) {
        updateAfterMisfire(cal, Instants.now());
    }

    @Override
    public void updateAfterMisfire(Calendar cal, Instant now) {
        int instr = getMisfireInstruction();
        
        if(instr == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY)
            return;
        
        if (instr == Trigger.MISFIRE_INSTRUCTION_SMART_POLICY) {
            if (getRepeatCount() == 0) {
                instr = MISFIRE_INSTRUCTION_FIRE_NOW;
            } else if (getRepeatCount() == REPEAT_INDEFINITELY) {
                instr = MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT;
            } else {
                // if (getRepeatCount() > 0)
                instr = MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT;
            }
        } else if (instr == MISFIRE_INSTRUCTION_FIRE_NOW && getRepeatCount() != 0) {
            instr = MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT;
        }

        if (instr == MISFIRE_INSTRUCTION_FIRE_NOW) {
            setNextFireTime(now);
        } else if (instr == MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT) {
            Instant newFireTime = getFireTimeAfter(now);
            while (newFireTime != null && cal != null
                    && !cal.isTimeIncluded(newFireTime)) {
                newFireTime = getFireTimeAfter(newFireTime);

                if(newFireTime == null)
                    break;
                
                //avoid infinite loop
                if (newFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
                    newFireTime = null;
                }
            }
            setNextFireTime(newFireTime);
        } else if (instr == MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT) {
            Instant newFireTime = getFireTimeAfter(now);
            while (newFireTime != null && cal != null
                    && !cal.isTimeIncluded(newFireTime)) {
                newFireTime = getFireTimeAfter(newFireTime);

                if(newFireTime == null)
                    break;
                
                //avoid infinite loop
                if (newFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
                    newFireTime = null;
                }
            }
            if (newFireTime != null) {
                int timesMissed = computeNumTimesFiredBetween(nextFireTime,
                        newFireTime);
                setTimesTriggered(getTimesTriggered() + timesMissed);
            }

            setNextFireTime(newFireTime);
        } else if (instr == MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT) {
            Instant newFireTime = now;
            if (repeatCount != 0 && repeatCount != REPEAT_INDEFINITELY) {
                setRepeatCount(getRepeatCount() - getTimesTriggered());
                setTimesTriggered(0);
            }
            
            if (getEndTime() != null && getEndTime().isBefore(newFireTime)) {
                setNextFireTime(null); // We are past the end time
            } else {
                setStartTime(newFireTime);
                setNextFireTime(newFireTime);
            } 
        } else if (instr == MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT) {
            Instant newFireTime = now;

            int timesMissed = computeNumTimesFiredBetween(nextFireTime,
                    newFireTime);

            if (repeatCount != 0 && repeatCount != REPEAT_INDEFINITELY) {
                int remainingCount = getRepeatCount()
                        - (getTimesTriggered() + timesMissed);
                if (remainingCount <= 0) { 
                    remainingCount = 0;
                }
                setRepeatCount(remainingCount);
                setTimesTriggered(0);
            }

            if (getEndTime() != null && getEndTime().isBefore(newFireTime)) {
                setNextFireTime(null); // We are past the end time
            } else {
                setStartTime(newFireTime);
                setNextFireTime(newFireTime);
            } 
        }

    }

    /**
     * <p>
     * Called when the <code>{@link Scheduler}</code> has decided to 'fire'
     * the trigger (execute the associated <code>Job</code>), in order to
     * give the <code>Trigger</code> a chance to update itself for its next
     * triggering (if any).
     * </p>
     * 
     * @see #executionComplete(JobExecutionContext, JobExecutionException)
     */
    @Override
    public void triggered(Calendar calendar) {
        timesTriggered++;
        previousFireTime = nextFireTime;
        nextFireTime = getFireTimeAfter(nextFireTime);

        while (nextFireTime != null && calendar != null
                && !calendar.isTimeIncluded(nextFireTime)) {
            
            nextFireTime = getFireTimeAfter(nextFireTime);

            if(nextFireTime == null)
                break;
            
            //avoid infinite loop
            if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
                nextFireTime = null;
            }
        }
    }

    /**
     * @see org.quartz.impl.triggers.AbstractTrigger#updateWithNewCalendar(org.quartz.Calendar, java.time.Duration)
     */
    @Override
    public void updateWithNewCalendar(Calendar calendar, Duration misfireThreshold)
    {
        updateWithNewCalendar(calendar, misfireThreshold, Instants.now());
    }

    @Override
    public void updateWithNewCalendar(Calendar calendar, Duration misfireThreshold, Instant now)
    {
        nextFireTime = getFireTimeAfter(previousFireTime);

        if (nextFireTime == null || calendar == null) {
            return;
        }
        
        while (nextFireTime != null && !calendar.isTimeIncluded(nextFireTime)) {

            nextFireTime = getFireTimeAfter(nextFireTime);

            if(nextFireTime == null)
                break;
            
            //avoid infinite loop
            if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
                nextFireTime = null;
            }

            if(nextFireTime != null && nextFireTime.isBefore(now)) {
                long diff = now.toEpochMilli() - nextFireTime.toEpochMilli();
                if(diff >= misfireThreshold.toMillis()) {
                    nextFireTime = getFireTimeAfter(nextFireTime);
                }
            }
        }
    }

    /**
     * <p>
     * Called by the scheduler at the time a <code>Trigger</code> is first
     * added to the scheduler, in order to have the <code>Trigger</code>
     * compute its first fire time, based on any associated calendar.
     * </p>
     * 
     * <p>
     * After this method has been called, <code>getNextFireTime()</code>
     * should return a valid answer.
     * </p>
     * 
     * @return the first time at which the <code>Trigger</code> will be fired
     *         by the scheduler, which is also the same value <code>getNextFireTime()</code>
     *         will return (until after the first firing of the <code>Trigger</code>).
     */
    @Override
    public Instant computeFirstFireTime(Calendar calendar) {
        nextFireTime = getStartTime();

        while (nextFireTime != null && calendar != null
                && !calendar.isTimeIncluded(nextFireTime)) {
            nextFireTime = getFireTimeAfter(nextFireTime);
            
            if(nextFireTime == null)
                break;
            
            //avoid infinite loop
            if (nextFireTime.atZone(ZoneId.systemDefault()).getYear() > YEAR_TO_GIVEUP_SCHEDULING_AT) {
                return null;
            }
        }
        
        return nextFireTime;
    }

    /**
     * <p>
     * Returns the next time at which the <code>Trigger</code> is scheduled to fire. If
     * the trigger will not fire again, <code>null</code> will be returned.  Note that
     * the time returned can possibly be in the past, if the time that was computed
     * for the trigger to next fire has already arrived, but the scheduler has not yet
     * been able to fire the trigger (which would likely be due to lack of resources
     * e.g. threads).
     * </p>
     *
     * <p>The value returned is not guaranteed to be valid until after the <code>Trigger</code>
     * has been added to the scheduler.
     * </p>
     *
     * @see TriggerUtils#computeFireTimesBetween(org.quartz.spi.OperableTrigger, org.quartz.Calendar, java.time.Instant, java.time.Instant)
     */
    @Override
    public Instant getNextFireTime() {
        return nextFireTime;
    }

    /**
     * <p>
     * Returns the previous time at which the <code>SimpleTrigger</code> 
     * fired. If the trigger has not yet fired, <code>null</code> will be
     * returned.
     */
    @Override
    public Instant getPreviousFireTime() {
        return previousFireTime;
    }

    /**
     * <p>
     * Set the next time at which the <code>SimpleTrigger</code> should fire.
     * </p>
     * 
     * <p>
     * <b>This method should not be invoked by client code.</b>
     * </p>
     */
    public void setNextFireTime(Instant nextFireTime) {
        this.nextFireTime = Instants.toMillis(nextFireTime);
    }

    /**
     * <p>
     * Set the previous time at which the <code>SimpleTrigger</code> fired.
     * </p>
     * 
     * <p>
     * <b>This method should not be invoked by client code.</b>
     * </p>
     */
    public void setPreviousFireTime(Instant previousFireTime) {
        this.previousFireTime = Instants.toMillis(previousFireTime);
    }

    /**
     * <p>
     * Returns the next time at which the <code>SimpleTrigger</code> will
     * fire, after the given time. If the trigger will not fire after the given
     * time, <code>null</code> will be returned.
     * </p>
     */
    @Override
    public Instant getFireTimeAfter(Instant afterTime) {
        if (complete) {
            return null;
        }

        if ((timesTriggered > repeatCount)
                && (repeatCount != REPEAT_INDEFINITELY)) {
            return null;
        }

        if (afterTime == null) {
            afterTime = Instants.now();
        }

        if (repeatCount == 0 && afterTime.compareTo(getStartTime()) >= 0) {
            return null;
        }

        long startMillis = getStartTime().toEpochMilli();
        long afterMillis = afterTime.toEpochMilli();
        long endMillis = (getEndTime() == null) ? Long.MAX_VALUE : getEndTime()
                .toEpochMilli();

        if (endMillis <= afterMillis) {
            return null;
        }

        if (afterMillis < startMillis) {
            return Instant.ofEpochMilli(startMillis);
        }

        long numberOfTimesExecuted = ((afterMillis - startMillis) / repeatInterval.toMillis()) + 1;

        if ((numberOfTimesExecuted > repeatCount) && 
            (repeatCount != REPEAT_INDEFINITELY)) {
            return null;
        }

        Instant time = Instant.ofEpochMilli(startMillis + (numberOfTimesExecuted * repeatInterval.toMillis()));

        if (endMillis <= time.toEpochMilli()) {
            return null;
        }

        return time;
    }

    /**
     * <p>
     * Returns the last time at which the <code>SimpleTrigger</code> will
     * fire, before the given time. If the trigger will not fire before the
     * given time, <code>null</code> will be returned.
     * </p>
     */
    public Instant getFireTimeBefore(Instant end) {
        if (end.toEpochMilli() < getStartTime().toEpochMilli()) {
            return null;
        }

        int numFires = computeNumTimesFiredBetween(getStartTime(), end);

        return Instant.ofEpochMilli(getStartTime().toEpochMilli() + (numFires * repeatInterval.toMillis()));
    }

    public int computeNumTimesFiredBetween(Instant start, Instant end) {

        if(repeatInterval.toMillis() < 1) {
            return 0;
        }
        
        long time = end.toEpochMilli() - start.toEpochMilli();

        return (int) (time / repeatInterval.toMillis());
    }

    /**
     * <p>
     * Returns the final time at which the <code>SimpleTrigger</code> will
     * fire, if repeatCount is REPEAT_INDEFINITELY, null will be returned.
     * </p>
     * 
     * <p>
     * Note that the return time may be in the past.
     * </p>
     */
    @Override
    public Instant getFinalFireTime() {
        if (repeatCount == 0) {
            return startTime;
        }

        if (repeatCount == REPEAT_INDEFINITELY) {
            return (getEndTime() == null) ? null : getFireTimeBefore(getEndTime()); 
        }

        long lastTrigger = startTime.toEpochMilli() + (repeatCount * repeatInterval.toMillis());

        if ((getEndTime() == null) || (lastTrigger < getEndTime().toEpochMilli())) { 
            return Instant.ofEpochMilli(lastTrigger);
        } else {
            return getFireTimeBefore(getEndTime());
        }
    }

    /**
     * <p>
     * Determines whether or not the <code>SimpleTrigger</code> will occur
     * again.
     * </p>
     */
    @Override
    public boolean mayFireAgain() {
        return (getNextFireTime() != null);
    }

    /**
     * <p>
     * Validates whether the properties of the <code>JobDetail</code> are
     * valid for submission into a <code>Scheduler</code>.
     * 
     * @throws IllegalStateException
     *           if a required property (such as Name, Group, Class) is not
     *           set.
     */
    @Override
    public void validate() throws SchedulerException {
        super.validate();

        if (repeatCount != 0 && repeatInterval.toMillis() < 1) {
            throw new SchedulerException("Repeat Interval cannot be zero.");
        }
    }

    /**
     * Used by extensions of SimpleTrigger to imply that there are additional 
     * properties, specifically so that extensions can choose whether to be 
     * stored as a serialized blob, or as a flattened SimpleTrigger table. 
     */
    public boolean hasAdditionalProperties() {
        return false;
    }

    /**
     * Get a {@link ScheduleBuilder} that is configured to produce a 
     * schedule identical to this trigger's schedule.
     * 
     * @see #getTriggerBuilder()
     */
    @Override
    public ScheduleBuilder<SimpleTrigger> getScheduleBuilder() {
        
        SimpleScheduleBuilder sb = SimpleScheduleBuilder.simpleSchedule()
        .withInterval(getRepeatInterval())
        .withRepeatCount(getRepeatCount());
        
        switch(getMisfireInstruction()) {
            case MISFIRE_INSTRUCTION_FIRE_NOW : sb.withMisfireHandlingInstructionFireNow();
            break;
            case MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT : sb.withMisfireHandlingInstructionNextWithExistingCount();
            break;
            case MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT : sb.withMisfireHandlingInstructionNextWithRemainingCount();
            break;
            case MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT : sb.withMisfireHandlingInstructionNowWithExistingCount();
            break;
            case MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT : sb.withMisfireHandlingInstructionNowWithRemainingCount();
            break;
        }
        
        return sb;
    }

}
