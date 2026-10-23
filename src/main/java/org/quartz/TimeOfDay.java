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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;
import org.quartz.utils.LenientDateTime;
import org.springframework.lang.Contract;

/**
 * Represents a time in hour, minute and second of any given day.
 *
 * <p>The hour is in 24-hour convention, meaning values are from 0 to 23.
 *
 * @see DailyTimeIntervalScheduleBuilder
 * @since 2.0.3
 * @author James House
 * @author Zemian Deng &lt;saltnlight5@gmail.com&gt;
 */
public class TimeOfDay implements Serializable {

  private static final long serialVersionUID = 2964774315889061771L;

  private final int hour;
  private final int minute;
  private final int second;

  /**
   * Create a TimeOfDay instance for the given hour, minute and second.
   *
   * @param hour The hour of day, between 0 and 23.
   * @param minute The minute of the hour, between 0 and 59.
   * @param second The second of the minute, between 0 and 59.
   * @throws IllegalArgumentException if one or more of the input values is out of their valid
   *     range.
   */
  public TimeOfDay(int hour, int minute, int second) {
    this.hour = hour;
    this.minute = minute;
    this.second = second;
    validate();
  }

  /**
   * Create a TimeOfDay instance for the given hour and minute (at the zero second of the minute).
   *
   * @param hour The hour of day, between 0 and 23.
   * @param minute The minute of the hour, between 0 and 59.
   * @throws IllegalArgumentException if one or more of the input values is out of their valid
   *     range.
   */
  public TimeOfDay(int hour, int minute) {
    this.hour = hour;
    this.minute = minute;
    this.second = 0;
    validate();
  }

  private void validate() {
    if (hour < 0 || hour > 23) throw new IllegalArgumentException("Hour must be from 0 to 23");
    if (minute < 0 || minute > 59)
      throw new IllegalArgumentException("Minute must be from 0 to 59");
    if (second < 0 || second > 59)
      throw new IllegalArgumentException("Second must be from 0 to 59");
  }

  /**
   * Create a TimeOfDay instance for the given hour, minute and second.
   *
   * @param hour The hour of day, between 0 and 23.
   * @param minute The minute of the hour, between 0 and 59.
   * @param second The second of the minute, between 0 and 59.
   * @throws IllegalArgumentException if one or more of the input values is out of their valid
   *     range.
   */
  public static TimeOfDay hourMinuteAndSecondOfDay(int hour, int minute, int second) {
    return new TimeOfDay(hour, minute, second);
  }

  /**
   * Create a TimeOfDay instance for the given hour and minute (at the zero second of the minute).
   *
   * @param hour The hour of day, between 0 and 23.
   * @param minute The minute of the hour, between 0 and 59.
   * @throws IllegalArgumentException if one or more of the input values is out of their valid
   *     range.
   */
  public static TimeOfDay hourAndMinuteOfDay(int hour, int minute) {
    return new TimeOfDay(hour, minute);
  }

  /**
   * The hour of the day (between 0 and 23).
   *
   * @return The hour of the day (between 0 and 23).
   */
  public int getHour() {
    return hour;
  }

  /**
   * The minute of the hour.
   *
   * @return The minute of the hour (between 0 and 59).
   */
  public int getMinute() {
    return minute;
  }

  /**
   * The second of the minute.
   *
   * @return The second of the minute (between 0 and 59).
   */
  public int getSecond() {
    return second;
  }

