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
package org.quartz.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.calendar.BaseCalendar;
import org.quartz.impl.calendar.DailyCalendar;
import org.quartz.impl.calendar.HolidayCalendar;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.MutableClock;
import org.quartz.support.TestSchedulers;

/**
 * The scheduler's default time zone ({@link SchedulerSettings#timeZone()}) and clock reach the
 * triggers it schedules, and the store keeps what was resolved.
 */
class SchedulerTimeZoneIT extends AbstractMongoIT {

  private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

  private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

  private static final Instant NOW = Instant.parse("2026-03-10T08:00:00.123Z");

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  private static Scheduler createScheduler(MutableClock clock, ZoneId timeZone)
      throws SchedulerException {
    return TestSchedulers.factory(
            SchedulerSettings.builder().name("TimeZoneTest").timeZone(timeZone), 1)
        .clock(clock)
        .build()
        .getScheduler();
  }

  @Test
  void theDefaultZoneIsUtcAndTheSchedulerExposesItsClockAndZone() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler scheduler =
        TestSchedulers.factory(SchedulerSettings.builder().name("TimeZoneTest"), 1)
            .clock(clock)
            .build()
            .getScheduler();
    try {
      assertThat(scheduler.getTimeZone()).isEqualTo(ZoneId.of("UTC"));
      assertThat(scheduler.getClock()).isSameAs(clock);
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void schedulingResolvesTheStartTimeByTheClockAndTheZoneByTheSettings() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler scheduler = createScheduler(clock, KOLKATA);
    try {
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job").storeDurably().build();
      scheduler.addJob(job, false);

      Trigger cron =
          newTrigger()
              .withIdentity("cron")
              .forJob(job)
              .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
              .build();
      Trigger calendarInterval =
          newTrigger()
              .withIdentity("calint")
              .forJob(job)
              .withSchedule(
                  CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1))
              .build();
      Trigger daily =
          newTrigger()
              .withIdentity("daily")
              .forJob(job)
              .withSchedule(
                  DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                      .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                      .withIntervalInHours(1))
              .build();
      Trigger explicit =
          newTrigger()
              .withIdentity("paris")
              .forJob(job)
              .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0).inTimeZone(PARIS))
              .build();

      // 09:00 Kolkata is 03:30 UTC: the first cron fire time after NOW (08:00 UTC, 13:30 Kolkata)
      // is tomorrow's; the daily trigger repeats hourly from 09:00 Kolkata, so 14:00 Kolkata is
      // next
      Instant tomorrowNineKolkata = ZonedDateTime.of(2026, 3, 11, 9, 0, 0, 0, KOLKATA).toInstant();
      assertThat(scheduler.scheduleJob(cron)).isEqualTo(tomorrowNineKolkata);
      assertThat(scheduler.scheduleJob(calendarInterval)).isEqualTo(NOW);
      assertThat(scheduler.scheduleJob(daily))
          .isEqualTo(ZonedDateTime.of(2026, 3, 10, 14, 0, 0, 0, KOLKATA).toInstant());
      // 09:00 Paris is 08:00 UTC: today's, as a cron start time is rounded down to the second
      assertThat(scheduler.scheduleJob(explicit))
          .isEqualTo(ZonedDateTime.of(2026, 3, 10, 9, 0, 0, 0, PARIS).toInstant());

      // the caller's instances were resolved in place (a cron start time is whole seconds)
      assertThat(cron.getStartTime()).isEqualTo(NOW.truncatedTo(ChronoUnit.SECONDS));
      assertThat(((CronTrigger) cron).getTimeZone()).isEqualTo(KOLKATA);

      // and the store kept the resolved values
      CronTrigger storedCron = (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey("cron"));
      CalendarIntervalTrigger storedCalendarInterval =
          (CalendarIntervalTrigger) scheduler.getTrigger(TriggerKey.triggerKey("calint"));
      DailyTimeIntervalTrigger storedDaily =
          (DailyTimeIntervalTrigger) scheduler.getTrigger(TriggerKey.triggerKey("daily"));
      CronTrigger storedExplicit =
          (CronTrigger) scheduler.getTrigger(TriggerKey.triggerKey("paris"));
      assertThat(storedCron.getStartTime()).isEqualTo(NOW.truncatedTo(ChronoUnit.SECONDS));
      assertThat(storedCron.getTimeZone()).isEqualTo(KOLKATA);
      assertThat(storedCalendarInterval.getTimeZone()).isEqualTo(KOLKATA);
      assertThat(storedDaily.getTimeZone()).isEqualTo(KOLKATA);
      assertThat(storedDaily.getStartTime()).isEqualTo(NOW);
      assertThat(storedExplicit.getTimeZone()).isEqualTo(PARIS);
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void aLoadedTriggerTakesNowFromTheSchedulersClock() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler scheduler = createScheduler(clock, KOLKATA);
    try {
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job").storeDurably().build();
      scheduler.addJob(job, false);
      scheduler.scheduleJob(
          newTrigger()
              .withIdentity("cron")
              .forJob(job)
              .withSchedule(CronScheduleBuilder.cronSchedule("0 0 * * * ?"))
              .build());

      // "every hour on the hour" in Kolkata (+05:30) is at half past each UTC hour
      Trigger loaded = scheduler.getTrigger(TriggerKey.triggerKey("cron"));
      assertThat(loaded.getFireTimeAfter(null)).isEqualTo(Instant.parse("2026-03-10T08:30:00Z"));

      clock.advance(Duration.ofHours(5));
      loaded = scheduler.getTrigger(TriggerKey.triggerKey("cron"));
      assertThat(loaded.getFireTimeAfter(null)).isEqualTo(Instant.parse("2026-03-10T13:30:00Z"));
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void reschedulingAndManualFiresUseTheSchedulersClockAndZone() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler scheduler = createScheduler(clock, KOLKATA);
    try {
      JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job").storeDurably().build();
      scheduler.scheduleJob(
          job,
          newTrigger()
              .withIdentity("t")
              .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
              .build());

      clock.advance(Duration.ofMinutes(30));
      Trigger replacement =
          newTrigger()
              .withIdentity("t")
              .withSchedule(
                  DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                      .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(10, 0))
                      .withIntervalInHours(1))
              .build();
      scheduler.rescheduleJob(TriggerKey.triggerKey("t"), replacement);

      DailyTimeIntervalTrigger stored =
          (DailyTimeIntervalTrigger) scheduler.getTrigger(TriggerKey.triggerKey("t"));
      assertThat(stored.getStartTime()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
      assertThat(stored.getTimeZone()).isEqualTo(KOLKATA);

      // a manual fire starts "now" by the clock
      clock.advance(Duration.ofMinutes(30));
      scheduler.triggerJob(job.getKey());
      SimpleTrigger manual =
          (SimpleTrigger)
              scheduler.getTriggersOfJob(job.getKey()).stream()
                  .filter(t -> Scheduler.MANUAL_TRIGGER_GROUP.equals(t.getKey().getGroup()))
                  .findFirst()
                  .orElseThrow();
      assertThat(manual.getStartTime()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void addingACalendarAppliesTheDefaultZoneAndStoresIt() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler first = createScheduler(clock, KOLKATA);
    try {
      HolidayCalendar holidays = new HolidayCalendar();
      DailyCalendar maintenance = new DailyCalendar(holidays, "01:00", "03:00");
      first.addCalendar("maintenance", maintenance, false, false);
      DailyCalendar paris = new DailyCalendar("01:00", "03:00");
      paris.setTimeZone(PARIS);
      first.addCalendar("paris", paris, false, false);

      // bound in place, base calendar included
      assertThat(maintenance.getTimeZone()).isEqualTo(KOLKATA);
      assertThat(holidays.getTimeZone()).isEqualTo(KOLKATA);

      DailyCalendar stored = (DailyCalendar) first.getCalendar("maintenance");
      assertThat(stored.getTimeZone()).isEqualTo(KOLKATA);
      assertThat(((BaseCalendar) stored.getBaseCalendar()).getTimeZone()).isEqualTo(KOLKATA);
      // 02:00 UTC is 07:30 in Kolkata: outside the excluded window
      assertThat(stored.isTimeIncluded(Instant.parse("2026-03-10T02:00:00Z"))).isTrue();
      assertThat(((BaseCalendar) first.getCalendar("paris")).getTimeZone()).isEqualTo(PARIS);
    } finally {
      first.shutdown();
    }

    Scheduler second = createScheduler(clock, PARIS);
    try {
      assertThat(((BaseCalendar) second.getCalendar("maintenance")).getTimeZone())
          .isEqualTo(KOLKATA);
    } finally {
      second.shutdown();
    }
  }

  @Test
  void aStoredZoneSurvivesASchedulerWithAnotherDefault() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    Scheduler first = createScheduler(clock, KOLKATA);
    try {
      first.scheduleJob(
          JobBuilder.newJob(NoOpJob.class).withIdentity("job").build(),
          newTrigger()
              .withIdentity("cron")
              .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(9, 0))
              .build());
    } finally {
      first.shutdown();
    }

    Scheduler second = createScheduler(clock, PARIS);
    try {
      assertThat(second.getTimeZone()).isEqualTo(PARIS);
      CronTrigger loaded = (CronTrigger) second.getTrigger(TriggerKey.triggerKey("cron"));
      assertThat(loaded.getTimeZone()).isEqualTo(KOLKATA);
    } finally {
      second.shutdown();
    }
  }
}
