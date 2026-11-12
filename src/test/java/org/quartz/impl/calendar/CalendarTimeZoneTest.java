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
package org.quartz.impl.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseCalendar#bind(ZoneId)}: what the scheduler does to a calendar when it is added, and
 * the store to every calendar it loads. A calendar without a zone resolves in UTC until then, never
 * in the JVM default zone.
 */
class CalendarTimeZoneTest {

  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

  // 02:00 UTC on 10 March 2026 is 07:30 in Kolkata
  private static final Instant TWO_UTC = Instant.parse("2026-03-10T02:00:00Z");

  @Test
  void aCalendarWithoutAZoneResolvesInUtcUntilItIsBound() {
    DailyCalendar maintenance = new DailyCalendar("01:00", "03:00"); // excluded window

    assertThat(maintenance.getTimeZone()).isNull();
    assertThat(maintenance.isTimeIncluded(TWO_UTC)).as("02:00 UTC is in the window").isFalse();

    maintenance.bind(KOLKATA);

    assertThat(maintenance.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(maintenance.isTimeIncluded(TWO_UTC)).as("07:30 Kolkata is outside").isTrue();
  }

  @Test
  void bindingKeepsAnExplicitZoneAndBindsOnlyOnce() {
    DailyCalendar maintenance = new DailyCalendar("01:00", "03:00");
    maintenance.setTimeZone(PARIS);
    maintenance.bind(KOLKATA);
    assertThat(maintenance.getTimeZone()).isEqualTo(PARIS);

    DailyCalendar withoutZone = new DailyCalendar("01:00", "03:00");
    withoutZone.bind(KOLKATA);
    withoutZone.bind(PARIS); // the store, on load
    assertThat(withoutZone.getTimeZone()).isEqualTo(KOLKATA);
  }

  @Test
  void bindingReachesTheBaseCalendars() {
    HolidayCalendar holidays = new HolidayCalendar();
    holidays.addExcludedDate(LocalDate.of(2026, 3, 10).atStartOfDay(ZoneOffset.UTC).toInstant());
    WeeklyCalendar weekends = new WeeklyCalendar(holidays);
    DailyCalendar top = new DailyCalendar(weekends, "01:00", "03:00");

    top.bind(KOLKATA);

    assertThat(top.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(weekends.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(holidays.getTimeZone()).isEqualTo(KOLKATA);
  }

  @Test
  void aCronCalendarIsBoundThroughItsExpression() throws Exception {
    CronCalendar nights = new CronCalendar("* * 22-23,0-5 ? * *"); // excluded hours

    assertThat(nights.getTimeZone()).isNull();
    assertThat(nights.getCronExpression().getTimeZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(nights.isTimeIncluded(TWO_UTC)).as("02:00 UTC is a night hour").isFalse();

    nights.bind(KOLKATA);

    assertThat(nights.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(nights.getCronExpression().getTimeZone()).isEqualTo(KOLKATA);
    assertThat(nights.isTimeIncluded(TWO_UTC)).as("07:30 Kolkata is a day hour").isTrue();

    CronCalendar explicit = new CronCalendar(null, "* * 22-23,0-5 ? * *", PARIS);
    explicit.bind(KOLKATA);
    assertThat(explicit.getTimeZone()).isEqualTo(PARIS);
  }

  @Test
  void aHolidayIsADayOfTheCalendarsZone() {
    HolidayCalendar holidays = new HolidayCalendar();
    // the 10th in Kolkata starts at 18:30 UTC on the 9th
    holidays.addExcludedDate(Instant.parse("2026-03-09T20:00:00Z"));

    assertThat(holidays.isTimeIncluded(Instant.parse("2026-03-09T21:00:00Z")))
        .as("same UTC day as the excluded instant")
        .isFalse();
    assertThat(holidays.isTimeIncluded(Instant.parse("2026-03-10T01:00:00Z"))).isTrue();

    HolidayCalendar kolkata = new HolidayCalendar();
    kolkata.bind(KOLKATA);
    kolkata.addExcludedDate(Instant.parse("2026-03-09T20:00:00Z"));

    assertThat(kolkata.isTimeIncluded(Instant.parse("2026-03-10T01:00:00Z")))
        .as("still the 10th in Kolkata")
        .isFalse();
  }

  @Test
  void bindingRejectsANullZone() {
    assertThatThrownBy(() -> new HolidayCalendar().bind(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("defaultTimeZone");
  }
}