  /**
   * Determine with this time of day is before the given time of day.
   *
   * @return true this time of day is before the given time of day.
   */
  public boolean before(TimeOfDay timeOfDay) {

    if (timeOfDay.hour > hour) return true;
    if (timeOfDay.hour < hour) return false;

    if (timeOfDay.minute > minute) return true;
    if (timeOfDay.minute < minute) return false;

    if (timeOfDay.second > second) return true;
    if (timeOfDay.second < second) return false;

    return false; // must be equal...
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof TimeOfDay)) return false;

    TimeOfDay other = (TimeOfDay) obj;

    return (other.hour == hour && other.minute == minute && other.second == second);
  }

  @Override
  public int hashCode() {
    return (hour + 1) ^ (minute + 1) ^ (second + 1);
  }

  /**
   * Return a time with time of day reset to this object values, in the system default time zone.
   * The millisecond value will be zero.
   */
  @Contract("null -> null; !null -> !null")
  public @Nullable Instant getTimeOfDayForDate(@Nullable Instant dateTime) {
    return getTimeOfDayForDate(dateTime, ZoneId.systemDefault());
  }

  /**
   * Return a time with time of day reset to this object values, in the given time zone. The
   * millisecond value will be zero. A time of day that does not exist on that day (DST gap) moves
   * forward by the length of the gap.
   */
  @Contract("null, _ -> null; !null, _ -> !null")
  public @Nullable Instant getTimeOfDayForDate(@Nullable Instant dateTime, ZoneId zone) {
    if (dateTime == null) return null;
    LenientDateTime cal = new LenientDateTime(dateTime, zone);
    cal.set(LenientDateTime.HOUR_OF_DAY, hour);
    cal.set(LenientDateTime.MINUTE, minute);
    cal.set(LenientDateTime.SECOND, second);
    cal.set(LenientDateTime.MILLISECOND, 0);
    return cal.toInstant();
  }

  /** This time of day as a {@link LocalTime}. */
  public LocalTime toLocalTime() {
    return LocalTime.of(hour, minute, second);
  }

  /** Create a TimeOfDay from the hour, minute and second of the given {@link LocalTime}. */
  public static TimeOfDay from(LocalTime localTime) {
    return new TimeOfDay(localTime.getHour(), localTime.getMinute(), localTime.getSecond());
  }

  /**
   * Create a TimeOfDay from the given date, in the system default time zone.
   *
   * @param dateTime The instant from which to extract Hour, Minute and Second.
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable TimeOfDay hourAndMinuteAndSecondFromDate(@Nullable Instant dateTime) {
    return hourAndMinuteAndSecondFromDate(dateTime, null);
  }

  /**
   * Create a TimeOfDay from the given date, in the given time zone.
   *
   * @param dateTime The instant from which to extract Hour, Minute and Second.
   * @param tz The time zone from which relate Hour, Minute and Second for the given date. If null,
   *     system default time zone will be used.
   */
  @Contract("null, _ -> null; !null, _ -> !null")
  public static @Nullable TimeOfDay hourAndMinuteAndSecondFromDate(
      @Nullable Instant dateTime, @Nullable ZoneId tz) {
    if (dateTime == null) return null;
    ZonedDateTime cal = dateTime.atZone(tz != null ? tz : ZoneId.systemDefault());

    return new TimeOfDay(cal.getHour(), cal.getMinute(), cal.getSecond());
  }

  /**
   * Create a TimeOfDay from the given date (at the zero-second), in the system default time zone.
   *
   * @param dateTime The instant from which to extract Hour and Minute.
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable TimeOfDay hourAndMinuteFromDate(@Nullable Instant dateTime) {
    return hourAndMinuteFromDate(dateTime, null);
  }

  /**
   * Create a TimeOfDay from the given date (at the zero-second), in the system default time zone.
   *
   * @param dateTime The instant from which to extract Hour and Minute.
   * @param tz The time zone from which relate Hour and Minute for the given date. If null, system
   *     default time zone will be used.
   */
  @Contract("null, _ -> null; !null, _ -> !null")
  public static @Nullable TimeOfDay hourAndMinuteFromDate(
      @Nullable Instant dateTime, @Nullable ZoneId tz) {
    if (dateTime == null) return null;
    ZonedDateTime cal = dateTime.atZone(tz != null ? tz : ZoneId.systemDefault());

    return new TimeOfDay(cal.getHour(), cal.getMinute());
  }

  @Override
  public String toString() {
    return "TimeOfDay[" + hour + ":" + minute + ":" + second + "]";
  }
}
