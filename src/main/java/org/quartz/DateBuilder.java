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
import java.util.Locale;
import org.jspecify.annotations.Nullable;

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
 *             .startAt(futureDate(10, MINUTES))
 *             .build();
 *
 *         scheduler.scheduleJob(job, trigger);
 * </pre>
 *
 * @see TriggerBuilder
 * @see JobBuilder
 */
public class DateBuilder {

  /** A unit of time, for {@link #futureDate(int, IntervalUnit)} and the interval-based triggers. */
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

  /** Milliseconds in a minute. */
  public static final long MILLISECONDS_IN_MINUTE = 60L * 1000L;

  /** Milliseconds in an hour. */
  public static final long MILLISECONDS_IN_HOUR = 60L * 60L * 1000L;

  /** Seconds in a day without a daylight-saving change. */
  public static final long SECONDS_IN_MOST_DAYS = 24L * 60L * 60L;

  /** Milliseconds in a day without a daylight-saving change. */
  public static final long MILLISECONDS_IN_DAY = SECONDS_IN_MOST_DAYS * 1000L;

  private int month = -1;
  private int day = -1;
  private int year = -1;
  private int hour = -1;
  private int minute = -1;
  private int second = -1;
  private ZoneId zoneId;
  private @Nullable Locale lc;
  private Clock clock = Clock.systemDefaultZone();

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the system default
   * timezone.
   */
  private DateBuilder() {
    this(ZoneId.systemDefault());
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given
   * timezone.
   */
  private DateBuilder(ZoneId tz) {
    this.zoneId = tz;
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given locale.
   */
  private DateBuilder(Locale lc) {
    this(ZoneId.systemDefault());
    this.lc = lc;
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given timezone
   * and locale.
   */
  private DateBuilder(ZoneId tz, Locale lc) {
    this(tz);
    this.lc = lc;
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the system default
   * timezone.
   */
  public static DateBuilder newDate() {
    return new DateBuilder();
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given
   * timezone.
   */
  public static DateBuilder newDateInTimezone(ZoneId tz) {
    return new DateBuilder(tz);
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given locale.
   */
  public static DateBuilder newDateInLocale(Locale lc) {
    return new DateBuilder(lc);
  }

  /**
   * Create a DateBuilder, with initial settings for the current date and time in the given timezone
   * and locale.
   */
  public static DateBuilder newDateInTimeZoneAndLocale(ZoneId tz, Locale lc) {
    return new DateBuilder(tz, lc);
  }

  /** Build the Date defined by this builder instance. */
  public Instant build() {
    var useZoneId = (zoneId != null) ? zoneId : ZoneId.systemDefault();

    if (year == -1 || month == -1 || day == -1 || hour == -1 || minute == -1 || second == -1) {
      var zdt = ZonedDateTime.now(clock).withZoneSameInstant(useZoneId);

      year = zdt.getYear();
      month = zdt.getMonthValue();
      day = zdt.getDayOfMonth();
      hour = zdt.getHour();
      minute = zdt.getMinute();
      second = zdt.getSecond();
    }
    var zdt = ZonedDateTime.of(year, month, day, hour, minute, second, 0, useZoneId);

    return zdt.toInstant();
  }

  /** Set the hour (0-23) for the instant that will be built by this builder. */
  public DateBuilder atHourOfDay(int atHour) {
    validateHour(atHour);

    this.hour = atHour;
    return this;
  }

  /** Set the minute (0-59) for the instant that will be built by this builder. */
  public DateBuilder atMinute(int atMinute) {
    validateMinute(atMinute);

    this.minute = atMinute;
    return this;
  }

  /**
   * Set the second (0-59) for the instant that will be built by this builder, and truncate the
   * milliseconds to 000.
   */
  public DateBuilder atSecond(int atSecond) {
    validateSecond(atSecond);

    this.second = atSecond;
    return this;
  }

  /**
   * Set the hour (0-23), minute (0-59) and second (0-59) of the instant that will be built.
   *
   * @param atHour the hour of the day
   * @param atMinute the minute of the hour
   * @param atSecond the second of the minute
   * @return the updated DateBuilder
   * @throws IllegalArgumentException if a value is out of range
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

  /** Set the day of month (1-31) for the instant that will be built by this builder. */
  public DateBuilder onDay(int onDay) {
    validateDayOfMonth(onDay);

    this.day = onDay;
    return this;
  }

  /** Set the month (1-12) for the instant that will be built by this builder. */
  public DateBuilder inMonth(int inMonth) {
    validateMonth(inMonth);

    this.month = inMonth;
    return this;
  }

  /**
   * Set the month (1-12) and day of the month (1-31) of the instant that will be built.
   *
   * @param inMonth the month, for example {@link #MARCH}
   * @param onDay the day of the month
   * @return the updated DateBuilder
   * @throws IllegalArgumentException if a value is out of range
   */
  public DateBuilder inMonthOnDay(int inMonth, int onDay) {
    validateMonth(inMonth);
    validateDayOfMonth(onDay);

    this.month = inMonth;
    this.day = onDay;
    return this;
  }

  /** Set the year for the instant that will be built by this builder. */
  public DateBuilder inYear(int inYear) {
    validateYear(inYear);

    this.year = inYear;
    return this;
  }

  /**
   * Set the time zone for the instant that will be built by this builder (if "null", system default
   * will be used)
   */
  public DateBuilder inTimeZone(ZoneId timezone) {
    this.zoneId = timezone;
    return this;
  }

  /**
   * Set the Locale for the instant that will be built by this builder (if "null", system default
   * will be used)
   */
  public DateBuilder inLocale(Locale locale) {
    this.lc = locale;
    return this;
  }

  /**
   * The instant that is {@code interval} {@code unit}s from now, in the system default time zone
   * (relevant for days and longer units).
   *
   * @param interval how many units ahead
   * @param unit the unit of {@code interval}
   * @return the future instant
   */
  public static Instant futureDate(int interval, IntervalUnit unit) {
    return futureDate(interval, unit, Clock.systemDefaultZone());
  }

  static Instant futureDate(int interval, IntervalUnit unit, Clock clock) {
    return ZonedDateTime.now(clock).plus(interval, translate(unit)).toInstant();
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
   * Get an <code>Instant</code> that represents the given time, on tomorrow's date.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @return the new date
   */
  public static Instant tomorrowAt(int hour, int minute, int second) {
    return tomorrowAt(hour, minute, second, Clock.systemDefaultZone());
  }

  static Instant tomorrowAt(int hour, int minute, int second, Clock clock) {
    return ZonedDateTime.now(clock)
        .truncatedTo(ChronoUnit.DAYS)
        .plusHours(24)
        .with(LocalTime.of(hour, minute, second, 0))
        .toInstant();
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on today's date (equivalent to
   * {@link #dateOf(int, int, int)}).
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @return the new date
   */
  public static Instant todayAt(int hour, int minute, int second) {
    return todayAt(hour, minute, second, Clock.systemDefaultZone());
  }

  static Instant todayAt(int hour, int minute, int second, Clock clock) {
    return dateOf(hour, minute, second, clock);
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on today's date (equivalent to
   * {@link #todayAt(int, int, int)}).
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @return the new date
   */
  public static Instant dateOf(int hour, int minute, int second) {
    return dateOf(hour, minute, second, Clock.systemDefaultZone());
  }

  static Instant dateOf(int hour, int minute, int second, Clock clock) {
    return ZonedDateTime.now(clock).with(LocalTime.of(hour, minute, second, 0)).toInstant();
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on the given date.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param dayOfMonth The value (1-31) to give the day of month field of the date
   * @param month The value (1-12) to give the month field of the date
   * @return the new date
   */
  public static Instant dateOf(int hour, int minute, int second, int dayOfMonth, int month) {
    return dateOf(hour, minute, second, dayOfMonth, month, Clock.systemDefaultZone());
  }

  static Instant dateOf(int hour, int minute, int second, int dayOfMonth, int month, Clock clock) {
    var zdt = ZonedDateTime.now(clock);
    return zdt.with(LocalDateTime.of(zdt.getYear(), month, dayOfMonth, hour, minute, second, 0))
        .toInstant();
  }

  /**
   * Get an <code>Instant</code> that represents the given time, on the given date.
   *
   * @param hour The value (0-23) to give the hours field of the date
   * @param minute The value (0-59) to give the minutes field of the date
   * @param second The value (0-59) to give the seconds field of the date
   * @param dayOfMonth The value (1-31) to give the day of month field of the date
   * @param month The value (1-12) to give the month field of the date
   * @param year The value (1970-999999999) to give the year field of the date
   * @return the new date
   */
  public static Instant dateOf(
      int hour, int minute, int second, int dayOfMonth, int month, int year) {
    return dateOf(hour, minute, second, dayOfMonth, month, year, Clock.systemDefaultZone());
  }

  static Instant dateOf(
      int hour, int minute, int second, int dayOfMonth, int month, int year, Clock clock) {
    return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second, 0)
        .atZone(clock.getZone())
        .toInstant();
  }

  /**
   * Returns a date that is rounded to the next even hour after the current time.
   *
   * <p>For example a current time of 08:13:54 would result in a date with the time of 09:00:00. If
   * the date's time is in the 23rd hour, the date's 'day' will be promoted, and the time will be
   * set to 00:00:00.
   *
   * @return the new rounded date
   */
  public static Instant evenHourDateAfterNow() {
    return evenHourDateAfterNow(Clock.systemDefaultZone());
  }

  static Instant evenHourDateAfterNow(Clock clock) {
    return evenHourDate(null, clock);
  }

  /**
   * Returns a date that is rounded to the next even hour above the given date.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 09:00:00. If the date's time is in the 23rd hour, the date's 'day' will be promoted, and the
   * time will be set to 00:00:00.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenHourDate(@Nullable Instant date) {
    return evenHourDate(date, Clock.systemDefaultZone());
  }

  static Instant evenHourDate(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    zdt = zdt.plusHours(1);
    return zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
  }

  /**
   * Returns a date that is rounded to the previous even hour below the given date.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:00:00.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenHourDateBefore(@Nullable Instant date) {
    return evenHourDateBefore(date, Clock.systemDefaultZone());
  }

  static Instant evenHourDateBefore(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    return zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
  }

  /**
   * Returns a date that is rounded to the next even minute after the current time.
   *
   * <p>For example a current time of 08:13:54 would result in a date with the time of 08:14:00. If
   * the date's time is in the 59th minute, then the hour (and possibly the day) will be promoted.
   *
   * @return the new rounded date
   */
  public static Instant evenMinuteDateAfterNow() {
    return evenMinuteDateAfterNow(Clock.systemDefaultZone());
  }

  static Instant evenMinuteDateAfterNow(Clock clock) {
    return evenMinuteDate(null, clock);
  }

  /**
   * Returns a date that is rounded to the next even minute above the given date.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:14:00. If the date's time is in the 59th minute, then the hour (and possibly the day) will
   * be promoted.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenMinuteDate(@Nullable Instant date) {
    return evenMinuteDate(date, Clock.systemDefaultZone());
  }

  /**
   * Like {@link #evenMinuteDate(Instant)}, with the current time taken from {@code clock}.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @param clock the clock supplying the current time
   * @return the new rounded date
   */
  public static Instant evenMinuteDate(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    zdt = zdt.plusMinutes(1);
    return zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
  }

  /**
   * Returns a date that is rounded to the previous even minute below the given date.
   *
   * <p>For example an input date with a time of 08:13:54 would result in a date with the time of
   * 08:13:00.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenMinuteDateBefore(@Nullable Instant date) {
    return evenMinuteDateBefore(date, Clock.systemDefaultZone());
  }

  static Instant evenMinuteDateBefore(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    return zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
  }

  /**
   * Returns a date that is rounded to the next even second after the current time.
   *
   * @return the new rounded date
   */
  public static Instant evenSecondDateAfterNow() {
    return evenSecondDateAfterNow(Clock.systemDefaultZone());
  }

  static Instant evenSecondDateAfterNow(Clock clock) {
    return evenSecondDate(null, clock);
  }

  /**
   * Returns a date that is rounded to the next even second above the given date.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenSecondDate(@Nullable Instant date) {
    return evenSecondDate(date, Clock.systemDefaultZone());
  }

  static Instant evenSecondDate(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    zdt = zdt.plusSeconds(1);
    return zdt.truncatedTo(ChronoUnit.SECONDS).toInstant();
  }

  /**
   * Returns a date that is rounded to the previous even second below the given date.
   *
   * <p>For example an input date with a time of 08:13:54.341 would result in a date with the time
   * of 08:13:54.000.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @return the new rounded date
   */
  public static Instant evenSecondDateBefore(@Nullable Instant date) {
    return evenSecondDateBefore(date, Clock.systemDefaultZone());
  }

  static Instant evenSecondDateBefore(@Nullable Instant date, Clock clock) {
    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);

    return zdt.truncatedTo(ChronoUnit.SECONDS).toInstant();
  }

  /**
   * Returns a date that is rounded to the next even multiple of the given minute.
   *
   * <p>For example an input date with a time of 08:13:54, and an input minute-base of 5 would
   * result in a date with the time of 08:15:00. The same input date with an input minute-base of 10
   * would result in a date with the time of 08:20:00. But a date with the time 08:53:31 and an
   * input minute-base of 45 would result in 09:00:00, because the even-hour is the next 'base' for
   * 45-minute intervals.
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
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @param minuteBase the base-minute to set the time on
   * @return the new rounded date
   * @see #nextGivenSecondDate(Instant, int)
   */
  public static Instant nextGivenMinuteDate(Instant date, int minuteBase) {
    return nextGivenMinuteDate(date, minuteBase, Clock.systemDefaultZone());
  }

  static Instant nextGivenMinuteDate(Instant date, int minuteBase, Clock clock) {
    if (minuteBase < 0 || minuteBase > 59) {
      throw new IllegalArgumentException("minuteBase must be >=0 and <= 59");
    }

    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);
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
   * Returns a date that is rounded to the next even multiple of the given second.
   *
   * <p>The rules for calculating the second are the same as those for calculating the minute in the
   * method <code>getNextGivenMinuteDate(..)</code>.
   *
   * @param date the instant to round, if <code>null</code> the current time will be used
   * @param secondBase the base-second to set the time on
   * @return the new rounded date
   * @see #nextGivenMinuteDate(Instant, int)
   */
  public static Instant nextGivenSecondDate(Instant date, int secondBase) {
    return nextGivenSecondDate(date, secondBase, Clock.systemDefaultZone());
  }

  static Instant nextGivenSecondDate(Instant date, int secondBase, Clock clock) {
    if (secondBase < 0 || secondBase > 59) {
      throw new IllegalArgumentException("secondBase must be >=0 and <= 59");
    }

    var zdt =
        (date == null) ? ZonedDateTime.now(clock) : ZonedDateTime.ofInstant(date, ZoneOffset.UTC);
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

  /**
   * Translate a date and time from a users time zone to the another (probably server) time zone to
   * assist in creating a simple trigger with the right date and time.
   *
   * @param date the date to translate
   * @param src the original time-zone
   * @param dest the destination time-zone
   * @return the translated date
   */
  public static Instant translateTime(Instant date, ZoneId src, ZoneId dest) {

    long offset =
        (dest.getRules().getOffset(date).getTotalSeconds()
                - src.getRules().getOffset(date).getTotalSeconds())
            * 1000L;

    return date.minusMillis(offset);
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
