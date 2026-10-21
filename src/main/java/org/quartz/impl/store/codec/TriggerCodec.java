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

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.quartz.DateBuilder.IntervalUnit;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.TimeOfDay;
import org.quartz.impl.triggers.AbstractTrigger;
import org.quartz.impl.triggers.CalendarIntervalTriggerImpl;
import org.quartz.impl.triggers.CronTriggerImpl;
import org.quartz.impl.triggers.DailyTimeIntervalTriggerImpl;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.OperableTrigger;

/**
 * Encodes triggers as store-neutral field maps: common fields plus the schedule of the
 * four built-in trigger types. Other {@link OperableTrigger} implementations are stored
 * Java-serialized ({@value #TYPE_BLOB}). Instants are epoch milliseconds; job data is
 * encoded separately by {@link JobDataCodec}.
 */
public final class TriggerCodec {

    public static final String TYPE = "type";
    public static final String NAME = "name";
    public static final String GROUP = "group";
    public static final String JOB_NAME = "jobName";
    public static final String JOB_GROUP = "jobGroup";
    public static final String DESCRIPTION = "description";
    public static final String NEXT_FIRE_TIME = "nextFireTime";
    public static final String PREVIOUS_FIRE_TIME = "previousFireTime";
    public static final String PRIORITY = "priority";
    public static final String MISFIRE_INSTRUCTION = "misfireInstruction";
    public static final String CALENDAR_NAME = "calendarName";
    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String REPEAT_COUNT = "repeatCount";
    public static final String REPEAT_INTERVAL = "repeatInterval";
    public static final String REPEAT_INTERVAL_UNIT = "repeatIntervalUnit";
    public static final String TIMES_TRIGGERED = "timesTriggered";
    public static final String CRON_EXPRESSION = "cronExpression";
    public static final String TIME_ZONE = "timeZone";
    public static final String PRESERVE_HOUR = "preserveHourOfDayAcrossDaylightSavings";
    public static final String SKIP_DAY = "skipDayIfHourDoesNotExist";
    public static final String START_TIME_OF_DAY = "startTimeOfDay";
    public static final String END_TIME_OF_DAY = "endTimeOfDay";
    public static final String DAYS_OF_WEEK = "daysOfWeek";
    public static final String BLOB = "blob";

    public static final String TYPE_SIMPLE = "SIMPLE";
    public static final String TYPE_CRON = "CRON";
    public static final String TYPE_CALENDAR_INTERVAL = "CAL_INT";
    public static final String TYPE_DAILY_TIME_INTERVAL = "DAILY_I";
    public static final String TYPE_BLOB = "BLOB";

    private TriggerCodec() {
    }

