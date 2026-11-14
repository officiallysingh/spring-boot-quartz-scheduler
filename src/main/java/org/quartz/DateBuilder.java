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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.quartz.utils.LenientDateTime;

/**
 * <code>DateBuilder</code> is used to conveniently create <code>java.time.Instant</code> values
 * that meet particular criteria.
 *
 * <p>Quartz provides a builder-style API for constructing scheduling-related entities via a
 * Domain-Specific Language (DSL). The DSL can best be utilized through the usage of static imports
 * of the methods on the classes <code>TriggerBuilder</code>, <code>JobBuilder</code>, <code>
 * DateBuilder</code>, <code>JobKey</code>, <code>TriggerKey</code> and the various <code>
 * ScheduleBuilder</code> implementations.
 *
 * <p>Client code can then use the DSL to write code such as this:
 *
 * <pre>
 *         JobDetail job = newJob(MyJob.class)
 *             .withIdentity("myJob")
 *             .build();
 *
 *         Trigger trigger = newTrigger()
 *             .withIdentity(triggerKey("myTrigger", "myTriggerGroup"))
 *             .withSchedule(simpleSchedule()
 *                 .withIntervalInHours(1)
 *                 .repeatForever())
 *             .startAt(futureDate(10, MINUTES, scheduler.getClock(), scheduler.getTimeZone()))
 *             .build();
 *
 *         scheduler.scheduleJob(job, trigger);
 * </pre>
 *
 * <p>Every helper that needs "now" takes a {@link Clock}, the scheduler's ({@link
 * Scheduler#getClock()}), and every helper that works with a date or an hour takes the {@link
 * ZoneId} it is in ({@link Scheduler#getTimeZone()} for the scheduler's default). None reads the
 * system clock or the JVM default zone. A local time that falls in a daylight-saving gap moves
 * forward by the gap, and one that exists twice is the later (standard-time) one: the rule of
 * {@link LenientDateTime}, as for every fire time.
 *
 * @see TriggerBuilder
 * @see JobBuilder
 */
public class DateBuilder {

  /**
   * A unit of time, for {@link #futureDate(int, IntervalUnit, java.time.Clock, ZoneId)} and the
   * interval-based triggers.
   */
  public enum IntervalUnit {
    /** Milliseconds. */
    MILLISECOND,
    /** Seconds. */
    SECOND,
    /** Minutes. */
    MINUTE,
    /** Hours. */
    HOUR,
    /** Calendar days (which may be 23 or 25 hours long across daylight-saving changes). */
    DAY,
    /** Calendar weeks. */
    WEEK,
    /** Calendar months. */
    MONTH,
    /** Calendar years. */
    YEAR
  }

  /** Sunday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int SUNDAY = 1;

  /** Monday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int MONDAY = 2;

  /** Tuesday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int TUESDAY = 3;

  /** Wednesday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int WEDNESDAY = 4;

  /** Thursday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int THURSDAY = 5;

  /** Friday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int FRIDAY = 6;

  /** Saturday, as a day-of-week number (Sunday = 1 … Saturday = 7, as in cron). */
  public static final int SATURDAY = 7;

  /** January, as a month number (January = 1). */
  public static final int JANUARY = 1;

  /** February, as a month number (January = 1). */
  public static final int FEBRUARY = 2;

  /** March, as a month number (January = 1). */
  public static final int MARCH = 3;

  /** April, as a month number (January = 1). */
  public static final int APRIL = 4;

  /** May, as a month number (January = 1). */
  public static final int MAY = 5;

  /** June, as a month number (January = 1). */
  public static final int JUNE = 6;

  /** July, as a month number (January = 1). */
  public static final int JULY = 7;

  /** August, as a month number (January = 1). */
  public static final int AUGUST = 8;

  /** September, as a month number (January = 1). */
  public static final int SEPTEMBER = 9;

  /** October, as a month number (January = 1). */
  public static final int OCTOBER = 10;

  /** November, as a month number (January = 1). */
  public static final int NOVEMBER = 11;

  /** December, as a month number (January = 1). */
  public static final int DECEMBER = 12;

  private int month = -1;
  private int day = -1;
  private int year = -1;
  private int hour = -1;
  private int minute = -1;
  private int second = -1;
  private final ZoneId zoneId;
  private final Clock clock;

