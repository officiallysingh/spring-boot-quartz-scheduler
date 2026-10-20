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
package org.quartz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.quartz.DateBuilder.IntervalUnit;
import org.quartz.impl.calendar.AnnualCalendar;
import org.quartz.impl.calendar.CronCalendar;
import org.quartz.impl.calendar.DailyCalendar;
import org.quartz.impl.calendar.HolidayCalendar;
import org.quartz.impl.calendar.MonthlyCalendar;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.impl.triggers.CalendarIntervalTriggerImpl;
import org.quartz.impl.triggers.DailyTimeIntervalTriggerImpl;
import org.quartz.impl.triggers.SimpleTriggerImpl;

/**
 * Replays scheduling results recorded from the original {@code java.util.Date}/{@code GregorianCalendar}
 * implementation (Phase 4, see DECISIONS D-010) so the java.time port provably keeps fire-time semantics.
 * Fixtures live in {@code src/test/resources/org/quartz/golden/*.tsv.gz}; all instants are epoch millis.
 * Rows recorded as {@code HANG} looped forever in the original code and are covered by the DST tests instead.
 * The fixtures were recorded with {@code -Duser.timezone=UTC} and the {@code en_US} locale: the original
 * {@code getTimeBefore} for {@code W} expressions depended on the JVM defaults, so the replay pins them too.
 */
class GoldenBehaviourTest {

    private static final String HANG = "HANG";

    private static TimeZone originalZone;
    private static Locale originalLocale;

