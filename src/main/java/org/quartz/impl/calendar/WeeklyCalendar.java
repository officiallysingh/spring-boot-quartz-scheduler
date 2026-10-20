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

package org.quartz.impl.calendar;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;

import org.quartz.Calendar;
import org.quartz.DateBuilder;
import org.quartz.utils.LenientDateTime;

/**
 * <p>
 * This implementation of the Calendar excludes a set of days of the week. You
 * may use it to exclude weekends for example. But you may define any day of
 * the week.  By default it excludes SATURDAY and SUNDAY.
 * </p>
 *
 * @see org.quartz.Calendar
 * @see org.quartz.impl.calendar.BaseCalendar
 *
 * @author Juergen Donnerstag
 */
public class WeeklyCalendar extends BaseCalendar implements Calendar,
        Serializable {
    private static final long serialVersionUID = -6809298821229007586L;

    // An array to store the week days which are to be excluded.
    // DateBuilder.MONDAY etc. are used as index.
    private boolean[] excludeDays = new boolean[8];

    // Will be set to true, if all week days are excluded
    private boolean excludeAll = false;

    public WeeklyCalendar() {
        this(null, null);
    }

    public WeeklyCalendar(Calendar baseCalendar) {
        this(baseCalendar, null);
    }

    public WeeklyCalendar(ZoneId timeZone) {
        super(null, timeZone);
    }

    public WeeklyCalendar(Calendar baseCalendar, ZoneId timeZone) {
        super(baseCalendar, timeZone);

        excludeDays[DateBuilder.SUNDAY] = true;
        excludeDays[DateBuilder.SATURDAY] = true;
        excludeAll = areAllDaysExcluded();
    }

    @Override
    public Object clone() {
        WeeklyCalendar clone = (WeeklyCalendar) super.clone();
        clone.excludeDays = excludeDays.clone();
        return clone;
    }

    /**
     * <p>
     * Get the array with the week days
     * </p>
     */
    public boolean[] getDaysExcluded() {
        return excludeDays;
    }

    /**
     * <p>
     * Return true, if wday (see Calendar.get()) is defined to be excluded. E. g.
     * saturday and sunday.
     * </p>
     */
    public boolean isDayExcluded(int wday) {
        return excludeDays[wday];
    }

    /**
     * <p>
     * Redefine the array of days excluded. The array must of size greater or
     * equal 8. DateBuilder's constants like MONDAY should be used as
     * index. A value of true is regarded as: exclude it.
     * </p>
     */
    public void setDaysExcluded(boolean[] weekDays) {
        if (weekDays == null) {
            return;
        }

        excludeDays = weekDays;
        excludeAll = areAllDaysExcluded();
    }

    /**
     * <p>
     * Redefine a certain day of the week to be excluded (true) or included
     * (false). Use DateBuilder's constants like MONDAY to determine the
     * wday.
     * </p>
     */
    public void setDayExcluded(int wday, boolean exclude) {
        excludeDays[wday] = exclude;
        excludeAll = areAllDaysExcluded();
    }

    /**
     * <p>
     * Check if all week days are excluded. That is no day is included.
     * </p>
     *
     * @return boolean
     */
    public boolean areAllDaysExcluded() {
        return
            isDayExcluded(DateBuilder.SUNDAY) &&
            isDayExcluded(DateBuilder.MONDAY) &&
            isDayExcluded(DateBuilder.TUESDAY) &&
            isDayExcluded(DateBuilder.WEDNESDAY) &&
            isDayExcluded(DateBuilder.THURSDAY) &&
            isDayExcluded(DateBuilder.FRIDAY) &&
            isDayExcluded(DateBuilder.SATURDAY);
    }

    /**
     * <p>
     * Determine whether the given time (in milliseconds) is 'included' by the
     * Calendar.
     * </p>
     *
     * <p>
     * Note that this Calendar is only has full-day precision.
     * </p>
     */
    @Override
    public boolean isTimeIncluded(Instant timeStamp) {
        if (excludeAll) {
            return false;
        }

        // Test the base calendar first. Only if the base calendar not already
        // excludes the time/date, continue evaluating this calendar instance.
        if (!super.isTimeIncluded(timeStamp)) { return false; }

        LenientDateTime cl = createDateTime(timeStamp);
        int wday = cl.get(LenientDateTime.DAY_OF_WEEK);

        return !(isDayExcluded(wday));
    }

    /**
     * <p>
     * Determine the next time (in milliseconds) that is 'included' by the
     * Calendar after the given time. Return the original value if timeStamp is
     * included. Return 0 if all days are excluded.
     * </p>
     *
     * <p>
     * Note that this Calendar is only has full-day precision.
     * </p>
     */
    @Override
    public Instant getNextIncludedTime(Instant timeStamp) {
        if (excludeAll) {
            return Instant.EPOCH;
        }

        // Call base calendar implementation first
        Instant baseTime = super.getNextIncludedTime(timeStamp);
        if (baseTime.isAfter(Instant.EPOCH) && baseTime.isAfter(timeStamp)) {
            timeStamp = baseTime;
        }

        // Get timestamp for 00:00:00
        LenientDateTime cl = getStartOfDay(timeStamp);
        int wday = cl.get(LenientDateTime.DAY_OF_WEEK);

        if (!isDayExcluded(wday)) {
            return timeStamp; // return the original value
        }

        while (isDayExcluded(wday)) {
            cl.add(LenientDateTime.DAY_OF_MONTH, 1);
            wday = cl.get(LenientDateTime.DAY_OF_WEEK);
        }

        return cl.toInstant();
    }
}
