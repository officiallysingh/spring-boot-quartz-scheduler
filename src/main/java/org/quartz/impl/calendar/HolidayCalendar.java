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
import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.utils.LenientDateTime;

/**
 * This implementation of the Calendar stores a list of holidays (full days that are excluded from
 * scheduling).
 *
 * <p>The implementation DOES take the year into consideration, so if you want to exclude July 4th
 * for the next 10 years, you need to add 10 entries to the exclude list.
 *
 * @author Sharada Jambula
 * @author Juergen Donnerstag
 */
public class HolidayCalendar extends BaseCalendar implements Calendar, Serializable {
  private static final long serialVersionUID = -7590908752291814693L;

  // A sorted set to store the holidays
  private TreeSet<Instant> dates = new TreeSet<>();

  /**
   * Create a calendar that excludes nothing yet, without a base calendar, in the system default
   * time zone.
   */
  public HolidayCalendar() {}

  /**
   * Create a calendar that excludes nothing yet on top of another, in the system default time zone.
   *
   * @param baseCalendar the calendar that must also include a time, or {@code null}
   */
  public HolidayCalendar(@Nullable Calendar baseCalendar) {
    super(baseCalendar);
  }

  /**
   * Create a calendar that excludes nothing yet without a base calendar.
   *
   * @param timeZone the time zone, or {@code null} for the system default
   */
  public HolidayCalendar(@Nullable ZoneId timeZone) {
    super(timeZone);
  }

  /**
   * Create a calendar that excludes nothing yet on top of another.
   *
   * @param baseCalendar the calendar that must also include a time, or {@code null}
   * @param timeZone the time zone, or {@code null} for the system default
   */
  public HolidayCalendar(@Nullable Calendar baseCalendar, @Nullable ZoneId timeZone) {
    super(baseCalendar, timeZone);
  }

  @Override
  public Object clone() {
    HolidayCalendar clone = (HolidayCalendar) super.clone();
    clone.dates = new TreeSet<>(dates);
    return clone;
  }

  /**
   * Determine whether the given instant is 'included' by the Calendar.
   *
   * <p>Note that this Calendar is only has full-day precision.
   */
  @Override
  public boolean isTimeIncluded(Instant timeStamp) {
    if (!super.isTimeIncluded(timeStamp)) {
      return false;
    }

    Instant lookFor = getStartOfDay(timeStamp).toInstant();

    return !(dates.contains(lookFor));
  }

  /**
   * Determine the next instant that is 'included' by the Calendar after the given instant.
   *
   * <p>Note that this Calendar is only has full-day precision.
   */
  @Override
  public Instant getNextIncludedTime(Instant timeStamp) {

    // Call base calendar implementation first
    Instant baseTime = super.getNextIncludedTime(timeStamp);
    if (baseTime.isAfter(Instant.EPOCH) && baseTime.isAfter(timeStamp)) {
      timeStamp = baseTime;
    }

    // Get timestamp for 00:00:00
    LenientDateTime day = getStartOfDay(timeStamp);
    while (!isTimeIncluded(day.toInstant())) {
      day.add(LenientDateTime.DAY_OF_MONTH, 1);
    }
    return day.toInstant();
  }

  /**
   * Add the given instant's day to the list of excluded days. Only the month, day and year of the
   * returned dates are significant.
   */
  public void addExcludedDate(Instant excludedDate) {
    Instant date = getStartOfDay(excludedDate).toInstant();
    /*
     * System.err.println( "HolidayCalendar.add(): date=" +
     * excludedDate.toLocaleString());
     */
    this.dates.add(date);
  }

  /**
   * Remove the given instant's day from the list of excluded days.
   *
   * @param dateToRemove any instant on the day to include again
   */
  public void removeExcludedDate(Instant dateToRemove) {
    Instant date = getStartOfDay(dateToRemove).toInstant();
    dates.remove(date);
  }

  /**
   * Returns a <code>SortedSet</code> of Dates representing the excluded days. Only the month, day
   * and year of the returned dates are significant.
   */
  public SortedSet<Instant> getExcludedDates() {
    return Collections.unmodifiableSortedSet(dates);
  }
}
