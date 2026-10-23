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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.utils.LenientDateTime;

/**
 * This implementation of the Calendar excludes a set of days of the year. You may use it to exclude
 * bank holidays which are on the same date every year.
 *
 * @see org.quartz.Calendar
 * @see org.quartz.impl.calendar.BaseCalendar
 * @author Juergen Donnerstag
 */
public class AnnualCalendar extends BaseCalendar implements Calendar, Serializable {

  private static final long serialVersionUID = 7346867105876610961L;

  private ArrayList<ZonedDateTime> excludeDays = new ArrayList<>();

  // true, if excludeDays is sorted
  private boolean dataSorted = false;

  public AnnualCalendar() {}

  public AnnualCalendar(@Nullable Calendar baseCalendar) {
    super(baseCalendar);
  }

  public AnnualCalendar(@Nullable ZoneId timeZone) {
    super(timeZone);
  }

  public AnnualCalendar(@Nullable Calendar baseCalendar, @Nullable ZoneId timeZone) {
    super(baseCalendar, timeZone);
  }

  @Override
  public Object clone() {
    AnnualCalendar clone = (AnnualCalendar) super.clone();
    clone.excludeDays = new ArrayList<>(excludeDays);
    return clone;
  }

  /** Get the array which defines the exclude-value of each day of month */
  public ArrayList<ZonedDateTime> getDaysExcluded() {
    return excludeDays;
  }

  /** Return true, if day is defined to be excluded. */
  public boolean isDayExcluded(ZonedDateTime day) {

    if (day == null) {
      throw new IllegalArgumentException("Parameter day must not be null");
    }

    // Check baseCalendar first
    if (!super.isTimeIncluded(day.toInstant())) {
      return true;
    }

    int dmonth = day.getMonthValue();
    int dday = day.getDayOfMonth();

    if (!dataSorted) {
      Collections.sort(excludeDays, new CalendarComparator());
      dataSorted = true;
    }

    for (ZonedDateTime cl : excludeDays) {
      // remember, the list is sorted
      if (dmonth < cl.getMonthValue()) {
        return false;
      }

      if (dday != cl.getDayOfMonth()) {
        continue;
      }

      if (dmonth != cl.getMonthValue()) {
        continue;
      }

      return true;
    }

    return false;
  }

  /**
   * Redefine the list of days excluded. The ArrayList should contain <code>java.time.ZonedDateTime
   * </code> objects.
   */
  public void setDaysExcluded(ArrayList<ZonedDateTime> days) {
    if (days == null) {
      excludeDays = new ArrayList<>();
    } else {
      excludeDays = days;
    }

    dataSorted = false;
  }

  /** Redefine a certain day to be excluded (true) or included (false). */
  public void setDayExcluded(ZonedDateTime day, boolean exclude) {
    if (exclude) {
      if (isDayExcluded(day)) {
        return;
      }

      excludeDays.add(day);
      dataSorted = false;
    } else {
      if (!isDayExcluded(day)) {
        return;
      }

      removeExcludedDay(day, true);
    }
  }

  /**
   * Remove the given day from the list of excluded days
   *
   * @param day the day to exclude
   */
  public void removeExcludedDay(ZonedDateTime day) {
    removeExcludedDay(day, false);
  }

  private void removeExcludedDay(ZonedDateTime day, boolean isChecked) {
    if (!isChecked && !isDayExcluded(day)) {
      return;
    }

    // Fast way, see if exact day object was already in list
    if (this.excludeDays.remove(day)) {
      return;
    }

    int dmonth = day.getMonthValue();
    int dday = day.getDayOfMonth();

    // Since there is no guarantee that the given day is in the arraylist with the exact same year
    // search for the object based on month and day of month in the list and remove it
    for (ZonedDateTime cl : excludeDays) {
      if (dmonth != cl.getMonthValue()) {
        continue;
      }

      if (dday != cl.getDayOfMonth()) {
        continue;
      }

      day = cl;
      break;
    }

    this.excludeDays.remove(day);
  }

  /**
   * Determine whether the given time (in milliseconds) is 'included' by the Calendar.
   *
   * <p>Note that this Calendar is only has full-day precision.
   */
  @Override
  public boolean isTimeIncluded(Instant timeStamp) {
    // Test the base calendar first. Only if the base calendar not already
    // excludes the time/date, continue evaluating this calendar instance.
    if (!super.isTimeIncluded(timeStamp)) {
      return false;
    }

    ZonedDateTime day = timeStamp.atZone(getTimeZoneOrDefault());

    return !(isDayExcluded(day));
  }

  /**
   * Determine the next time (in milliseconds) that is 'included' by the Calendar after the given
   * time. Return the original value if timeStamp is included. Return 0 if all days are excluded.
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
    if (!isDayExcluded(day.toZonedDateTime())) {
      return timeStamp; // return the original value
    }

    while (isDayExcluded(day.toZonedDateTime())) {
      day.add(LenientDateTime.DAY_OF_MONTH, 1);
    }

    return day.toInstant();
  }
}

class CalendarComparator implements Comparator<ZonedDateTime>, Serializable {

  private static final long serialVersionUID = 7346867105876610961L;

  public CalendarComparator() {}

  public int compare(ZonedDateTime c1, ZonedDateTime c2) {

    int month1 = c1.getMonthValue();
    int month2 = c2.getMonthValue();

    int day1 = c1.getDayOfMonth();
    int day2 = c2.getDayOfMonth();

    if (month1 < month2) {
      return -1;
    }
    if (month1 > month2) {
      return 1;
    }
    if (day1 < day2) {
      return -1;
    }
    if (day1 > day2) {
      return 1;
    }
    return 0;
  }
}