    @BeforeAll
    static void pinDefaults() {
        originalZone = TimeZone.getDefault();
        originalLocale = Locale.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);
    }

    @AfterAll
    static void restoreDefaults() {
        TimeZone.setDefault(originalZone);
        Locale.setDefault(originalLocale);
    }

    @Test
    void cronExpressionFireTimes() throws Exception {
        assertNoMismatches("cron.tsv.gz", r -> {
            CronExpression ce = new CronExpression(r[0]);
            ce.setTimeZone(TimeZone.getTimeZone(r[1]));
            Date start = new Date(Long.parseLong(r[2]));
            List<String> diffs = new ArrayList<>();
            if (!HANG.equals(r[3])) {
                List<Date> out = new ArrayList<>();
                Date t = start;
                for (int i = 0; i < 8; i++) {
                    t = ce.getTimeAfter(t);
                    if (t == null) {
                        break;
                    }
                    out.add(t);
                }
                diffs.add(compare("next", r[3], join(out)));
            }
            if (!HANG.equals(r[4])) {
                diffs.add(compare("before", r[4], ms(ce.getTimeBefore(start))));
            }
            return String.join("", diffs);
        });
    }

    @Test
    void calendarIntervalTriggerFireTimes() throws Exception {
        assertNoMismatches("calendar-interval.tsv.gz", r -> {
            CalendarIntervalTriggerImpl t = new CalendarIntervalTriggerImpl();
            t.setName("g");
            t.setStartTime(new Date(Long.parseLong(r[3])));
            t.setRepeatIntervalUnit(IntervalUnit.valueOf(r[0]));
            t.setRepeatInterval(Integer.parseInt(r[1]));
            t.setTimeZone(TimeZone.getTimeZone(r[2]));
            t.setPreserveHourOfDayAcrossDaylightSavings(Boolean.parseBoolean(r[4]));
            t.setSkipDayIfHourDoesNotExist(Boolean.parseBoolean(r[5]));
            return compare("fire", r[6], join(TriggerUtils.computeFireTimes(t, null, 10)));
        });
    }

    @Test
    void dailyTimeIntervalTriggerFireTimes() throws Exception {
        try {
            assertNoMismatches("daily-time-interval.tsv.gz", r -> {
                TimeZone.setDefault(TimeZone.getTimeZone(r[0]));
                DailyTimeIntervalTriggerImpl t = new DailyTimeIntervalTriggerImpl();
                t.setName("g");
                t.setStartTime(new Date(Long.parseLong(r[1])));
                t.setStartTimeOfDay(timeOfDay(r[2]));
                t.setEndTimeOfDay(timeOfDay(r[3]));
                t.setRepeatIntervalUnit(IntervalUnit.valueOf(r[4]));
                t.setRepeatInterval(Integer.parseInt(r[5]));
                if (!r[6].isEmpty()) {
                    Set<Integer> days = new HashSet<>();
                    for (String d : r[6].split(",")) {
                        days.add(Integer.parseInt(d));
                    }
                    t.setDaysOfWeek(days);
                }
                t.setRepeatCount(Integer.parseInt(r[7]));
                return compare("fire", r[8], join(TriggerUtils.computeFireTimes(t, null, 15)));
            });
        } finally {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        }
    }

    @Test
    void simpleTriggerFireTimes() throws Exception {
        assertNoMismatches("simple.tsv.gz", r -> {
            SimpleTriggerImpl t = new SimpleTriggerImpl();
            t.setName("g");
            t.setStartTime(new Date(Long.parseLong(r[0])));
            if (!"null".equals(r[1])) {
                t.setEndTime(new Date(Long.parseLong(r[1])));
            }
            t.setRepeatInterval(Long.parseLong(r[2]));
            t.setRepeatCount(Integer.parseInt(r[3]));
            return compare("fire", r[4], join(TriggerUtils.computeFireTimes(t, null, 12)));
        });
    }

    @Test
    void calendarInclusion() throws Exception {
        assertNoMismatches("calendars.tsv.gz", r -> {
            Calendar cal = calendar(r[0], r[1]);
            long t = Long.parseLong(r[2]);
            return compare("included", r[3], Boolean.toString(cal.isTimeIncluded(t)))
                    + compare("next", r[4], Long.toString(cal.getNextIncludedTime(t)));
        });
    }

    // the calendar configurations the fixtures were recorded with
    private static Calendar calendar(String name, String zone) {
        TimeZone tz = TimeZone.getTimeZone(zone);
        try {
            switch (name) {
                case "weekly":
                    return new WeeklyCalendar(tz);
                case "weekly-mon": {
                    WeeklyCalendar c = new WeeklyCalendar(tz);
                    c.setDayExcluded(java.util.Calendar.SATURDAY, false);
                    c.setDayExcluded(java.util.Calendar.SUNDAY, false);
                    c.setDayExcluded(java.util.Calendar.MONDAY, true);
                    return c;
                }
                case "monthly": {
                    MonthlyCalendar c = new MonthlyCalendar(tz);
                    c.setDayExcluded(1, true);
                    c.setDayExcluded(15, true);
                    c.setDayExcluded(31, true);
                    return c;
                }
                case "annual": {
                    AnnualCalendar c = new AnnualCalendar(tz);
                    int[][] days = {{1, 1}, {7, 4}, {12, 25}, {2, 29}, {3, 10}, {11, 3}};
                    for (int[] d : days) {
                        java.util.Calendar day = new GregorianCalendar(tz);
                        day.clear();
                        day.set(2000, d[0] - 1, d[1]);
                        c.setDayExcluded(day, true);
                    }
                    return c;
                }
                case "holiday": {
                    HolidayCalendar c = new HolidayCalendar(tz);
                    c.addExcludedDate(localNoon(tz, 2024, 3, 10));
                    c.addExcludedDate(localNoon(tz, 2024, 11, 3));
                    c.addExcludedDate(localNoon(tz, 2024, 12, 25));
                    c.addExcludedDate(localNoon(tz, 2024, 3, 31));
                    return c;
                }
                case "daily": {
                    DailyCalendar c = new DailyCalendar("08:00", "17:00");
                    c.setTimeZone(tz);
                    return c;
                }
                case "daily-inverted": {
                    DailyCalendar c = new DailyCalendar("08:00:00:000", "17:30:15:500");
                    c.setTimeZone(tz);
                    c.setInvertTimeRange(true);
                    return c;
                }
                case "daily-dst": {
                    DailyCalendar c = new DailyCalendar("01:30", "02:45");
                    c.setTimeZone(tz);
                    return c;
                }
                case "cron": {
                    CronCalendar c = new CronCalendar("* * 0-7,18-23 ? * *");
                    c.setTimeZone(tz);
                    return c;
                }
                case "weekly-on-holiday":
                    return new WeeklyCalendar(calendar("holiday", zone), tz);
                default:
                    throw new IllegalArgumentException(name);
            }
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Date localNoon(TimeZone tz, int year, int month, int day) {
        java.util.Calendar c = new GregorianCalendar(tz);
        c.clear();
        c.set(year, month - 1, day, 12, 0, 0);
        return c.getTime();
    }

    private static TimeOfDay timeOfDay(String hms) {
        String[] p = hms.split(":");
        return new TimeOfDay(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    private static String ms(Date d) {
        return d == null ? "null" : Long.toString(d.getTime());
    }

    private static String join(List<Date> dates) {
        List<String> parts = new ArrayList<>();
        for (Date d : dates) {
            parts.add(ms(d));
        }
        return String.join(",", parts);
    }

    private static String compare(String what, String expected, String actual) {
        return expected.equals(actual) ? "" : " " + what + ": expected [" + expected + "] but was [" + actual + "]";
    }

    private interface Row extends Function<String[], String> {
        String check(String[] row) throws Exception;

        @Override
        default String apply(String[] row) {
            try {
                return check(row);
            } catch (Exception e) {
                return " threw " + e;
            }
        }
    }

    private void assertNoMismatches(String fixture, Row row) throws IOException {
        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        try (InputStream in = getClass().getResourceAsStream("golden/" + fixture);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                rows++;
                String diff = row.apply(line.split("\t", -1));
                if (!diff.isEmpty()) {
                    mismatches.add(line.substring(0, Math.min(line.length(), 120)) + " ->" + diff);
                }
            }
        }
        assertThat(rows).as("rows in %s", fixture).isPositive();
        assertThat(mismatches).as("mismatches in %s (%d rows)", fixture, rows).isEmpty();
    }
}
