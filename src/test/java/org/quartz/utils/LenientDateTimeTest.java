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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * {@link LenientDateTime} must reproduce the lenient {@code GregorianCalendar} semantics the scheduling
 * algorithms were written against (D-010). Random set/add/get sequences around DST transitions are run
 * on both and compared after every step.
 */
class LenientDateTimeTest {

    private static final String[] ZONES = {"UTC", "America/New_York", "Europe/London", "Australia/Lord_Howe",
            "Asia/Kolkata", "America/Sao_Paulo", "Pacific/Apia"};

    // transitions: US spring/fall, EU spring/fall, Lord Howe (30 min) fall/spring, Samoa skipped day,
    // Sao Paulo midnight DST
    private static final String[] ANCHORS = {"2024-03-10T07:00:00Z", "2024-11-03T06:00:00Z",
            "2024-03-31T01:00:00Z", "2024-10-27T01:00:00Z", "2024-04-06T15:00:00Z", "2024-10-05T15:30:00Z",
            "2011-12-30T10:00:00Z", "2018-11-04T03:00:00Z"};

    private static final int[] CAL_SET = {Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH,
            Calendar.HOUR_OF_DAY, Calendar.MINUTE, Calendar.SECOND, Calendar.MILLISECOND};
    private static final int[] LDT_SET = {LenientDateTime.YEAR, LenientDateTime.MONTH, LenientDateTime.DAY_OF_MONTH,
            LenientDateTime.HOUR_OF_DAY, LenientDateTime.MINUTE, LenientDateTime.SECOND, LenientDateTime.MILLISECOND};
    private static final int[] CAL_ADD = {Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.WEEK_OF_YEAR,
            Calendar.DAY_OF_YEAR, Calendar.HOUR_OF_DAY, Calendar.MINUTE, Calendar.SECOND, Calendar.MILLISECOND};
    private static final int[] LDT_ADD = {LenientDateTime.YEAR, LenientDateTime.MONTH, LenientDateTime.DAY_OF_MONTH,
            LenientDateTime.WEEK_OF_YEAR, LenientDateTime.DAY_OF_YEAR, LenientDateTime.HOUR_OF_DAY,
            LenientDateTime.MINUTE, LenientDateTime.SECOND, LenientDateTime.MILLISECOND};

    @Test
    void matchesLenientGregorianCalendar() {
        Random random = new Random(42);
        List<String> failures = new ArrayList<>();
        for (int iteration = 0; iteration < 30_000 && failures.size() < 10; iteration++) {
            String zone = ZONES[random.nextInt(ZONES.length)];
            long start = Instant.parse(ANCHORS[random.nextInt(ANCHORS.length)]).toEpochMilli()
                    + (long) (random.nextGaussian() * 3 * 86_400_000L);
            GregorianCalendar expected = new GregorianCalendar(TimeZone.getTimeZone(zone));
            expected.setTimeInMillis(start);
            LenientDateTime actual = new LenientDateTime(Instant.ofEpochMilli(start), ZoneId.of(zone));
            StringBuilder trace = new StringBuilder(zone).append(" start=").append(start);
            for (int step = 0; step < 6; step++) {
                switch (random.nextInt(3)) {
                    case 0: {
                        int i = random.nextInt(CAL_SET.length);
                        int value = randomFieldValue(random, i);
                        expected.set(CAL_SET[i], value);
                        actual.set(LDT_SET[i], value);
                        trace.append(" set[").append(i).append("]=").append(value);
                        break;
                    }
                    case 1: {
                        int i = random.nextInt(CAL_ADD.length);
                        int amount = random.nextInt(7) - 3;
                        expected.add(CAL_ADD[i], amount);
                        actual.add(LDT_ADD[i], amount);
                        trace.append(" add[").append(i).append("]=").append(amount);
                        break;
                    }
                    default: {
                        int i = random.nextInt(CAL_SET.length);
                        trace.append(" get[").append(i).append(']');
                        if (expected.get(CAL_SET[i]) != actual.get(LDT_SET[i])) {
                            failures.add("field " + i + ": " + trace);
                        }
                    }
                }
                if (expected.getTimeInMillis() != actual.toEpochMilli()
                        || expected.get(Calendar.DAY_OF_WEEK) != actual.get(LenientDateTime.DAY_OF_WEEK)) {
                    failures.add("time " + expected.getTimeInMillis() + " vs " + actual.toEpochMilli() + ": " + trace);
                    break;
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void resolvesGapsForwardAndOverlapsToTheLaterInstant() {
        ZoneId newYork = ZoneId.of("America/New_York");
        assertThat(LenientDateTime.resolve(LocalDateTime.parse("2024-03-10T02:30"), newYork))
                .isEqualTo(Instant.parse("2024-03-10T07:30:00Z"));
        assertThat(LenientDateTime.resolve(LocalDateTime.parse("2024-11-03T01:30"), newYork))
                .isEqualTo(Instant.parse("2024-11-03T06:30:00Z"));
    }

    private static int randomFieldValue(Random random, int index) {
        switch (index) {
            case 0:
                return 2023 + random.nextInt(3);
            case 1:
                return random.nextInt(14) - 1;
            case 2:
                return random.nextInt(33);
            case 3:
                return random.nextInt(26) - 1;
            case 4:
            case 5:
                return random.nextInt(62) - 1;
            default:
                return random.nextInt(1000);
        }
    }
}
