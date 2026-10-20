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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cron fire times across DST transitions (D-010): a wall time inside a spring-forward gap is skipped,
 * a wall time repeated by a fall-back fires once, at the later (standard-time) instant.
 */
class CronDstTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId LORD_HOWE = ZoneId.of("Australia/Lord_Howe");

    @Test
    void dailyFireTimeInsideTheSpringForwardGapIsSkipped() throws Exception {
        // 2024-03-10 02:00 EST -> 03:00 EDT: 02:30 does not exist that day
        assertThat(fireTimes("0 30 2 * * ?", NEW_YORK, "2024-03-09T06:00:00Z", 3)).containsExactly(
                Instant.parse("2024-03-09T07:30:00Z"),  // 02:30 EST
                Instant.parse("2024-03-11T06:30:00Z"),  // 02:30 EDT, 03-10 skipped
                Instant.parse("2024-03-12T06:30:00Z"));
    }

    @Test
    void dailyFireTimeInsideTheFallBackOverlapFiresOnceAtTheLaterInstant() throws Exception {
        // 2024-11-03 02:00 EDT -> 01:00 EST: 01:30 occurs twice (05:30Z and 06:30Z)
        assertThat(fireTimes("0 30 1 * * ?", NEW_YORK, "2024-11-02T04:00:00Z", 3)).containsExactly(
                Instant.parse("2024-11-02T05:30:00Z"),  // 01:30 EDT
                Instant.parse("2024-11-03T06:30:00Z"),  // 01:30 EST only
                Instant.parse("2024-11-04T06:30:00Z"));
    }

    @Test
    void hourlyScheduleAcrossBothTransitions() throws Exception {
        assertThat(fireTimes("0 0 * * * ?", NEW_YORK, "2024-03-10T05:30:00Z", 3)).containsExactly(
                Instant.parse("2024-03-10T06:00:00Z"),  // 01:00 EST
                Instant.parse("2024-03-10T07:00:00Z"),  // 03:00 EDT, there is no 02:00
                Instant.parse("2024-03-10T08:00:00Z"));
        assertThat(fireTimes("0 0 * * * ?", NEW_YORK, "2024-11-03T03:30:00Z", 4)).containsExactly(
                Instant.parse("2024-11-03T04:00:00Z"),  // 00:00 EDT
                Instant.parse("2024-11-03T06:00:00Z"),  // 01:00, the later (EST) occurrence
                Instant.parse("2024-11-03T07:00:00Z"),  // 02:00 EST
                Instant.parse("2024-11-03T08:00:00Z"));
    }

    @Test
    @Timeout(10)
    void halfHourlyScheduleAcrossTheThirtyMinuteGapTerminatesAndSkipsTheMissingTimes() throws Exception {
        // Australia/Lord_Howe: 2024-10-06 02:00 (+10:30) -> 02:30 (+11:00)
        List<Instant> times = fireTimes("0 */30 * * * ?", LORD_HOWE, "2024-10-05T14:00:00Z", 6);

        assertThat(times).isSorted().doesNotHaveDuplicates().hasSize(6);
        assertThat(times).first().isEqualTo(Instant.parse("2024-10-05T14:30:00Z")); // 01:00
        assertThat(times).extracting(t -> t.atZone(LORD_HOWE).toLocalTime())
                .doesNotContain(LocalTime.of(2, 0))
                .contains(LocalTime.of(1, 30), LocalTime.of(2, 30));
        // Known limitation kept from the original algorithm (golden fixtures): the 03:00 after the
        // gap is skipped as well, the schedule continues at 03:30 (D-010).
        assertThat(times.get(3)).isEqualTo(Instant.parse("2024-10-05T16:30:00Z"));
    }

    @Test
    void halfHourlyScheduleAcrossTheThirtyMinuteOverlap() throws Exception {
        // Australia/Lord_Howe: 2024-04-07 02:00 (+11:00) -> 01:30 (+10:30)
        assertThat(fireTimes("0 */30 * * * ?", LORD_HOWE, "2024-04-06T14:00:00Z", 4)).containsExactly(
                Instant.parse("2024-04-06T15:00:00Z"),  // 01:30, the later (+10:30) occurrence
                Instant.parse("2024-04-06T15:30:00Z"),  // 02:00
                Instant.parse("2024-04-06T16:00:00Z"),  // 02:30
                Instant.parse("2024-04-06T16:30:00Z"));
    }

    @Test
    void isSatisfiedByHonoursTheTimeZone() throws Exception {
        CronExpression expression = new CronExpression("0 30 1 * * ?");
        expression.setTimeZone(NEW_YORK);

        assertThat(expression.isSatisfiedBy(Instant.parse("2024-11-03T06:30:00Z"))).isTrue();
        assertThat(expression.isSatisfiedBy(Instant.parse("2024-11-03T06:30:00Z").plus(Duration.ofHours(1)))).isFalse();
    }

    private static List<Instant> fireTimes(String cron, ZoneId zone, String after, int count) throws Exception {
        CronExpression expression = new CronExpression(cron);
        expression.setTimeZone(zone);
        List<Instant> times = new ArrayList<>();
        Instant t = Instant.parse(after);
        for (int i = 0; i < count; i++) {
            t = expression.getTimeAfter(t);
            times.add(t);
        }
        return times;
    }
}
