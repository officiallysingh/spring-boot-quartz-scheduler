/*
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
 */
package org.quartz.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A mutable, lenient, field-based date-time in a {@link ZoneId}, built on {@code java.time}.
 *
 * <p>Quartz's scheduling algorithms (cron, calendar-interval, daily-time-interval triggers, calendars)
 * were written against the lenient legacy Gregorian calendar of the JDK: fields are set one at a time, may overflow
 * (minute 60, day 32, month 12) and are normalized on the next read. This class reproduces exactly those
 * semantics so the algorithms keep their fire times (DECISIONS.md D-010):</p>
 * <ul>
 *   <li>{@link #set} stores a raw value; the next {@link #get} or {@link #toInstant} normalizes all fields,
 *   resolving the wall time with the zone's <em>standard</em> offset during a transition, i.e. a time in a
 *   DST gap moves forward by the gap and an ambiguous fall-back time takes the later instant.</li>
 *   <li>{@link #add} with a time field moves the instant; with a day field it keeps the wall-clock time
 *   across a DST change unless that would change the date; with {@code MONTH}/{@code YEAR} it pins the
 *   day of month to the end of a shorter month.</li>
 * </ul>
 *
 * <p>Field numbers deliberately follow the legacy calendar convention so ports stay line-for-line:
 * {@link #MONTH} is 0-based and {@link #DAY_OF_WEEK} runs from {@link #SUNDAY} (1) to
 * {@link #SATURDAY} (7). Not thread-safe; internal to Quartz.</p>
 */
public final class LenientDateTime {

    public static final int YEAR = 1;
    public static final int MONTH = 2;
    public static final int WEEK_OF_YEAR = 3;
    public static final int DATE = 5;
    public static final int DAY_OF_MONTH = 5;
    public static final int DAY_OF_YEAR = 6;
    public static final int DAY_OF_WEEK = 7;
    public static final int HOUR_OF_DAY = 11;
    public static final int MINUTE = 12;
    public static final int SECOND = 13;
    public static final int MILLISECOND = 14;

    public static final int SUNDAY = 1;
    public static final int MONDAY = 2;
    public static final int TUESDAY = 3;
    public static final int WEDNESDAY = 4;
    public static final int THURSDAY = 5;
    public static final int FRIDAY = 6;
    public static final int SATURDAY = 7;

    public static final int JANUARY = 0;
    public static final int DECEMBER = 11;

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final ZoneId zone;

    private Instant instant;
    private boolean dirty;

    // raw (possibly out-of-range while dirty) local fields; month is 0-based
    private int year;
    private int month;
    private int day;
    private int hour;
    private int minute;
    private int second;
    private int millis;

    public LenientDateTime(Instant instant, ZoneId zone) {
        this.zone = zone;
        setInstant(instant);
    }

    public ZoneId getZone() {
        return zone;
    }

    /** Sets the instant (millisecond precision, as with the legacy calendar) and recomputes all fields. */
    public void setInstant(Instant instant) {
        this.instant = instant.truncatedTo(ChronoUnit.MILLIS);
        computeFields();
    }

    public Instant toInstant() {
        complete();
        return instant;
    }

    public long toEpochMilli() {
        return toInstant().toEpochMilli();
    }

    /** The normalized wall-clock date-time. */
    public ZonedDateTime toZonedDateTime() {
        return toInstant().atZone(zone);
    }

    public int get(int field) {
        complete();
        switch (field) {
            case YEAR:
                return year;
            case MONTH:
                return month;
            case DAY_OF_MONTH:
                return day;
            case DAY_OF_YEAR:
                return localDate().getDayOfYear();
            case DAY_OF_WEEK:
                return localDate().getDayOfWeek().getValue() % 7 + 1;
            case HOUR_OF_DAY:
                return hour;
            case MINUTE:
                return minute;
            case SECOND:
                return second;
            case MILLISECOND:
                return millis;
            default:
                throw new IllegalArgumentException("Unsupported field: " + field);
        }
    }

    /** Stores a raw field value; it is normalized together with the other fields on the next read. */
    public void set(int field, int value) {
        switch (field) {
            case YEAR:
                year = value;
                break;
            case MONTH:
                month = value;
                break;
            case DAY_OF_MONTH:
                day = value;
                break;
            case HOUR_OF_DAY:
                hour = value;
                break;
            case MINUTE:
                minute = value;
                break;
            case SECOND:
                second = value;
                break;
            case MILLISECOND:
                millis = value;
                break;
            default:
                throw new IllegalArgumentException("Unsupported field: " + field);
        }
        dirty = true;
    }

    public void add(int field, int amount) {
        if (amount == 0) {
            return;
        }
        complete();
        switch (field) {
            case YEAR:
                set(YEAR, year + amount);
                pinDayOfMonth();
                return;
            case MONTH: {
                int m = month + amount;
                int yearAmount = m >= 0 ? m / 12 : (m + 1) / 12 - 1;
                if (yearAmount != 0) {
                    set(YEAR, year + yearAmount);
                }
                m %= 12;
                if (m < 0) {
                    m += 12;
                }
                set(MONTH, m);
                pinDayOfMonth();
                return;
            }
            case HOUR_OF_DAY:
                setInstant(instant.plusMillis(amount * 3_600_000L));
                return;
            case MINUTE:
                setInstant(instant.plusMillis(amount * 60_000L));
                return;
            case SECOND:
                setInstant(instant.plusMillis(amount * 1_000L));
                return;
            case MILLISECOND:
                setInstant(instant.plusMillis(amount));
                return;
            case WEEK_OF_YEAR:
                addDays(amount * 7L);
                return;
            case DAY_OF_MONTH:
            case DAY_OF_YEAR:
            case DAY_OF_WEEK:
                addDays(amount);
                return;
            default:
                throw new IllegalArgumentException("Unsupported field: " + field);
        }
    }

    public boolean isAfter(Instant other) {
        return toInstant().isAfter(other);
    }

    public boolean isBefore(Instant other) {
        return toInstant().isBefore(other);
    }

    /** Days are added in wall-clock terms, compensating a DST change unless that moves the date. */
    private void addDays(long days) {
        LocalDate targetDate = localDate().plusDays(days);
        long timeOfDay = ((hour * 60L + minute) * 60L + second) * 1000L + millis;
        ZoneOffset before = offsetAt(instant);
        Instant moved = Instant.ofEpochMilli(targetDate.toEpochDay() * MILLIS_PER_DAY + timeOfDay
                - before.getTotalSeconds() * 1000L);
        long offsetChange = (before.getTotalSeconds() - offsetAt(moved).getTotalSeconds()) * 1000L;
        if (offsetChange != 0) {
            Instant compensated = moved.plusMillis(offsetChange);
            if (compensated.atZone(zone).toLocalDate().equals(targetDate)) {
                moved = compensated;
            }
        }
        setInstant(moved);
    }

    private void pinDayOfMonth() {
        int monthLength = YearMonth.of(year, month + 1).lengthOfMonth();
        if (day > monthLength) {
            set(DAY_OF_MONTH, monthLength);
        }
    }

    private void complete() {
        if (!dirty) {
            return;
        }
        LocalDateTime local = LocalDateTime.of(year, 1, 1, 0, 0)
                .plusMonths(month)
                .plusDays(day - 1L)
                .plusHours(hour)
                .plusMinutes(minute)
                .plusSeconds(second)
                .plus(millis, ChronoUnit.MILLIS);
        instant = resolve(local, zone);
        computeFields();
    }

    /**
     * Resolves a wall-clock time the way the legacy lenient calendar does: a time in a gap moves forward
     * by the length of the gap, an ambiguous time takes the later (standard-time) instant.
     */
    public static Instant resolve(LocalDateTime local, ZoneId zone) {
        return ZonedDateTime.ofLocal(local, zone, null).withLaterOffsetAtOverlap().toInstant();
    }

    private void computeFields() {
        ZonedDateTime z = instant.atZone(zone);
        year = z.getYear();
        month = z.getMonthValue() - 1;
        day = z.getDayOfMonth();
        hour = z.getHour();
        minute = z.getMinute();
        second = z.getSecond();
        millis = z.getNano() / 1_000_000;
        dirty = false;
    }

    private LocalDate localDate() {
        return LocalDate.of(year, month + 1, day);
    }

    private ZoneOffset offsetAt(Instant i) {
        return zone.getRules().getOffset(i);
    }
}
