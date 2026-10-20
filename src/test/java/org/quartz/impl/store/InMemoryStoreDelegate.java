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
package org.quartz.impl.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.quartz.Calendar;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.store.codec.JobDataCodec;
import org.quartz.impl.store.codec.JobDetailCodec;
import org.quartz.impl.store.codec.SerializationCodec;
import org.quartz.impl.store.codec.TriggerCodec;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.OperableTrigger;

/**
 * Test-only {@link StoreDelegate} keeping encoded copies in shared maps, so the engine can
 * be run against the contract kits without containers. Every method is synchronized on the
 * shared data, which makes each primitive atomic as the SPI requires.
 */
class InMemoryStoreDelegate implements StoreDelegate {

    /** The backing data, shared by all store instances of a test. */
    static final class Data {
        final Map<JobKey, Map<String, Object>> jobs = new LinkedHashMap<>();
        final Map<JobKey, byte[]> jobData = new LinkedHashMap<>();
        final Map<TriggerKey, Map<String, Object>> triggers = new LinkedHashMap<>();
        final Map<TriggerKey, byte[]> triggerData = new LinkedHashMap<>();
        final Map<TriggerKey, String> states = new LinkedHashMap<>();
        final Map<String, byte[]> calendars = new LinkedHashMap<>();
        final Set<String> pausedTriggerGroups = new LinkedHashSet<>();
        final Set<String> pausedJobGroups = new LinkedHashSet<>();
        final Map<String, FiredTriggerRecord> fired = new LinkedHashMap<>();
        final Map<String, SchedulerStateRecord> schedulers = new LinkedHashMap<>();
        final Map<String, String[]> locks = new ConcurrentHashMap<>(); // name -> {owner, expiresAtMillis}
    }

    private final Data data;

    private final ClassLoadHelper loadHelper;

    InMemoryStoreDelegate(Data data, ClassLoadHelper loadHelper) {
        this.data = data;
        this.loadHelper = loadHelper;
    }

    private JobDetail decodeJob(JobKey key) throws JobPersistenceException {
        Map<String, Object> fields = data.jobs.get(key);
        if (fields == null) {
            return null;
        }
        return JobDetailCodec.decode(fields, JobDataCodec.deserialize(data.jobData.get(key), loadHelper), loadHelper);
    }

    @Override
    public void insertJob(JobDetail job) throws JobPersistenceException {
        synchronized (data) {
            data.jobs.put(job.getKey(), JobDetailCodec.encode(job));
            data.jobData.put(job.getKey(), JobDataCodec.serialize(job.getJobDataMap()));
        }
    }

    @Override
    public void updateJob(JobDetail job) throws JobPersistenceException {
        insertJob(job);
    }

    @Override
    public void updateJobData(JobDetail job) throws JobPersistenceException {
        synchronized (data) {
            data.jobData.put(job.getKey(), JobDataCodec.serialize(job.getJobDataMap()));
        }
    }

    @Override
    public boolean jobExists(JobKey jobKey) {
        synchronized (data) {
            return data.jobs.containsKey(jobKey);
        }
    }

    @Override
    public JobDetail selectJob(JobKey jobKey) throws JobPersistenceException {
        synchronized (data) {
            return decodeJob(jobKey);
        }
    }

    @Override
    public boolean deleteJob(JobKey jobKey) {
        synchronized (data) {
            data.jobData.remove(jobKey);
            return data.jobs.remove(jobKey) != null;
        }
    }

    @Override
    public int selectNumJobs() {
        synchronized (data) {
            return data.jobs.size();
        }
    }

    @Override
    public List<String> selectJobGroups() {
        synchronized (data) {
            return data.jobs.keySet().stream().map(JobKey::getGroup).distinct().toList();
        }
    }

    @Override
    public Set<JobKey> selectJobKeys(String group) {
        synchronized (data) {
            return data.jobs.keySet().stream().filter(k -> k.getGroup().equals(group))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    @Override
    public void insertTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        synchronized (data) {
            data.triggers.put(trigger.getKey(), TriggerCodec.encode(trigger));
            data.triggerData.put(trigger.getKey(), JobDataCodec.serialize(trigger.getJobDataMap()));
            data.states.put(trigger.getKey(), state);
        }
    }

    @Override
    public void updateTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        insertTrigger(trigger, state);
    }

