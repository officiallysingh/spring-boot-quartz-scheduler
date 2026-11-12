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
package org.quartz.impl.store.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobPersistenceException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TimeOfDay;
import org.quartz.TriggerBuilder;
import org.quartz.impl.calendar.AnnualCalendar;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.OperableTrigger;
import org.quartz.support.MutableClock;

class CodecsTest {

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  /** A custom trigger type: stored as a serialized blob. */
  public static class CustomTrigger extends SimpleTriggerImpl {
    private static final long serialVersionUID = 1L;
    public String extra = "custom";
  }

  private final ClassLoader classLoader = CodecsTest.class.getClassLoader();

  /** What a Redis hash gives back: every value as a string, binary values base64. */
  private static Map<String, Object> asStrings(Map<String, Object> fields) {
    Map<String, Object> strings = new LinkedHashMap<>();
    fields.forEach(
        (name, value) ->
            strings.put(
                name,
                (value instanceof byte[] bytes)
                    ? SerializationCodec.toBase64(bytes)
                    : value.toString()));
    return strings;
  }

  private static List<OperableTrigger> triggers() {
    Instant start = Instant.parse("2030-03-04T05:06:07Z");
    List<OperableTrigger> triggers = new ArrayList<>();
    triggers.add(
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("simple", "g")
                .forJob("job", "jg")
                .startAt(start)
                .endAt(start.plus(Duration.ofDays(3)))
                .withPriority(9)
                .withDescription("d")
                .modifiedByCalendar("cal")
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule()
                        .withInterval(Duration.ofMillis(1500))
                        .withRepeatCount(4)
                        .withMisfireHandlingInstructionNowWithRemainingCount())
                .build());
    triggers.add(
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("cron", "g")
                .forJob("job", "jg")
                .startAt(start)
                .withSchedule(
                    CronScheduleBuilder.cronSchedule("0 0/5 9-17 ? * MON-FRI")
                        .inTimeZone(ZoneId.of("Asia/Tokyo")))
                .build());
    triggers.add(
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("calint", "g")
                .forJob("job", "jg")
                .startAt(start)
                .withSchedule(
                    CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                        .withIntervalInWeeks(3)
                        .inTimeZone(ZoneId.of("Europe/Paris"))
                        .preserveHourOfDayAcrossDaylightSavings(true))
                .build());
    triggers.add(
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("daily", "g")
                .forJob("job", "jg")
                .startAt(start)
                .withSchedule(
                    DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                        .startingDailyAt(TimeOfDay.hourMinuteAndSecondOfDay(8, 15, 30))
                        .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(18, 0))
                        .onSaturdayAndSunday()
                        .withIntervalInHours(2)
                        .withRepeatCount(7)
                        .inTimeZone(ZoneId.of("Asia/Kolkata")))
                .build());
    triggers.forEach(t -> t.computeFirstFireTime(null));
    triggers.get(0).triggered(null);
    return triggers;
  }

  private static void assertSameTrigger(OperableTrigger decoded, OperableTrigger original) {
    assertThat(decoded.getClass()).isEqualTo(original.getClass());
    assertThat(decoded.getKey()).isEqualTo(original.getKey());
    assertThat(decoded.getJobKey()).isEqualTo(original.getJobKey());
    assertThat(decoded.getDescription()).isEqualTo(original.getDescription());
    assertThat(decoded.getCalendarName()).isEqualTo(original.getCalendarName());
    assertThat(decoded.getPriority()).isEqualTo(original.getPriority());
    assertThat(decoded.getMisfireInstruction()).isEqualTo(original.getMisfireInstruction());
    assertThat(decoded.getStartTime()).isEqualTo(original.getStartTime());
    assertThat(decoded.getEndTime()).isEqualTo(original.getEndTime());
    assertThat(decoded.getNextFireTime()).isEqualTo(original.getNextFireTime());
    assertThat(decoded.getPreviousFireTime()).isEqualTo(original.getPreviousFireTime());
    if (original instanceof CronTrigger cron) {
      assertThat(((CronTrigger) decoded).getTimeZone()).isEqualTo(cron.getTimeZone());
    } else if (original instanceof CalendarIntervalTrigger calendarInterval) {
      assertThat(((CalendarIntervalTrigger) decoded).getTimeZone())
          .isEqualTo(calendarInterval.getTimeZone());
    } else if (original instanceof DailyTimeIntervalTrigger daily) {
      assertThat(((DailyTimeIntervalTrigger) decoded).getTimeZone()).isEqualTo(daily.getTimeZone());
    }
    // the schedule itself: equal fire times from here on
    Instant after = original.getNextFireTime();
    for (int i = 0; i < 5 && after != null; i++) {
      assertThat(decoded.getFireTimeAfter(after)).isEqualTo(original.getFireTimeAfter(after));
      after = original.getFireTimeAfter(after);
    }
  }

  @Test
  void builtInTriggersRoundTripTypedAndAsStrings() throws Exception {
    for (OperableTrigger trigger : triggers()) {
      Map<String, Object> fields = TriggerCodec.encode(trigger);
      assertThat(fields).doesNotContainKey(TriggerCodec.BLOB);
      assertSameTrigger(
          TriggerCodec.decode(
              fields, new JobDataMap(), classLoader, Clock.systemUTC(), ZoneOffset.UTC),
          trigger);
      assertSameTrigger(
          TriggerCodec.decode(
              asStrings(fields), new JobDataMap(), classLoader, Clock.systemUTC(), ZoneOffset.UTC),
          trigger);
    }
  }

  @Test
  void zonesAreStoredWithEveryZonedTrigger() throws Exception {
    for (OperableTrigger trigger : triggers()) {
      Map<String, Object> fields = TriggerCodec.encode(trigger);
      if (trigger instanceof SimpleTriggerImpl) {
        assertThat(fields).doesNotContainKey(TriggerCodec.TIME_ZONE);
      } else {
        assertThat(fields).containsKey(TriggerCodec.TIME_ZONE);
      }
    }
    assertThat(TriggerCodec.encode(triggers().get(3)))
        .containsEntry(TriggerCodec.TIME_ZONE, "Asia/Kolkata");
  }

  @Test
  void decodingBindsTheSchedulersClockAndDefaultZone() throws Exception {
    MutableClock clock = new MutableClock(Instant.parse("2030-03-04T05:06:07Z"));
    ZoneId defaultZone = ZoneId.of("Europe/London");
    for (OperableTrigger trigger : triggers()) {
      Map<String, Object> fields = new LinkedHashMap<>(TriggerCodec.encode(trigger));
      fields.remove(TriggerCodec.TIME_ZONE); // a record without a zone (none is written so)

      OperableTrigger decoded =
          TriggerCodec.decode(fields, new JobDataMap(), classLoader, clock, defaultZone);

      if (decoded instanceof CronTrigger cron) {
        assertThat(cron.getTimeZone()).isEqualTo(defaultZone);
      } else if (decoded instanceof CalendarIntervalTrigger calendarInterval) {
        assertThat(calendarInterval.getTimeZone()).isEqualTo(defaultZone);
      } else if (decoded instanceof DailyTimeIntervalTrigger daily) {
        assertThat(daily.getTimeZone()).isEqualTo(defaultZone);
      }
      // "now" is the scheduler's clock: the first fire time after it, not after the system clock
      Instant afterNow = decoded.getFireTimeAfter(null);
      assertThat(afterNow).isEqualTo(decoded.getFireTimeAfter(clock.instant()));
    }
  }

  @Test
  void customTriggersAreStoredSerialized() throws Exception {
    CustomTrigger trigger = new CustomTrigger();
    trigger.setName("custom");
    trigger.setJobName("job");
    trigger.setStartTime(Instant.parse("2030-01-01T00:00:00Z"));
    trigger.extra = "kept";
    trigger.computeFirstFireTime(null);
    JobDataMap data = new JobDataMap(Map.of("k", "v"));

    Map<String, Object> fields = TriggerCodec.encode(trigger);
    assertThat(fields.get(TriggerCodec.TYPE)).isEqualTo(TriggerCodec.TYPE_BLOB);
    OperableTrigger decoded =
        TriggerCodec.decode(
            asStrings(fields), data, classLoader, Clock.systemUTC(), ZoneOffset.UTC);

    assertThat(decoded).isInstanceOf(CustomTrigger.class);
    assertThat(((CustomTrigger) decoded).extra).isEqualTo("kept");
    assertThat(decoded.getNextFireTime()).isEqualTo(trigger.getNextFireTime());
    assertThat(decoded.getJobDataMap().getString("k")).isEqualTo("v");
  }

  @Test
  void jobDetailRoundTrip() throws Exception {
    JobDetail job =
        JobBuilder.newJob(NoOpJob.class)
            .withIdentity("j", "g")
            .withDescription("desc")
            .storeDurably()
            .requestRecovery()
            .build();
    JobDataMap data = new JobDataMap(Map.of("a", 1));

    for (Map<String, Object> fields :
        List.of(JobDetailCodec.encode(job), asStrings(JobDetailCodec.encode(job)))) {
      JobDetail decoded = JobDetailCodec.decode(fields, data, classLoader);
      assertThat(decoded.getKey()).isEqualTo(job.getKey());
      assertThat(decoded.getJobClass()).isEqualTo(NoOpJob.class);
      assertThat(decoded.getDescription()).isEqualTo("desc");
      assertThat(decoded.isDurable()).isTrue();
      assertThat(decoded.requestsRecovery()).isTrue();
      assertThat(decoded.getJobDataMap().getInt("a")).isEqualTo(1);
    }
    assertThatThrownBy(
            () ->
                JobDetailCodec.decode(
                    Map.of(
                        JobDetailCodec.JOB_CLASS,
                        "com.example.Missing",
                        JobDetailCodec.NAME,
                        "n",
                        JobDetailCodec.GROUP,
                        "g"),
                    null,
                    classLoader))
        .isInstanceOf(JobPersistenceException.class);
  }

  @Test
  void jobDataIsNativeOnlyForSimpleValuesAndPlainKeys() {
    assertThat(
            JobDataCodec.isNative(
                new JobDataMap(Map.of("s", "x", "i", 1, "l", 2L, "d", 1.5d, "b", true))))
        .isTrue();
    assertThat(JobDataCodec.isNative(new JobDataMap(Map.of("list", new ArrayList<>())))).isFalse();
    assertThat(JobDataCodec.isNative(new JobDataMap(Map.of("f", 1.5f)))).isFalse();
    assertThat(JobDataCodec.isNative(new JobDataMap(Map.of("a.b", "x")))).isFalse();
    assertThat(JobDataCodec.isNative(new JobDataMap(Map.of("$x", "x")))).isFalse();
  }

  @Test
  void taggedValuesRoundTrip() {
    for (Object value : List.of("text:with:colons", 42, 7L, 2.5d, true)) {
      assertThat(JobDataCodec.untag(JobDataCodec.tag(value))).isEqualTo(value);
    }
  }

  @Test
  void serializedJobDataRoundTrip() throws Exception {
    JobDataMap data = new JobDataMap();
    data.put("list", new ArrayList<>(List.of(1, 2)));
    data.put("float", 1.5f);

    JobDataMap decoded = JobDataCodec.deserialize(JobDataCodec.serialize(data), classLoader);
    assertThat(decoded.get("list")).isEqualTo(List.of(1, 2));
    assertThat(decoded.get("float")).isEqualTo(1.5f);
    assertThat(decoded.isDirty()).isFalse();
  }

  @Test
  void usePropertiesRejectsNonStringValues() throws Exception {
    JobDataCodec.checkUseProperties(new JobDataMap(Map.of("a", "b")), true);
    JobDataCodec.checkUseProperties(new JobDataMap(Map.of("a", 1)), false);
    assertThatThrownBy(() -> JobDataCodec.checkUseProperties(new JobDataMap(Map.of("a", 1)), true))
        .isInstanceOf(JobPersistenceException.class)
        .hasMessageContaining("useProperties");
  }

  @Test
  void calendarsAreSerialized() throws Exception {
    AnnualCalendar calendar = new AnnualCalendar();
    calendar.setDescription("holidays");

    AnnualCalendar decoded =
        SerializationCodec.deserialize(
            SerializationCodec.serialize(calendar), AnnualCalendar.class, classLoader);
    assertThat(decoded.getDescription()).isEqualTo("holidays");
    assertThatThrownBy(() -> SerializationCodec.serialize(new Object()))
        .isInstanceOf(JobPersistenceException.class);
  }
}
