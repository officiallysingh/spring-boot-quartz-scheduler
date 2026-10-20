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
package org.quartz.impl.redis;

import org.quartz.JobKey;
import org.quartz.TriggerKey;
import org.quartz.utils.Key;

/**
 * Key naming of the Redis job store. Group and job/trigger names are escaped
 * ({@code %} and {@code :}) so they can be joined into key names; every key of a scheduler
 * starts with {@code keyPrefix{schedName}:}, whose hash tag keeps them in one slot.
 */
final class RedisKeys {

    private final String prefix;

    RedisKeys(String keyPrefix, String schedName) {
        this.prefix = keyPrefix + "{" + schedName + "}:";
    }

    String prefix() {
        return prefix;
    }

    /** Glob pattern matching every key of the scheduler. */
    String pattern() {
        return prefix.replaceAll("([*?\\[\\]\\\\])", "\\\\$1") + "*";
    }

    static String escape(String value) {
        return value.replace("%", "%25").replace(":", "%3A");
    }

    static String unescape(String value) {
        return value.replace("%3A", ":").replace("%25", "%");
    }

    static String k(Key<?> key) {
        return escape(key.getGroup()) + ":" + escape(key.getName());
    }

    static JobKey jobKey(String k) {
        int split = k.indexOf(':');
        return new JobKey(unescape(k.substring(split + 1)), unescape(k.substring(0, split)));
    }

    static TriggerKey triggerKey(String k) {
        int split = k.indexOf(':');
        return new TriggerKey(unescape(k.substring(split + 1)), unescape(k.substring(0, split)));
    }

    String job(String k) {
        return prefix + "job:" + k;
    }

    String jobs(String group) {
        return prefix + "jobs:" + escape(group);
    }

    String jobGroups() {
        return prefix + "job_groups";
    }

    String trigger(String k) {
        return prefix + "trigger:" + k;
    }

    String triggers(String group) {
        return prefix + "triggers:" + escape(group);
    }

    String triggerGroups() {
        return prefix + "trigger_groups";
    }

    String jobTriggers(String jobK) {
        return prefix + "job_triggers:" + jobK;
    }

    String calendarTriggers(String calendarName) {
        return prefix + "calendar_triggers:" + escape(calendarName);
    }

    String state(String state) {
        return prefix + "state:" + state;
    }

    String waiting() {
        return prefix + "waiting";
    }

    String waitingIgnoreMisfire() {
        return prefix + "waiting_ignore_misfire";
    }

    String calendars() {
        return prefix + "calendars";
    }

    String pausedTriggerGroups() {
        return prefix + "paused_trigger_groups";
    }

    String pausedJobGroups() {
        return prefix + "paused_job_groups";
    }

    String fired(String id) {
        return prefix + "fired:" + id;
    }

    String firedIds() {
        return prefix + "fired_ids";
    }

    String firedByInstance(String instanceId) {
        return prefix + "fired_by_instance:" + escape(instanceId);
    }

    String firedByJob(String jobK) {
        return prefix + "fired_by_job:" + jobK;
    }

    String firedByTrigger(String triggerK) {
        return prefix + "fired_by_trigger:" + triggerK;
    }

    String firedInstances() {
        return prefix + "fired_instances";
    }

    String schedulers() {
        return prefix + "schedulers";
    }

    String lock(String lockName) {
        return prefix + "lock:" + lockName;
    }
}
