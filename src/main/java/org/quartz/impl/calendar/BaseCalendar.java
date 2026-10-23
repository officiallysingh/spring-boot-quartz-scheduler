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
import org.quartz.utils.LenientDateTime;

/**
 * This implementation of the Calendar may be used (you don't have to) as a base class for more
 * sophisticated one's. It merely implements the base functionality required by each Calendar.
 *
 * <p>Regarded as base functionality is the treatment of base calendars. Base calendar allow you to
 * chain (stack) as much calendars as you may need. For example to exclude weekends you may use
 * WeeklyCalendar. In order to exclude holidays as well you may define a WeeklyCalendar instance to
 * be the base calendar for HolidayCalendar instance.
 *
 * @see org.quartz.Calendar
 * @author Juergen Donnerstag
 * @author James House
 */
public class BaseCalendar implements Calendar, Serializable, Cloneable {

  private static final long serialVersionUID = 3106623404629760239L;

  // <p>A optional base calendar.</p>
  private Calendar baseCalendar;

  private String description;

  private ZoneId timeZone;

  public BaseCalendar() {}

  public BaseCalendar(Calendar baseCalendar) {
    setBaseCalendar(baseCalendar);
  }

  /**
   * @param timeZone The time zone to use for this Calendar, <code>null</code> if <code>
   *     {@link ZoneId#systemDefault()}</code> should be used
   */
  public BaseCalendar(ZoneId timeZone) {
    setTimeZone(timeZone);
  }

  /**
   * @param timeZone The time zone to use for this Calendar, <code>null</code> if <code>
   *     {@link ZoneId#systemDefault()}</code> should be used
   */
  public BaseCalendar(Calendar baseCalendar, ZoneId timeZone) {
    setBaseCalendar(baseCalendar);
    setTimeZone(timeZone);
  }

  @Override
  public Object clone() {
    try {
      BaseCalendar clone = (BaseCalendar) super.clone();
      if (getBaseCalendar() != null) {
        clone.baseCalendar = (Calendar) getBaseCalendar().clone();
      }
      return clone;
    } catch (CloneNotSupportedException ex) {
      throw new IncompatibleClassChangeError("Not Cloneable.");
    }
  }

  /** Set a new base calendar or remove the existing one */
  public void setBaseCalendar(Calendar baseCalendar) {
    this.baseCalendar = baseCalendar;
  }

  /** Get the base calendar. Will be null, if not set. */
  public Calendar getBaseCalendar() {
    return this.baseCalendar;
  }

  /**
   * Return the description given to the <code>Calendar</code> instance by its creator (if any).
   *
   * @return null if no description was set.
   */
  public String getDescription() {
    return description;
  }

  /**
   * Set a description for the <code>Calendar</code> instance - may be useful for
   * remembering/displaying the purpose of the calendar, though the description has no meaning to
   * Quartz.
   */
  public void setDescription(String description) {
    this.description = description;
  }

  /**
   * Returns the time zone for which this <code>Calendar</code> will be resolved.
   *
   * @return This Calendar's timezone, <code>null</code> if Calendar should use the <code>
   *     {@link ZoneId#systemDefault()}</code>
   */
  public ZoneId getTimeZone() {
    return timeZone;
  }

  /**
   * Sets the time zone for which this <code>Calendar</code> will be resolved.
   *
   * @param timeZone The time zone to use for this Calendar, <code>null</code> if <code>
   *     {@link ZoneId#systemDefault()}</code> should be used
   */
  public void setTimeZone(ZoneId timeZone) {
    this.timeZone = timeZone;
  }

  /**
   * Check if date/time represented by timeStamp is included. If included return true. The
   * implementation of BaseCalendar simply calls the base calendars isTimeIncluded() method if base
   * calendar is set.
   *
   * @see org.quartz.Calendar#isTimeIncluded(java.time.Instant)
   */
  public boolean isTimeIncluded(Instant timeStamp) {

    if (!timeStamp.isAfter(Instant.EPOCH)) {
      throw new IllegalArgumentException("timeStamp must be greater 0");
    }

    if (baseCalendar != null) {
      return baseCalendar.isTimeIncluded(timeStamp);
    }

    return true;
  }

  /**
   * Determine the next time (in milliseconds) that is 'included' by the Calendar after the given
   * time. Return the original value if timeStamp is included. Return 0 if all days are excluded.
   *
   * @see org.quartz.Calendar#getNextIncludedTime(java.time.Instant)
   */
  public Instant getNextIncludedTime(Instant timeStamp) {

    if (!timeStamp.isAfter(Instant.EPOCH)) {
      throw new IllegalArgumentException("timeStamp must be greater 0");
    }

    if (baseCalendar != null) {
      return baseCalendar.getNextIncludedTime(timeStamp);
    }

    return timeStamp;
  }

  /** The calendar's time zone, or the system default zone if none was set. */
  protected ZoneId getTimeZoneOrDefault() {
    return (getTimeZone() == null) ? ZoneId.systemDefault() : getTimeZone();
  }

  /**
   * Build a <code>{@link LenientDateTime}</code> for the given time in this calendar's time zone
   * (or the default time zone if none was set).
   */
  protected LenientDateTime createDateTime(Instant timeStamp) {
    return new LenientDateTime(timeStamp, getTimeZoneOrDefault());
  }

  /** Returns the start of the given day (00:00:00.000) in this calendar's time zone. */
  protected LenientDateTime getStartOfDay(Instant timeStamp) {
    LenientDateTime startOfDay = createDateTime(timeStamp);
    startOfDay.set(LenientDateTime.HOUR_OF_DAY, 0);
    startOfDay.set(LenientDateTime.MINUTE, 0);
    startOfDay.set(LenientDateTime.SECOND, 0);
    startOfDay.set(LenientDateTime.MILLISECOND, 0);
    return startOfDay;
  }

  /** Returns the end of the given day (23:59:59.999) in this calendar's time zone. */
  protected LenientDateTime getEndOfDay(Instant timeStamp) {
    LenientDateTime endOfDay = createDateTime(timeStamp);
    endOfDay.set(LenientDateTime.HOUR_OF_DAY, 23);
    endOfDay.set(LenientDateTime.MINUTE, 59);
    endOfDay.set(LenientDateTime.SECOND, 59);
    endOfDay.set(LenientDateTime.MILLISECOND, 999);
    return endOfDay;
  }
}
