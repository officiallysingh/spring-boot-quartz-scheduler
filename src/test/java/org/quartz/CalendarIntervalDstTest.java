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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.quartz.DateBuilder.IntervalUnit;
import org.quartz.impl.triggers.CalendarIntervalTriggerImpl;

/**
 * Calendar-interval fire times across DST transitions, with and without
 * {@code preserveHourOfDayAcrossDaylightSavings} / {@code skipDayIfHourDoesNotExist}. The expected values are
 * the original Quartz semantics (golden fixtures, D-010).
 */
class CalendarIntervalDstTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void dailyIntervalWithoutPreservingTheHourShiftsAcrossTheSpringGap() {
        // adding a day to 03-09 02:30 lands in the gap; the wall time moves back and stays at 01:30
        assertThat(fireTimes(IntervalUnit.DAY, NEW_YORK, "2024-03-08T02:30", false, false, 4)).containsExactly(
                Instant.parse("2024-03-08T07:30:00Z"),
                Instant.parse("2024-03-09T07:30:00Z"),
                Instant.parse("2024-03-10T06:30:00Z"),  // 01:30 EST
                Instant.parse("2024-03-11T05:30:00Z")); // 01:30 EDT
    }

    @Test
    void dailyIntervalPreservingTheHourUsesTheNextExistingHourOnTheGapDay() {
        assertThat(fireTimes(IntervalUnit.DAY, NEW_YORK, "2024-03-08T02:30", true, false, 4)).containsExactly(
                Instant.parse("2024-03-08T07:30:00Z"),
                Instant.parse("2024-03-09T07:30:00Z"),
                Instant.parse("2024-03-10T07:30:00Z"),  // 03:30 EDT
                Instant.parse("2024-03-11T06:30:00Z")); // back to 02:30
    }

    @Test
    void dailyIntervalPreservingTheHourCanSkipTheGapDay() {
        assertThat(fireTimes(IntervalUnit.DAY, NEW_YORK, "2024-03-08T02:30", true, true, 4)).containsExactly(
                Instant.parse("2024-03-08T07:30:00Z"),
                Instant.parse("2024-03-09T07:30:00Z"),
                Instant.parse("2024-03-11T06:30:00Z"),  // 03-10 skipped
                Instant.parse("2024-03-12T06:30:00Z"));
    }

    @Test
    void dailyIntervalAcrossTheFallBackOverlapKeepsTheWallTime() {
        List<Instant> expected = List.of(
                Instant.parse("2024-11-01T05:30:00Z"),
                Instant.parse("2024-11-02T05:30:00Z"),
                Instant.parse("2024-11-03T05:30:00Z"),  // 01:30 EDT, the first occurrence
                Instant.parse("2024-11-04T06:30:00Z")); // 01:30 EST
        assertThat(fireTimes(IntervalUnit.DAY, NEW_YORK, "2024-11-01T01:30", false, false, 4)).isEqualTo(expected);
        assertThat(fireTimes(IntervalUnit.DAY, NEW_YORK, "2024-11-01T01:30", true, false, 4)).isEqualTo(expected);
    }

    @Test
    void weeklyIntervalAcrossTheSpringGap() {
        // Europe/London: 2024-03-31 01:00 GMT -> 02:00 BST
        assertThat(fireTimes(IntervalUnit.WEEK, LONDON, "2024-03-24T01:30", false, false, 3)).containsExactly(
                Instant.parse("2024-03-24T01:30:00Z"),
                Instant.parse("2024-03-31T00:30:00Z"),  // 00:30 GMT
                Instant.parse("2024-04-06T23:30:00Z")); // 00:30 BST
        assertThat(fireTimes(IntervalUnit.WEEK, LONDON, "2024-03-24T01:30", true, true, 3)).containsExactly(
                Instant.parse("2024-03-24T01:30:00Z"),
                Instant.parse("2024-04-07T00:30:00Z"),  // 01:30 BST, 03-31 skipped
                Instant.parse("2024-04-14T00:30:00Z"));
    }

    @Test
    void monthlyIntervalKeepsTheWallTimeAcrossTheTransition() {
        assertThat(fireTimes(IntervalUnit.MONTH, LONDON, "2024-02-27T09:00", false, false, 3))
                .extracting(t -> t.atZone(LONDON).toLocalDateTime())
                .containsExactly(
                        LocalDateTime.parse("2024-02-27T09:00"),
                        LocalDateTime.parse("2024-03-27T09:00"),
                        LocalDateTime.parse("2024-04-27T09:00"));
    }

    @Test
    void hourlyIntervalCountsElapsedHoursThroughTheOverlap() {
        assertThat(fireTimes(IntervalUnit.HOUR, NEW_YORK, "2024-11-03T00:00", false, false, 4)).containsExactly(
                Instant.parse("2024-11-03T04:00:00Z"),
                Instant.parse("2024-11-03T05:00:00Z"),  // 01:00 EDT
                Instant.parse("2024-11-03T06:00:00Z"),  // 01:00 EST
                Instant.parse("2024-11-03T07:00:00Z"));
    }

    private static List<Instant> fireTimes(IntervalUnit unit, ZoneId zone, String localStart,
                                           boolean preserveHour, boolean skipDay, int count) {
        CalendarIntervalTriggerImpl trigger = new CalendarIntervalTriggerImpl();
        trigger.setName("dst");
        trigger.setStartTime(LocalDateTime.parse(localStart).atZone(zone).toInstant());
        trigger.setRepeatIntervalUnit(unit);
        trigger.setRepeatInterval(1);
        trigger.setTimeZone(zone);
        trigger.setPreserveHourOfDayAcrossDaylightSavings(preserveHour);
        trigger.setSkipDayIfHourDoesNotExist(skipDay);
        return TriggerUtils.computeFireTimes(trigger, null, count);
    }
}