    /**
     * Encodes the trigger, without its job data. {@code null} values are omitted.
     */
    public static Map<String, Object> encode(OperableTrigger trigger) throws JobPersistenceException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NAME, trigger.getKey().getName());
        fields.put(GROUP, trigger.getKey().getGroup());
        fields.put(JOB_NAME, trigger.getJobKey().getName());
        fields.put(JOB_GROUP, trigger.getJobKey().getGroup());
        putIfNotNull(fields, DESCRIPTION, trigger.getDescription());
        putIfNotNull(fields, NEXT_FIRE_TIME, millis(trigger.getNextFireTime()));
        putIfNotNull(fields, PREVIOUS_FIRE_TIME, millis(trigger.getPreviousFireTime()));
        fields.put(PRIORITY, trigger.getPriority());
        fields.put(MISFIRE_INSTRUCTION, trigger.getMisfireInstruction());
        putIfNotNull(fields, CALENDAR_NAME, trigger.getCalendarName());
        putIfNotNull(fields, START_TIME, millis(trigger.getStartTime()));
        putIfNotNull(fields, END_TIME, millis(trigger.getEndTime()));

        if (trigger.getClass() == SimpleTriggerImpl.class) {
            SimpleTriggerImpl simple = (SimpleTriggerImpl) trigger;
            fields.put(TYPE, TYPE_SIMPLE);
            fields.put(REPEAT_COUNT, simple.getRepeatCount());
            fields.put(REPEAT_INTERVAL, simple.getRepeatInterval().toMillis());
            fields.put(TIMES_TRIGGERED, simple.getTimesTriggered());
        }
        else if (trigger.getClass() == CronTriggerImpl.class) {
            CronTriggerImpl cron = (CronTriggerImpl) trigger;
            fields.put(TYPE, TYPE_CRON);
            fields.put(CRON_EXPRESSION, cron.getCronExpression());
            fields.put(TIME_ZONE, cron.getTimeZone().getId());
        }
        else if (trigger.getClass() == CalendarIntervalTriggerImpl.class) {
            CalendarIntervalTriggerImpl calendarInterval = (CalendarIntervalTriggerImpl) trigger;
            fields.put(TYPE, TYPE_CALENDAR_INTERVAL);
            fields.put(REPEAT_INTERVAL_UNIT, calendarInterval.getRepeatIntervalUnit().name());
            fields.put(REPEAT_INTERVAL, calendarInterval.getRepeatInterval());
            fields.put(TIMES_TRIGGERED, calendarInterval.getTimesTriggered());
            fields.put(TIME_ZONE, calendarInterval.getTimeZone().getId());
            fields.put(PRESERVE_HOUR, calendarInterval.isPreserveHourOfDayAcrossDaylightSavings());
            fields.put(SKIP_DAY, calendarInterval.isSkipDayIfHourDoesNotExist());
        }
        else if (trigger.getClass() == DailyTimeIntervalTriggerImpl.class) {
            DailyTimeIntervalTriggerImpl daily = (DailyTimeIntervalTriggerImpl) trigger;
            fields.put(TYPE, TYPE_DAILY_TIME_INTERVAL);
            fields.put(REPEAT_INTERVAL_UNIT, daily.getRepeatIntervalUnit().name());
            fields.put(REPEAT_INTERVAL, daily.getRepeatInterval());
            fields.put(REPEAT_COUNT, daily.getRepeatCount());
            fields.put(TIMES_TRIGGERED, daily.getTimesTriggered());
            putIfNotNull(fields, START_TIME_OF_DAY, timeOfDay(daily.getStartTimeOfDay()));
            putIfNotNull(fields, END_TIME_OF_DAY, timeOfDay(daily.getEndTimeOfDay()));
            if (daily.getDaysOfWeek() != null) {
                fields.put(DAYS_OF_WEEK, daily.getDaysOfWeek().stream().sorted().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            }
        }
        else {
            fields.put(TYPE, TYPE_BLOB);
            OperableTrigger copy = (OperableTrigger) trigger.clone();
            copy.setJobDataMap(new JobDataMap());
            fields.put(BLOB, SerializationCodec.serialize(copy));
        }
        return fields;
    }

    /**
     * Decodes a trigger encoded by {@link #encode}, attaching {@code jobData}.
     */
    public static OperableTrigger decode(Map<String, ?> fields, JobDataMap jobData, ClassLoadHelper loadHelper)
            throws JobPersistenceException {
        String type = Fields.string(fields, TYPE);
        OperableTrigger trigger;
        try {
            trigger = switch (type) {
                case TYPE_SIMPLE -> decodeSimple(fields);
                case TYPE_CRON -> decodeCron(fields);
                case TYPE_CALENDAR_INTERVAL -> decodeCalendarInterval(fields);
                case TYPE_DAILY_TIME_INTERVAL -> decodeDailyTimeInterval(fields);
                case TYPE_BLOB -> SerializationCodec.deserialize(Objects.requireNonNull(Fields.bytes(fields, BLOB), BLOB),
                        OperableTrigger.class, loadHelper);
                case null, default -> throw new JobPersistenceException("Unknown trigger type '" + type + "'");
            };
        }
        catch (ParseException | IllegalArgumentException ex) {
            throw new JobPersistenceException("Couldn't decode trigger " + Fields.string(fields, GROUP) + "."
                    + Fields.string(fields, NAME) + ": " + ex.getMessage(), ex);
        }
        if (trigger instanceof AbstractTrigger<?> common) {
            common.setName(Fields.string(fields, NAME));
            common.setGroup(Fields.string(fields, GROUP));
            common.setJobKey(new JobKey(Fields.string(fields, JOB_NAME), Fields.string(fields, JOB_GROUP)));
            common.setDescription(Fields.string(fields, DESCRIPTION));
            common.setCalendarName(Fields.string(fields, CALENDAR_NAME));
        }
        trigger.setPriority(Fields.intValue(fields, PRIORITY, 5));
        trigger.setMisfireInstruction(Fields.intValue(fields, MISFIRE_INSTRUCTION, 0));
        if (!TYPE_BLOB.equals(type)) {
            Instant start = instant(fields, START_TIME);
            if (start != null) {
                trigger.setStartTime(start);
            }
            trigger.setEndTime(instant(fields, END_TIME));
        }
        trigger.setNextFireTime(instant(fields, NEXT_FIRE_TIME));
        trigger.setPreviousFireTime(instant(fields, PREVIOUS_FIRE_TIME));
        trigger.setJobDataMap((jobData != null) ? jobData : new JobDataMap());
        return trigger;
    }

    private static SimpleTriggerImpl decodeSimple(Map<String, ?> fields) {
        SimpleTriggerImpl trigger = new SimpleTriggerImpl();
        trigger.setRepeatCount(Fields.intValue(fields, REPEAT_COUNT, 0));
        trigger.setRepeatInterval(
                Duration.ofMillis(Objects.requireNonNull(Fields.longValue(fields, REPEAT_INTERVAL), REPEAT_INTERVAL)));
        trigger.setTimesTriggered(Fields.intValue(fields, TIMES_TRIGGERED, 0));
        return trigger;
    }

    private static CronTriggerImpl decodeCron(Map<String, ?> fields) throws ParseException {
        CronTriggerImpl trigger = new CronTriggerImpl();
        trigger.setCronExpression(Fields.string(fields, CRON_EXPRESSION));
        trigger.setTimeZone(ZoneId.of(Fields.string(fields, TIME_ZONE)));
        return trigger;
    }

    private static CalendarIntervalTriggerImpl decodeCalendarInterval(Map<String, ?> fields) {
        CalendarIntervalTriggerImpl trigger = new CalendarIntervalTriggerImpl();
        trigger.setRepeatIntervalUnit(IntervalUnit.valueOf(Fields.string(fields, REPEAT_INTERVAL_UNIT)));
        trigger.setRepeatInterval(Fields.intValue(fields, REPEAT_INTERVAL, 1));
        trigger.setTimesTriggered(Fields.intValue(fields, TIMES_TRIGGERED, 0));
        trigger.setTimeZone(ZoneId.of(Fields.string(fields, TIME_ZONE)));
        trigger.setPreserveHourOfDayAcrossDaylightSavings(Fields.booleanValue(fields, PRESERVE_HOUR));
        trigger.setSkipDayIfHourDoesNotExist(Fields.booleanValue(fields, SKIP_DAY));
        return trigger;
    }

    private static DailyTimeIntervalTriggerImpl decodeDailyTimeInterval(Map<String, ?> fields) {
        DailyTimeIntervalTriggerImpl trigger = new DailyTimeIntervalTriggerImpl();
        trigger.setRepeatIntervalUnit(IntervalUnit.valueOf(Fields.string(fields, REPEAT_INTERVAL_UNIT)));
        trigger.setRepeatInterval(Fields.intValue(fields, REPEAT_INTERVAL, 1));
        trigger.setRepeatCount(Fields.intValue(fields, REPEAT_COUNT, -1));
        trigger.setTimesTriggered(Fields.intValue(fields, TIMES_TRIGGERED, 0));
        String start = Fields.string(fields, START_TIME_OF_DAY);
        if (start != null) {
            trigger.setStartTimeOfDay(parseTimeOfDay(start));
        }
        String end = Fields.string(fields, END_TIME_OF_DAY);
        if (end != null) {
            trigger.setEndTimeOfDay(parseTimeOfDay(end));
        }
        String days = Fields.string(fields, DAYS_OF_WEEK);
        if (days != null && !days.isEmpty()) {
            trigger.setDaysOfWeek(Arrays.stream(days.split(",")).map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new)));
        }
        return trigger;
    }

    private static @Nullable String timeOfDay(@Nullable TimeOfDay timeOfDay) {
        return (timeOfDay != null)
                ? String.format("%02d:%02d:%02d", timeOfDay.getHour(), timeOfDay.getMinute(), timeOfDay.getSecond())
                : null;
    }

    private static TimeOfDay parseTimeOfDay(String value) {
        String[] parts = value.split(":");
        return new TimeOfDay(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static @Nullable Long millis(@Nullable Instant instant) {
        return (instant != null) ? instant.toEpochMilli() : null;
    }

    private static @Nullable Instant instant(Map<String, ?> fields, String name) {
        Long millis = Fields.longValue(fields, name);
        return (millis != null) ? Instant.ofEpochMilli(millis) : null;
    }

    private static void putIfNotNull(Map<String, Object> fields, String name, @Nullable Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
