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
package org.quartz.impl.triggers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.spi.OperableTrigger;
import org.quartz.support.MutableClock;

/**
 * {@link OperableTrigger#bind(Clock, ZoneId)}: what the scheduler does to a trigger before it
 * stores it, and the store to every trigger it loads.
 */
class TriggerBindingTest {

  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

  private static final Instant NOW = Instant.parse("2026-03-10T08:00:00.123Z");

  private static OperableTrigger op(Trigger trigger) {
    return (OperableTrigger) trigger;
  }

  @Test
  void aTriggerWithoutAZoneResolvesInUtcUntilItIsBound() {
    CronTrigger cron =
        newTrigger()
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
            .startAt(NOW)
            .build();
    CalendarIntervalTrigger calendarInterval =
        newTrigger()
            .withSchedule(
                CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1))
            .startAt(NOW)
            .build();
    DailyTimeIntervalTrigger daily =
        newTrigger()
            .withSchedule(
                DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                    .withIntervalInHours(1))
            .startAt(NOW)
            .build();

    assertThat(cron.getTimeZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(calendarInterval.getTimeZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(daily.getTimeZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(cron.getFireTimeAfter(NOW)).isEqualTo(Instant.parse("2026-03-10T09:00:00Z"));
    assertThat(daily.getFireTimeAfter(NOW)).isEqualTo(Instant.parse("2026-03-10T09:00:00Z"));
  }

  @Test
  void bindingAppliesTheDefaultZoneToTriggersWithoutOneAndStoresIt() {
    CronTrigger cron =
        newTrigger()
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
            .startAt(NOW)
            .build();
    CalendarIntervalTrigger calendarInterval =
        newTrigger()
            .withSchedule(
                CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1))
            .startAt(NOW)
            .build();
    DailyTimeIntervalTrigger daily =
        newTrigger()
            .withSchedule(
                DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                    .withIntervalInHours(1))
            .startAt(NOW)
            .build();

    for (Trigger t : new Trigger[] {cron, calendarInterval, daily}) {
      op(t).bind(Clock.systemUTC(), KOLKATA);
    }

    assertThat(cron.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(calendarInterval.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(daily.getTimeZone()).isEqualTo(KOLKATA);
    // 09:00 Kolkata is 03:30 UTC: the next cron fire after 08:00 UTC (13:30 Kolkata) is
    // tomorrow's; the daily trigger repeats hourly from 09:00 Kolkata, so 14:00 Kolkata is next
    assertThat(cron.getFireTimeAfter(NOW)).isEqualTo(Instant.parse("2026-03-11T03:30:00Z"));
    assertThat(daily.getFireTimeAfter(NOW)).isEqualTo(Instant.parse("2026-03-10T08:30:00Z"));

    // binding again (the store, on load) keeps the zone
    for (Trigger t : new Trigger[] {cron, calendarInterval, daily}) {
      op(t).bind(Clock.systemUTC(), PARIS);
    }
    assertThat(cron.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(calendarInterval.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(daily.getTimeZone()).isEqualTo(KOLKATA);
  }

  @Test
  void bindingKeepsAnExplicitZone() {
    CronTrigger cron =
        newTrigger()
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0).inTimeZone(PARIS))
            .startAt(NOW)
            .build();
    CalendarIntervalTrigger calendarInterval =
        newTrigger()
            .withSchedule(
                CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                    .withIntervalInDays(1)
                    .inTimeZone(PARIS))
            .startAt(NOW)
            .build();
    DailyTimeIntervalTrigger daily =
        newTrigger()
            .withSchedule(
                DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                    .withIntervalInHours(1)
                    .inTimeZone(PARIS))
            .startAt(NOW)
            .build();

    for (Trigger t : new Trigger[] {cron, calendarInterval, daily}) {
      op(t).bind(Clock.systemUTC(), KOLKATA);
    }

    assertThat(cron.getTimeZone()).isEqualTo(PARIS);
    assertThat(calendarInterval.getTimeZone()).isEqualTo(PARIS);
    assertThat(daily.getTimeZone()).isEqualTo(PARIS);
  }

  @Test
  void theScheduleBuilderOfABoundTriggerCarriesItsZone() {
    DailyTimeIntervalTrigger daily =
        newTrigger()
            .withSchedule(
                DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                    .withIntervalInHours(1))
            .startAt(NOW)
            .build();
    CalendarIntervalTrigger calendarInterval =
        newTrigger()
            .withSchedule(
                CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1))
            .startAt(NOW)
            .build();
    op(daily).bind(Clock.systemUTC(), KOLKATA);
    op(calendarInterval).bind(Clock.systemUTC(), KOLKATA);

    DailyTimeIntervalTrigger dailyCopy = daily.getTriggerBuilder().build();
    CalendarIntervalTrigger calendarCopy = calendarInterval.getTriggerBuilder().build();

    assertThat(dailyCopy.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(calendarCopy.getTimeZone()).isEqualTo(KOLKATA);
  }

  @Test
  void aCronScheduleBuilderCanBeReusedAfterOneOfItsTriggersWasBound() {
    CronScheduleBuilder schedule = CronScheduleBuilder.dailyAtHourAndMinute(9, 0);
    CronTrigger first = newTrigger().withSchedule(schedule).startAt(NOW).build();
    op(first).bind(Clock.systemUTC(), KOLKATA);

    CronTrigger second = newTrigger().withSchedule(schedule).startAt(NOW).build();
    op(second).bind(Clock.systemUTC(), PARIS);

    assertThat(first.getTimeZone()).isEqualTo(KOLKATA);
    assertThat(second.getTimeZone()).isEqualTo(PARIS);
  }

  @Test
  void aCronExpressionWithAZoneWinsOverTheTriggersAndOneWithoutTakesIt() throws Exception {
    CronTriggerImpl trigger = new CronTriggerImpl();
    trigger.setTimeZone(PARIS);

    CronExpression withZone = new CronExpression("0 0 9 * * ?");
    withZone.setTimeZone(KOLKATA);
    trigger.setCronExpression(withZone);
    assertThat(trigger.getTimeZone()).isEqualTo(KOLKATA);

    trigger.setCronExpression(new CronExpression("0 0 10 * * ?"));
    assertThat(trigger.getTimeZone()).isEqualTo(KOLKATA);

    // a String expression keeps the trigger's zone; unsetting the zone makes it the default's
    trigger.setCronExpression("0 0 11 * * ?");
    assertThat(trigger.getTimeZone()).isEqualTo(KOLKATA);
    trigger.setTimeZone(null);
    assertThat(trigger.getTimeZone()).isEqualTo(ZoneOffset.UTC);
    trigger.bind(Clock.systemUTC(), PARIS);
    assertThat(trigger.getTimeZone()).isEqualTo(PARIS);
  }

  @Test
  void bindingSetsAMissingStartTimeFromTheClockAndKeepsAGivenOne() {
    MutableClock clock = new MutableClock(NOW);
    SimpleTrigger withoutStart =
        newTrigger().withSchedule(SimpleScheduleBuilder.simpleSchedule()).build();
    Trigger startNow = newTrigger().startNow().build();
    Trigger withStart = newTrigger().startAt(NOW.plusSeconds(60)).build();

    assertThat(withoutStart.getStartTime()).isNull();
    assertThat(startNow.getStartTime()).isNull();

    op(withoutStart).bind(clock, ZoneOffset.UTC);
    op(startNow).bind(clock, ZoneOffset.UTC);
    op(withStart).bind(clock, ZoneOffset.UTC);

    assertThat(withoutStart.getStartTime()).isEqualTo(NOW);
    assertThat(startNow.getStartTime()).isEqualTo(NOW);
    assertThat(withStart.getStartTime()).isEqualTo(NOW.plusSeconds(60));

    clock.advance(Duration.ofHours(1));
    op(withoutStart).bind(clock, ZoneOffset.UTC);
    assertThat(withoutStart.getStartTime()).as("binding again keeps the start").isEqualTo(NOW);
  }

  @Test
  void fireTimeArithmeticNeedsAStartTime() {
    SimpleTrigger trigger =
        newTrigger().withIdentity("t").withSchedule(SimpleScheduleBuilder.simpleSchedule()).build();

    assertThatThrownBy(() -> op(trigger).computeFirstFireTime(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DEFAULT.t")
        .hasMessageContaining("startAt");
    assertThatThrownBy(() -> trigger.getFireTimeAfter(NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nowOfABoundTriggerIsItsClock() {
    MutableClock clock = new MutableClock(NOW);
    SimpleTrigger simple =
        newTrigger()
            .startAt(NOW.plus(Duration.ofMinutes(10)))
            .withSchedule(
                SimpleScheduleBuilder.simpleSchedule()
                    .withInterval(Duration.ofMinutes(10))
                    .repeatForever())
            .build();
    op(simple).bind(clock, ZoneOffset.UTC);

    assertThat(simple.getFireTimeAfter(null)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    clock.advance(Duration.ofMinutes(15));
    assertThat(simple.getFireTimeAfter(null)).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
  }

  @Test
  void aDailyTriggerCountsItsDaysOfWeekInItsZone() {
    // 2026-03-14 is a Saturday; 23:00 UTC on Friday the 13th is already Saturday in Kolkata
    DailyTimeIntervalTrigger daily =
        newTrigger()
            .startAt(Instant.parse("2026-03-13T22:00:00Z"))
            .withSchedule(
                DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .onSaturdayAndSunday()
                    .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(4, 30))
                    .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(5, 30))
                    .withIntervalInHours(1)
                    .inTimeZone(KOLKATA))
            .build();
    op(daily).bind(Clock.systemUTC(), ZoneOffset.UTC);

    assertThat(op(daily).computeFirstFireTime(null))
        .isEqualTo(ZonedDateTime.of(2026, 3, 14, 4, 30, 0, 0, KOLKATA).toInstant());
  }
}