  private DateBuilder(Clock clock, ZoneId zoneId) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
  }

  /**
   * Create a DateBuilder whose fields that are not set are taken from the current date and time by
   * the given clock, in the given time zone. There is no factory without them: a date needs a zone,
   * and "now" comes from the scheduler's clock ({@link Scheduler#getClock()}), never from the JVM
   * default zone or the system clock.
   *
   * @param clock the source of the current date and time
   * @param zoneId the zone the fields of the date are in
   */
  public static DateBuilder newDate(Clock clock, ZoneId zoneId) {
    return new DateBuilder(clock, zoneId);
  }

  /**
   * Build the Date defined by this builder instance: every field not set is taken from the current
   * date and time by the clock, in the zone. A local time in a daylight-saving gap moves forward by
   * the gap; one in an overlap is the later (standard-time) one, as everywhere in the scheduler.
   */
  public Instant build() {
    var now = now(clock, zoneId);
    var local =
        LocalDateTime.of(
            year != -1 ? year : now.getYear(),
            month != -1 ? month : now.getMonthValue(),
            day != -1 ? day : now.getDayOfMonth(),
            hour != -1 ? hour : now.getHour(),
            minute != -1 ? minute : now.getMinute(),
            second != -1 ? second : now.getSecond(),
            0);

    return LenientDateTime.resolve(local, zoneId);
  }

  /**
   * Set the hour (0-23) for the Date that will be built by this builder.
   *
   * @see #atMinute(int)
   * @see #atSecond(int)
   */
  public DateBuilder atHourOfDay(int atHour) {
    validateHour(atHour);

    this.hour = atHour;
    return this;
  }

  /**
   * Set the minute (0-59) for the Date that will be built by this builder.
   *
   * @see #atHourOfDay(int)
   * @see #atSecond(int)
   */
  public DateBuilder atMinute(int atMinute) {
    validateMinute(atMinute);

    this.minute = atMinute;
    return this;
  }

  /**
   * Set the second (0-59) for the Date that will be built by this builder, and truncate the
   * milliseconds to 000.
   *
   * @see #atHourOfDay(int)
   * @see #atMinute(int)
   */
  public DateBuilder atSecond(int atSecond) {
    validateSecond(atSecond);

    this.second = atSecond;
    return this;
  }

  /**
   * Set the hour (0-23), minute (0-59) and second (0-59) for the Date that will be built by this
   * builder, and truncate the milliseconds to 000.
   */
  public DateBuilder atHourMinuteAndSecond(int atHour, int atMinute, int atSecond) {
    validateHour(atHour);
    validateMinute(atMinute);
    validateSecond(atSecond);

    this.hour = atHour;
    this.second = atSecond;
    this.minute = atMinute;
    return this;
  }

  /**
   * Set the day of month (1-31) for the Date that will be built by this builder.
   *
   * @see #inMonth(int)
   */
  public DateBuilder onDay(int onDay) {
    validateDayOfMonth(onDay);

    this.day = onDay;
    return this;
  }

  /**
   * Set the month (1-12) for the Date that will be built by this builder.
   *
   * @see #onDay(int)
   */
  public DateBuilder inMonth(int inMonth) {
    validateMonth(inMonth);

    this.month = inMonth;
    return this;
  }

  /**
   * Set the month (1-12) and day of month (1-31) for the Date that will be built by this builder.
   */
  public DateBuilder inMonthOnDay(int inMonth, int onDay) {
    validateMonth(inMonth);
    validateDayOfMonth(onDay);

    this.month = inMonth;
    this.day = onDay;
    return this;
  }

  /** Set the year for the Date that will be built by this builder. */
  public DateBuilder inYear(int inYear) {
    validateYear(inYear);

    this.year = inYear;
    return this;
  }

  /**
   * The instant that is {@code interval} {@code unit}s from now by the given clock, counted in the
   * given time zone (relevant for days and longer units, across daylight-saving changes).
   *
   * @param interval how many units ahead
   * @param unit the unit of {@code interval}
   * @param clock the source of now
   * @param zoneId the zone to count days, weeks, months and years in
   * @return the future instant
   */
  public static Instant futureDate(int interval, IntervalUnit unit, Clock clock, ZoneId zoneId) {
    return now(clock, zoneId).plus(interval, translate(unit)).toInstant();
  }

  private static ZonedDateTime now(Clock clock, ZoneId zoneId) {
    return ZonedDateTime.ofInstant(clock.instant(), zoneId);
  }

  private static ChronoUnit translate(IntervalUnit unit) {
    switch (unit) {
      case DAY:
        return ChronoUnit.DAYS;
      case HOUR:
        return ChronoUnit.HOURS;
      case MINUTE:
        return ChronoUnit.MINUTES;
      case MONTH:
        return ChronoUnit.MONTHS;
      case SECOND:
        return ChronoUnit.SECONDS;
      case MILLISECOND:
        return ChronoUnit.MILLIS;
      case WEEK:
        return ChronoUnit.WEEKS;
      case YEAR:
        return ChronoUnit.YEARS;
      default:
        throw new IllegalArgumentException("Unknown IntervalUnit");
    }
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on tomorrow's date by the given
   * clock, in the given time zone.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param clock the source of today
   * @param zoneId the zone of the time of day
   * @return the new date
   */
  public static Instant tomorrowAt(int hour, int minute, int second, Clock clock, ZoneId zoneId) {
    return LenientDateTime.resolve(
        now(clock, zoneId).toLocalDate().plusDays(1).atTime(LocalTime.of(hour, minute, second, 0)),
        zoneId);
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on today's date by the given clock,
   * in the given time zone (equivalent to {@link #dateOf(int, int, int, Clock, ZoneId)}).
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param clock the source of today
   * @param zoneId the zone of the time of day
   * @return the new date
   */
  public static Instant todayAt(int hour, int minute, int second, Clock clock, ZoneId zoneId) {
    return dateOf(hour, minute, second, clock, zoneId);
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on today's date by the given clock,
   * in the given time zone (equivalent to {@link #todayAt(int, int, int, Clock, ZoneId)}).
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param clock the source of today
   * @param zoneId the zone of the time of day
   * @return the new date
   */
  public static Instant dateOf(int hour, int minute, int second, Clock clock, ZoneId zoneId) {
    return LenientDateTime.resolve(
        now(clock, zoneId).toLocalDate().atTime(LocalTime.of(hour, minute, second, 0)), zoneId);
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on the given date, in the current
   * year by the given clock, in the given time zone.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param dayOfMonth The value (1-31) to give the day of month field of the date
   * @param month The value (1-12) to give the month field of the date
   * @param clock the source of the current year
   * @param zoneId the zone of the date and time
   * @return the new date
   */
  public static Instant dateOf(
      int hour, int minute, int second, int dayOfMonth, int month, Clock clock, ZoneId zoneId) {
    return dateOf(hour, minute, second, dayOfMonth, month, now(clock, zoneId).getYear(), zoneId);
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on the given date, in the given
   * time zone. No clock is involved: every field is given.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param dayOfMonth The value (1-31) to give the day of month field of the date
   * @param month The value (1-12) to give the month field of the date
   * @param year The value (1970-999999999) to give the year field of the date
   * @param zoneId the zone of the date and time
   * @return the new date
   */
  public static Instant dateOf(
      int hour, int minute, int second, int dayOfMonth, int month, int year, ZoneId zoneId) {
    return LenientDateTime.resolve(
        LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, 0), zoneId);
  }

  /**
   * Returns a date that is rounded to the next even hour after now by the given clock, in the given
   * time zone (an hour boundary differs between zones whose offsets are not whole hours).
   *
   * <p>For example a current time of 08:13:54 would result in a date with the time of 09:00:00. If
   * the date's time is in the 23rd hour, the date's 'day' will be promoted, and the time will be
   * set to 00:00:00.
   *
   * @param clock the source of now
   * @param zoneId the zone whose hours to round to
   * @return the new rounded date
   */
  public static Instant evenHourDateAfterNow(Clock clock, ZoneId zoneId) {
    return evenHourDate(clock.instant(), zoneId);
  }

  /**
   * Returns a date that is rounded to the next even hour above the given date, in the given time
   * zone (an hour boundary differs between zones whose offsets are not whole hours).
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 09:00:00. If the date's time is in the 23rd hour, the date's 'day' will be promoted, and the
   * time will be set to 00:00:00.
   *
   * @param date the instant to round
   * @param zoneId the zone whose hours to round to
   * @return the new rounded date
   */
  public static Instant evenHourDate(Instant date, ZoneId zoneId) {
    return date.atZone(zoneId).plusHours(1).truncatedTo(ChronoUnit.HOURS).toInstant();
  }

  /**
   * Returns a date that is rounded to the previous even hour below the given date, in the given
   * time zone.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:00:00.
   *
   * @param date the instant to round
   * @param zoneId the zone whose hours to round to
   * @return the new rounded date
   */
  public static Instant evenHourDateBefore(Instant date, ZoneId zoneId) {
    return date.atZone(zoneId).truncatedTo(ChronoUnit.HOURS).toInstant();
  }

  /**
   * Returns a date that is rounded to the next even minute after now by the given clock. Minute
   * boundaries are the same in every time zone.
   *
   * <p>For example a current time of 08:13:54 would result in a date with the time of 08:14:00. If
   * the date's time is in the 59th minute, then the hour (and possibly the day) will be promoted.
   *
   * @param clock the source of now
   * @return the new rounded date
   */
  public static Instant evenMinuteDateAfterNow(Clock clock) {
    return evenMinuteDate(clock.instant());
  }

  /**
   * Returns a date that is rounded to the next even minute above the given date. Minute boundaries
   * are the same in every time zone.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:14:00. If the date's time is in the 59th minute, then the hour (and possibly the day) will
   * be promoted.
   *
   * @param date the instant to round
   * @return the new rounded date
   */
  public static Instant evenMinuteDate(Instant date) {
    return date.plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
  }

  /**
   * Returns a date that is rounded to the previous even minute below the given date. Minute
   * boundaries are the same in every time zone.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:13:00.
   *
   * @param date the instant to round
   * @return the new rounded date
   */
  public static Instant evenMinuteDateBefore(Instant date) {
    return date.truncatedTo(ChronoUnit.MINUTES);
  }

  /**
   * Returns a date that is rounded to the next even second after now by the given clock.
   *
   * @param clock the source of now
   * @return the new rounded date
   */
  public static Instant evenSecondDateAfterNow(Clock clock) {
    return evenSecondDate(clock.instant());
  }

  /**
   * Returns a date that is rounded to the next even second above the given date.
   *
   * @param date the instant to round
   * @return the new rounded date
   */
  public static Instant evenSecondDate(Instant date) {
    return date.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * Returns a date that is rounded to the previous even second below the given date.
   *
   * <p>For example an input date with a time of 08:13:54.341 would result in a date with the time
   * of 08:13:54.000.
   *
   * @param date the instant to round
   * @return the new rounded date
   */
  public static Instant evenSecondDateBefore(Instant date) {
    return date.truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * Returns a date that is rounded to the next even multiple of the given minute, in the given time
   * zone (the hour the minutes count from differs between zones whose offsets are not whole hours).
   *
   * <p>For example an input date with a time of 08:13:54, and an input minute-base of 5 would
   * result in a date with the time of 08:15:00. The same input date with an input minute-base of 10
   * would result in a date with the time of 08:20:00. But a minute-base of 0 would result in
   * 09:00:00, because the even-hour is the next 'base' for 0-minutes.
   *
   * <p>More examples:
   *
   * <table>
   * <caption>Examples of inputs and corresponding outputs.</caption>
   * <tr>
   * <th>Input Time</th>
   * <th>Minute-Base</th>
   * <th>Result Time</th>
   * </tr>
   * <tr>
   * <td>11:16:41</td>
   * <td>20</td>
   * <td>11:20:00</td>
   * </tr>
   * <tr>
   * <td>11:36:41</td>
   * <td>20</td>
   * <td>11:40:00</td>
   * </tr>
   * <tr>
   * <td>11:46:41</td>
   * <td>20</td>
   * <td>12:00:00</td>
   * </tr>
   * <tr>
   * <td>11:26:41</td>
   * <td>30</td>
   * <td>11:30:00</td>
   * </tr>
   * <tr>
   * <td>11:36:41</td>
   * <td>30</td>
   * <td>12:00:00</td>
   * </tr>
   * <tr>
   * <td>11:16:41</td>
   * <td>17</td>
   * <td>11:17:00</td>
   * </tr>
   * <tr>
   * <td>11:17:41</td>
   * <td>17</td>
   * <td>11:34:00</td>
   * </tr>
   * <tr>
   * <td>11:52:41</td>
   * <td>17</td>
   * <td>12:00:00</td>
   * </tr>
   * <tr>
   * <td>11:52:41</td>
   * <td>5</td>
   * <td>11:55:00</td>
   * </tr>
   * <tr>
   * <td>11:57:41</td>
   * <td>5</td>
   * <td>12:00:00</td>
   * </tr>
   * <tr>
   * <td>11:17:41</td>
   * <td>0</td>
   * <td>12:00:00</td>
   * </tr>
   * <tr>
   * <td>11:17:41</td>
   * <td>1</td>
   * <td>11:08:00</td>
   * </tr>
   * </table>
   *
   * @param date the instant to round
   * @param minuteBase the base-minute to set the time on
   * @param zoneId the zone whose hours the minutes count from
   * @return the new rounded date
   * @see #nextGivenSecondDate(Instant, int)
   */
  public static Instant nextGivenMinuteDate(Instant date, int minuteBase, ZoneId zoneId) {
    if (minuteBase < 0 || minuteBase > 59) {
      throw new IllegalArgumentException("minuteBase must be >=0 and <= 59");
    }

    var zdt = date.atZone(zoneId);
    if (minuteBase == 0) {
      zdt = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1);
      return zdt.toInstant();
    }

    zdt = zdt.truncatedTo(ChronoUnit.MINUTES);
    int minute = zdt.getMinute();
    int nextminute = minute + minuteBase - (minute % minuteBase);

    if (nextminute >= 60) {
      zdt = zdt.truncatedTo(ChronoUnit.HOURS).plusHours(1);
    } else {
      zdt = zdt.withMinute(nextminute);
    }

    return zdt.toInstant();
  }

  /**
   * Returns a date that is rounded to the next even multiple of the given second. Minute boundaries
   * are the same in every time zone, so no zone is needed.
   *
   * <p>The rules for calculating the second are the same as those for calculating the minute in the
   * method <code>nextGivenMinuteDate(..)</code>.
   *
   * @param date the instant to round
   * @param secondBase the base-second to set the time on
   * @return the new rounded date
   * @see #nextGivenMinuteDate(Instant, int, ZoneId)
   */
  public static Instant nextGivenSecondDate(Instant date, int secondBase) {
    if (secondBase < 0 || secondBase > 59) {
      throw new IllegalArgumentException("secondBase must be >=0 and <= 59");
    }

    var zdt = date.atZone(ZoneOffset.UTC);
    if (secondBase == 0) {
      zdt = zdt.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
      return zdt.toInstant();
    }

    zdt = zdt.truncatedTo(ChronoUnit.SECONDS);
    int second = zdt.getSecond();
    int nextSecond = second + secondBase - (second % secondBase);

    if (nextSecond >= 60) {
      zdt = zdt.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
    } else {
      zdt = zdt.withSecond(nextSecond);
    }

    return zdt.toInstant();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Check a day of the week.
   *
   * @param dayOfWeek the value, {@link #SUNDAY} (1) to {@link #SATURDAY} (7)
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateDayOfWeek(int dayOfWeek) {
    if (dayOfWeek < SUNDAY || dayOfWeek > SATURDAY) {
      throw new IllegalArgumentException("Invalid day of week.");
    }
  }

  /**
   * Check an hour of the day.
   *
   * @param hour the value, 0 to 23
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateHour(int hour) {
    if (hour < 0 || hour > 23) {
      throw new IllegalArgumentException("Invalid hour (must be >= 0 and <= 23).");
    }
  }

  /**
   * Check a minute.
   *
   * @param minute the value, 0 to 59
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateMinute(int minute) {
    if (minute < 0 || minute > 59) {
      throw new IllegalArgumentException("Invalid minute (must be >= 0 and <= 59).");
    }
  }

  /**
   * Check a second.
   *
   * @param second the value, 0 to 59
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateSecond(int second) {
    if (second < 0 || second > 59) {
      throw new IllegalArgumentException("Invalid second (must be >= 0 and <= 59).");
    }
  }

  /**
   * Check a day of the month.
   *
   * @param day the value, 1 to 31
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateDayOfMonth(int day) {
    if (day < 1 || day > 31) {
      throw new IllegalArgumentException("Invalid day of month.");
    }
  }

  /**
   * Check a month.
   *
   * @param month the value, 1 to 12
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateMonth(int month) {
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("Invalid month (must be >= 1 and <= 12).");
    }
  }

  /**
   * Check a year.
   *
   * @param year the value, 1970 to {@link Year#MAX_VALUE}
   * @throws IllegalArgumentException if it is out of range
   */
  public static void validateYear(int year) {
    if (year < 1970 || year > Year.MAX_VALUE) {
      throw new IllegalArgumentException(
          "Invalid year (must be >= 1970 and <= " + Year.MAX_VALUE + ").");
    }
  }
}