    @Override
    public boolean triggerExists(TriggerKey triggerKey) {
        synchronized (data) {
            return data.triggers.containsKey(triggerKey);
        }
    }

    @Override
    public OperableTrigger selectTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        synchronized (data) {
            Map<String, Object> fields = data.triggers.get(triggerKey);
            return (fields != null) ? TriggerCodec.decode(fields, selectTriggerJobData(triggerKey), loadHelper) : null;
        }
    }

    @Override
    public JobDataMap selectTriggerJobData(TriggerKey triggerKey) throws JobPersistenceException {
        synchronized (data) {
            byte[] bytes = data.triggerData.get(triggerKey);
            return (bytes != null) ? JobDataCodec.deserialize(bytes, loadHelper) : new JobDataMap();
        }
    }

    @Override
    public String selectTriggerState(TriggerKey triggerKey) {
        synchronized (data) {
            return data.states.get(triggerKey);
        }
    }

    @Override
    public boolean deleteTrigger(TriggerKey triggerKey) {
        synchronized (data) {
            data.triggerData.remove(triggerKey);
            data.states.remove(triggerKey);
            return data.triggers.remove(triggerKey) != null;
        }
    }

    @Override
    public int selectNumTriggers() {
        synchronized (data) {
            return data.triggers.size();
        }
    }

    private JobKey jobKeyOf(Map<String, Object> fields) {
        return new JobKey((String) fields.get(TriggerCodec.JOB_NAME), (String) fields.get(TriggerCodec.JOB_GROUP));
    }

    private List<TriggerKey> triggerKeys(Predicate<Map.Entry<TriggerKey, Map<String, Object>>> filter) {
        return data.triggers.entrySet().stream().filter(filter).map(Map.Entry::getKey).toList();
    }

    @Override
    public int selectNumTriggersForJob(JobKey jobKey) {
        synchronized (data) {
            return selectTriggerKeysForJob(jobKey).size();
        }
    }

    @Override
    public List<String> selectTriggerGroups() {
        synchronized (data) {
            return data.triggers.keySet().stream().map(TriggerKey::getGroup).distinct().toList();
        }
    }

    @Override
    public Set<TriggerKey> selectTriggerKeys(String group) {
        synchronized (data) {
            return new LinkedHashSet<>(triggerKeys(e -> e.getKey().getGroup().equals(group)));
        }
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForJob(JobKey jobKey) {
        synchronized (data) {
            return triggerKeys(e -> jobKeyOf(e.getValue()).equals(jobKey));
        }
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForCalendar(String calendarName) {
        synchronized (data) {
            return triggerKeys(e -> calendarName.equals(e.getValue().get(TriggerCodec.CALENDAR_NAME)));
        }
    }

    @Override
    public List<TriggerKey> selectTriggerKeysInState(String state) {
        synchronized (data) {
            return triggerKeys(e -> state.equals(data.states.get(e.getKey())));
        }
    }

    @Override
    public int updateTriggerState(TriggerKey triggerKey, String newState) {
        synchronized (data) {
            return data.states.replace(triggerKey, newState) != null ? 1 : 0;
        }
    }

    @Override
    public int updateTriggerStateFromOtherStates(TriggerKey triggerKey, String newState, Collection<String> oldStates) {
        synchronized (data) {
            if (oldStates.contains(data.states.get(triggerKey))) {
                data.states.put(triggerKey, newState);
                return 1;
            }
            return 0;
        }
    }

    private int updateWhere(Predicate<TriggerKey> filter, String newState, Collection<String> oldStates) {
        int count = 0;
        for (TriggerKey key : List.copyOf(data.states.keySet())) {
            if (filter.test(key) && (oldStates == null || oldStates.contains(data.states.get(key)))) {
                data.states.put(key, newState);
                count++;
            }
        }
        return count;
    }

    @Override
    public int updateTriggerGroupStateFromOtherStates(String group, String newState, Collection<String> oldStates) {
        synchronized (data) {
            return updateWhere(k -> k.getGroup().equals(group), newState, oldStates);
        }
    }

    @Override
    public int updateTriggerStatesForJob(JobKey jobKey, String newState) {
        synchronized (data) {
            return updateWhere(k -> jobKeyOf(data.triggers.get(k)).equals(jobKey), newState, null);
        }
    }

    @Override
    public int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState) {
        synchronized (data) {
            return updateWhere(k -> jobKeyOf(data.triggers.get(k)).equals(jobKey), newState, List.of(oldState));
        }
    }

    @Override
    public int updateTriggerStatesFromOtherStates(String newState, Collection<String> oldStates) {
        synchronized (data) {
            return updateWhere(k -> true, newState, oldStates);
        }
    }

    private Long nextFireTime(TriggerKey key) {
        Object value = data.triggers.get(key).get(TriggerCodec.NEXT_FIRE_TIME);
        return (value != null) ? ((Number) value).longValue() : null;
    }

    private int priority(TriggerKey key) {
        return ((Number) data.triggers.get(key).get(TriggerCodec.PRIORITY)).intValue();
    }

    private boolean ignoresMisfires(TriggerKey key) {
        return ((Number) data.triggers.get(key).get(TriggerCodec.MISFIRE_INSTRUCTION)).intValue()
                == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY;
    }

    private List<TriggerKey> sortedByFireTime(Predicate<TriggerKey> filter, int limit) {
        return data.states.keySet().stream().filter(k -> nextFireTime(k) != null).filter(filter)
            .sorted(Comparator.<TriggerKey>comparingLong(this::nextFireTime)
                .thenComparing(Comparator.comparingInt(this::priority).reversed()))
            .limit((limit < 0) ? Long.MAX_VALUE : limit)
            .toList();
    }

    @Override
    public List<TriggerKey> selectTriggersToAcquire(Instant noLaterThan, Instant misfireTime, int maxCount) {
        synchronized (data) {
            return sortedByFireTime(k -> PersistentJobStore.STATE_WAITING.equals(data.states.get(k))
                    && nextFireTime(k) <= noLaterThan.toEpochMilli()
                    && (ignoresMisfires(k) || nextFireTime(k) >= misfireTime.toEpochMilli()), maxCount);
        }
    }

    @Override
    public List<TriggerKey> selectMisfiredTriggersInState(String state, Instant misfireTime, int limit) {
        synchronized (data) {
            return sortedByFireTime(k -> state.equals(data.states.get(k)) && !ignoresMisfires(k)
                    && nextFireTime(k) < misfireTime.toEpochMilli(), limit);
        }
    }

    @Override
    public int countMisfiredTriggersInState(String state, Instant misfireTime) {
        return selectMisfiredTriggersInState(state, misfireTime, -1).size();
    }

    @Override
    public void insertCalendar(String name, Calendar calendar) throws JobPersistenceException {
        synchronized (data) {
            data.calendars.put(name, SerializationCodec.serialize(calendar));
        }
    }

    @Override
    public void updateCalendar(String name, Calendar calendar) throws JobPersistenceException {
        insertCalendar(name, calendar);
    }

    @Override
    public boolean calendarExists(String name) {
        synchronized (data) {
            return data.calendars.containsKey(name);
        }
    }

    @Override
    public Calendar selectCalendar(String name) throws JobPersistenceException {
        synchronized (data) {
            byte[] bytes = data.calendars.get(name);
            return (bytes != null) ? SerializationCodec.deserialize(bytes, Calendar.class, loadHelper) : null;
        }
    }

    @Override
    public boolean deleteCalendar(String name) {
        synchronized (data) {
            return data.calendars.remove(name) != null;
        }
    }

    @Override
    public List<String> selectCalendarNames() {
        synchronized (data) {
            return List.copyOf(data.calendars.keySet());
        }
    }

    @Override
    public int selectNumCalendars() {
        synchronized (data) {
            return data.calendars.size();
        }
    }

    @Override
    public void insertPausedTriggerGroup(String group) {
        synchronized (data) {
            data.pausedTriggerGroups.add(group);
        }
    }

    @Override
    public void deletePausedTriggerGroup(String group) {
        synchronized (data) {
            data.pausedTriggerGroups.remove(group);
        }
    }

    @Override
    public boolean isTriggerGroupPaused(String group) {
        synchronized (data) {
            return data.pausedTriggerGroups.contains(group);
        }
    }

    @Override
    public Set<String> selectPausedTriggerGroups() {
        synchronized (data) {
            return new LinkedHashSet<>(data.pausedTriggerGroups);
        }
    }

    @Override
    public void insertPausedJobGroup(String group) {
        synchronized (data) {
            data.pausedJobGroups.add(group);
        }
    }

    @Override
    public void deletePausedJobGroup(String group) {
        synchronized (data) {
            data.pausedJobGroups.remove(group);
        }
    }

    @Override
    public boolean isJobGroupPaused(String group) {
        synchronized (data) {
            return data.pausedJobGroups.contains(group);
        }
    }

    @Override
    public Set<String> selectPausedJobGroups() {
        synchronized (data) {
            return new LinkedHashSet<>(data.pausedJobGroups);
        }
    }

    @Override
    public void insertFiredTrigger(FiredTriggerRecord record) {
        synchronized (data) {
            data.fired.put(record.fireInstanceId(), record);
        }
    }

    @Override
    public void updateFiredTrigger(FiredTriggerRecord record) {
        synchronized (data) {
            data.fired.replace(record.fireInstanceId(), record);
        }
    }

    @Override
    public void deleteFiredTrigger(String fireInstanceId) {
        synchronized (data) {
            data.fired.remove(fireInstanceId);
        }
    }

    @Override
    public int deleteFiredTriggers() {
        synchronized (data) {
            int count = data.fired.size();
            data.fired.clear();
            return count;
        }
    }

    @Override
    public int deleteFiredTriggers(String instanceId) {
        synchronized (data) {
            List<String> ids = data.fired.values().stream().filter(r -> r.instanceId().equals(instanceId))
                .map(FiredTriggerRecord::fireInstanceId).toList();
            ids.forEach(data.fired::remove);
            return ids.size();
        }
    }

    private List<FiredTriggerRecord> fired(Predicate<FiredTriggerRecord> filter) {
        synchronized (data) {
            return data.fired.values().stream().filter(filter).toList();
        }
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForInstance(String instanceId) {
        return fired(r -> r.instanceId().equals(instanceId));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForJob(JobKey jobKey) {
        return fired(r -> r.jobKey().equals(jobKey));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForTrigger(TriggerKey triggerKey) {
        return fired(r -> r.triggerKey().equals(triggerKey));
    }

    @Override
    public Set<String> selectFiredTriggerInstanceIds() {
        return fired(r -> true).stream().map(FiredTriggerRecord::instanceId).collect(Collectors.toSet());
    }

    @Override
    public void upsertSchedulerState(String instanceId, Instant checkin, Duration checkinInterval) {
        synchronized (data) {
            data.schedulers.put(instanceId, new SchedulerStateRecord(instanceId, checkin, checkinInterval));
        }
    }

    @Override
    public List<SchedulerStateRecord> selectSchedulerStateRecords() {
        synchronized (data) {
            return new ArrayList<>(data.schedulers.values());
        }
    }

    @Override
    public void deleteSchedulerState(String instanceId) {
        synchronized (data) {
            data.schedulers.remove(instanceId);
        }
    }

    @Override
    public void clearAllSchedulingData() {
        synchronized (data) {
            data.jobs.clear();
            data.jobData.clear();
            data.triggers.clear();
            data.triggerData.clear();
            data.states.clear();
            data.calendars.clear();
            data.pausedTriggerGroups.clear();
            data.pausedJobGroups.clear();
            data.fired.clear();
        }
    }
}
