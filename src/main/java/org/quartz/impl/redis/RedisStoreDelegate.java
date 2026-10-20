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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.quartz.Calendar;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.store.FiredTriggerRecord;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.store.SchedulerStateRecord;
import org.quartz.impl.store.StoreDelegate;
import org.quartz.impl.store.codec.JobDataCodec;
import org.quartz.impl.store.codec.JobDetailCodec;
import org.quartz.impl.store.codec.SerializationCodec;
import org.quartz.impl.store.codec.TriggerCodec;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.OperableTrigger;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link StoreDelegate} over Redis: hashes for jobs, triggers and fired triggers, index
 * sets and sorted sets maintained by the Lua scripts in {@link RedisScripts} (one script per
 * multi-key change, hence atomic); single-key changes use plain commands.
 */
class RedisStoreDelegate implements StoreDelegate {

    static final String DATA_FIELD_PREFIX = "data.";
    static final String DATA_BLOB = "dataBlob";

    private static final String FIRE_INSTANCE_ID = "fireInstanceId";
    private static final String INSTANCE_ID = "instanceId";
    private static final String TRIGGER_GROUP = "triggerGroup";
    private static final String TRIGGER_NAME = "triggerName";
    private static final String STATE = "state";
    private static final String FIRED_TIME = "firedTime";
    private static final String SCHEDULED_TIME = "scheduledTime";

    private final StringRedisTemplate redis;

    private final RedisKeys keys;

    private final ClassLoadHelper loadHelper;

    RedisStoreDelegate(StringRedisTemplate redis, RedisKeys keys, ClassLoadHelper loadHelper) {
        this.redis = redis;
        this.keys = keys;
        this.loadHelper = loadHelper;
    }

    private long run(RedisScript<Long> script, String routingKey, List<String> args) {
        Long result = redis.execute(script, List.of(routingKey), args.toArray());
        return (result != null) ? result : 0L;
    }

    private Map<String, String> hash(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        Map<String, String> fields = new LinkedHashMap<>();
        entries.forEach((name, value) -> fields.put((String) name, (String) value));
        return fields;
    }

    private static void addPairs(List<String> args, Map<String, Object> fields) {
        fields.forEach((name, value) -> {
            args.add(name);
            args.add((value instanceof byte[] bytes) ? SerializationCodec.toBase64(bytes) : value.toString());
        });
    }

    private static void addJobData(List<String> args, JobDataMap data) throws JobPersistenceException {
        if (data == null || data.isEmpty()) {
            return;
        }
        if (JobDataCodec.isNative(data)) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                args.add(DATA_FIELD_PREFIX + entry.getKey());
                args.add(JobDataCodec.tag(entry.getValue()));
            }
        }
        else {
            args.add(DATA_BLOB);
            args.add(SerializationCodec.toBase64(JobDataCodec.serialize(data)));
        }
    }

    private JobDataMap jobData(Map<String, String> fields) throws JobPersistenceException {
        String blob = fields.get(DATA_BLOB);
        if (blob != null) {
            return JobDataCodec.deserialize(SerializationCodec.fromBase64(blob), loadHelper);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            if (name.startsWith(DATA_FIELD_PREFIX)) {
                values.put(name.substring(DATA_FIELD_PREFIX.length()), JobDataCodec.untag(value));
            }
        });
        return JobDataCodec.fromNative(values);
    }

    private Set<String> members(String key) {
        Set<String> members = redis.opsForSet().members(key);
        return (members != null) ? members : Set.of();
    }

    private long size(String key) {
        Long size = redis.opsForSet().size(key);
        return (size != null) ? size : 0L;
    }

    // ------------------------------------------------------------------
    // jobs
    // ------------------------------------------------------------------

    @Override
    public void insertJob(JobDetail job) throws JobPersistenceException {
        String k = RedisKeys.k(job.getKey());
        List<String> args = new ArrayList<>(List.of(keys.prefix(), k, RedisKeys.escape(job.getKey().getGroup()),
                job.getKey().getGroup()));
        addPairs(args, JobDetailCodec.encode(job));
        addJobData(args, job.getJobDataMap());
        run(RedisScripts.STORE_JOB, keys.job(k), args);
    }

    @Override
    public void updateJob(JobDetail job) throws JobPersistenceException {
        insertJob(job);
    }

    @Override
    public void updateJobData(JobDetail job) throws JobPersistenceException {
        String k = RedisKeys.k(job.getKey());
        List<String> args = new ArrayList<>(List.of(keys.prefix(), k));
        addJobData(args, job.getJobDataMap());
        run(RedisScripts.UPDATE_JOB_DATA, keys.job(k), args);
    }

    @Override
    public boolean jobExists(JobKey jobKey) {
        return Boolean.TRUE.equals(redis.hasKey(keys.job(RedisKeys.k(jobKey))));
    }

    @Override
    public JobDetail selectJob(JobKey jobKey) throws JobPersistenceException {
        Map<String, String> fields = hash(keys.job(RedisKeys.k(jobKey)));
        return fields.isEmpty() ? null : JobDetailCodec.decode(fields, jobData(fields), loadHelper);
    }

    @Override
    public boolean deleteJob(JobKey jobKey) {
        String k = RedisKeys.k(jobKey);
        return run(RedisScripts.DELETE_JOB, keys.job(k), List.of(keys.prefix(), k, jobKey.getGroup())) > 0;
    }

    @Override
    public int selectNumJobs() {
        return (int) selectJobGroups().stream().mapToLong(group -> size(keys.jobs(group))).sum();
    }

    @Override
    public List<String> selectJobGroups() {
        return new ArrayList<>(members(keys.jobGroups()));
    }

    @Override
    public Set<JobKey> selectJobKeys(String group) {
        Set<JobKey> result = new LinkedHashSet<>();
        members(keys.jobs(group)).forEach(k -> result.add(RedisKeys.jobKey(k)));
        return result;
    }

    // ------------------------------------------------------------------
    // triggers
    // ------------------------------------------------------------------

    @Override
    public void insertTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        String k = RedisKeys.k(trigger.getKey());
        String calendar = trigger.getCalendarName();
        List<String> args = new ArrayList<>(List.of(keys.prefix(), k, RedisKeys.k(trigger.getJobKey()),
                RedisKeys.escape(trigger.getKey().getGroup()), (calendar != null) ? RedisKeys.escape(calendar) : "",
                state, trigger.getKey().getGroup()));
        addPairs(args, TriggerCodec.encode(trigger));
        addJobData(args, trigger.getJobDataMap());
        run(RedisScripts.STORE_TRIGGER, keys.trigger(k), args);
    }

    @Override
    public void updateTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        insertTrigger(trigger, state);
    }

    @Override
    public boolean triggerExists(TriggerKey triggerKey) {
        return Boolean.TRUE.equals(redis.hasKey(keys.trigger(RedisKeys.k(triggerKey))));
    }

    @Override
    public OperableTrigger selectTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        Map<String, String> fields = hash(keys.trigger(RedisKeys.k(triggerKey)));
        return fields.isEmpty() ? null : TriggerCodec.decode(fields, jobData(fields), loadHelper);
    }

    @Override
    public JobDataMap selectTriggerJobData(TriggerKey triggerKey) throws JobPersistenceException {
        Map<String, String> fields = hash(keys.trigger(RedisKeys.k(triggerKey)));
        return fields.isEmpty() ? new JobDataMap() : jobData(fields);
    }

    @Override
    public String selectTriggerState(TriggerKey triggerKey) {
        return (String) redis.opsForHash().get(keys.trigger(RedisKeys.k(triggerKey)), STATE);
    }

    @Override
    public boolean deleteTrigger(TriggerKey triggerKey) {
        String k = RedisKeys.k(triggerKey);
        return run(RedisScripts.DELETE_TRIGGER, keys.trigger(k), List.of(keys.prefix(), k)) > 0;
    }

    @Override
    public int selectNumTriggers() {
        return (int) selectTriggerGroups().stream().mapToLong(group -> size(keys.triggers(group))).sum();
    }

    @Override
    public int selectNumTriggersForJob(JobKey jobKey) {
        return (int) size(keys.jobTriggers(RedisKeys.k(jobKey)));
    }

    @Override
    public List<String> selectTriggerGroups() {
        return new ArrayList<>(members(keys.triggerGroups()));
    }

    private List<TriggerKey> triggerKeys(String setKey) {
        return members(setKey).stream().map(RedisKeys::triggerKey).toList();
    }

    @Override
    public Set<TriggerKey> selectTriggerKeys(String group) {
        return new LinkedHashSet<>(triggerKeys(keys.triggers(group)));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForJob(JobKey jobKey) {
        return triggerKeys(keys.jobTriggers(RedisKeys.k(jobKey)));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForCalendar(String calendarName) {
        return triggerKeys(keys.calendarTriggers(calendarName));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysInState(String state) {
        return triggerKeys(keys.state(state));
    }

    private int setState(String sourceSet, String singleK, String newState, Collection<String> oldStates) {
        List<String> args = new ArrayList<>(List.of(keys.prefix(), (sourceSet != null) ? sourceSet : "",
                (singleK != null) ? singleK : "", newState));
        args.addAll(oldStates);
        String routing = (sourceSet != null) ? sourceSet : keys.trigger(singleK);
        return (int) run(RedisScripts.SET_STATE, routing, args);
    }

    @Override
    public int updateTriggerState(TriggerKey triggerKey, String newState) {
        return setState(null, RedisKeys.k(triggerKey), newState, List.of());
    }

    @Override
    public int updateTriggerStateFromOtherStates(TriggerKey triggerKey, String newState, Collection<String> oldStates) {
        return setState(null, RedisKeys.k(triggerKey), newState, oldStates);
    }

    @Override
    public int updateTriggerGroupStateFromOtherStates(String group, String newState, Collection<String> oldStates) {
        return setState(keys.triggers(group), null, newState, oldStates);
    }

    @Override
    public int updateTriggerStatesForJob(JobKey jobKey, String newState) {
        return setState(keys.jobTriggers(RedisKeys.k(jobKey)), null, newState, List.of());
    }

    @Override
    public int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState) {
        return setState(keys.jobTriggers(RedisKeys.k(jobKey)), null, newState, List.of(oldState));
    }

    @Override
    public int updateTriggerStatesFromOtherStates(String newState, Collection<String> oldStates) {
        int count = 0;
        for (String oldState : oldStates) {
            count += setState(keys.state(oldState), null, newState, List.of(oldState));
        }
        return count;
    }

    private record Candidate(String k, double nextFireTime, int priority) {
    }

    private static final Comparator<Candidate> FIRE_ORDER = Comparator.comparingDouble(Candidate::nextFireTime)
        .thenComparing(Comparator.comparingInt(Candidate::priority).reversed());

    private List<Candidate> candidates(Set<TypedTuple<String>> tuples) {
        List<Candidate> candidates = new ArrayList<>();
        if (tuples == null) {
            return candidates;
        }
        for (TypedTuple<String> tuple : tuples) {
            Object priority = redis.opsForHash().get(keys.trigger(tuple.getValue()), TriggerCodec.PRIORITY);
            candidates.add(new Candidate(tuple.getValue(), tuple.getScore(),
                    (priority != null) ? Integer.parseInt((String) priority) : 5));
        }
        return candidates;
    }

    @Override
    public List<TriggerKey> selectTriggersToAcquire(Instant noLaterThan, Instant misfireTime, int maxCount) {
        // not misfired, any instruction; plus misfired ones that ignore misfires
        int window = Math.max(maxCount, 1) + 50;
        Map<String, Candidate> candidates = new HashMap<>();
        candidates(redis.opsForZSet().rangeByScoreWithScores(keys.waiting(), misfireTime.toEpochMilli(),
                noLaterThan.toEpochMilli(), 0, window)).forEach(c -> candidates.put(c.k(), c));
        candidates(redis.opsForZSet().rangeByScoreWithScores(keys.waitingIgnoreMisfire(), Double.NEGATIVE_INFINITY,
                Math.min(misfireTime.toEpochMilli() - 1, noLaterThan.toEpochMilli()), 0, window))
            .forEach(c -> candidates.put(c.k(), c));
        return candidates.values().stream().sorted(FIRE_ORDER).limit(Math.max(maxCount, 1))
            .map(c -> RedisKeys.triggerKey(c.k())).toList();
    }

    private List<Candidate> misfired(String state, Instant misfireTime) {
        long before = misfireTime.toEpochMilli() - 1;
        if (PersistentJobStore.STATE_WAITING.equals(state)) {
            return candidates(redis.opsForZSet().rangeByScoreWithScores(keys.waiting(), Double.NEGATIVE_INFINITY, before))
                .stream()
                .filter(c -> redis.opsForZSet().score(keys.waitingIgnoreMisfire(), c.k()) == null)
                .sorted(FIRE_ORDER).toList();
        }
        List<Candidate> result = new ArrayList<>();
        for (String k : members(keys.state(state))) {
            List<Object> values = redis.opsForHash().multiGet(keys.trigger(k),
                    List.of(TriggerCodec.NEXT_FIRE_TIME, TriggerCodec.MISFIRE_INSTRUCTION, TriggerCodec.PRIORITY));
            if (values.get(0) == null
                    || String.valueOf(Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY).equals(values.get(1))) {
                continue;
            }
            long next = Long.parseLong((String) values.get(0));
            if (next <= before) {
                result.add(new Candidate(k, next, Integer.parseInt((String) values.get(2))));
            }
        }
        result.sort(FIRE_ORDER);
        return result;
    }

    @Override
    public List<TriggerKey> selectMisfiredTriggersInState(String state, Instant misfireTime, int limit) {
        return misfired(state, misfireTime).stream().limit((limit < 0) ? Long.MAX_VALUE : limit)
            .map(c -> RedisKeys.triggerKey(c.k())).toList();
    }

    @Override
    public int countMisfiredTriggersInState(String state, Instant misfireTime) {
        return misfired(state, misfireTime).size();
    }

    // ------------------------------------------------------------------
    // calendars (one hash: name -> serialized calendar)
    // ------------------------------------------------------------------

    @Override
    public void insertCalendar(String name, Calendar calendar) throws JobPersistenceException {
        redis.opsForHash().put(keys.calendars(), name, SerializationCodec.toBase64(SerializationCodec.serialize(calendar)));
    }

    @Override
    public void updateCalendar(String name, Calendar calendar) throws JobPersistenceException {
        insertCalendar(name, calendar);
    }

    @Override
    public boolean calendarExists(String name) {
        return redis.opsForHash().hasKey(keys.calendars(), name);
    }

    @Override
    public Calendar selectCalendar(String name) throws JobPersistenceException {
        Object value = redis.opsForHash().get(keys.calendars(), name);
        return (value != null)
                ? SerializationCodec.deserialize(SerializationCodec.fromBase64((String) value), Calendar.class, loadHelper)
                : null;
    }

    @Override
    public boolean deleteCalendar(String name) {
        return redis.opsForHash().delete(keys.calendars(), name) > 0;
    }

    @Override
    public List<String> selectCalendarNames() {
        return redis.opsForHash().keys(keys.calendars()).stream().map(String.class::cast).toList();
    }

    @Override
    public int selectNumCalendars() {
        return redis.opsForHash().size(keys.calendars()).intValue();
    }

    // ------------------------------------------------------------------
    // paused groups
    // ------------------------------------------------------------------

    @Override
    public void insertPausedTriggerGroup(String group) {
        redis.opsForSet().add(keys.pausedTriggerGroups(), group);
    }

    @Override
    public void deletePausedTriggerGroup(String group) {
        redis.opsForSet().remove(keys.pausedTriggerGroups(), group);
    }

    @Override
    public boolean isTriggerGroupPaused(String group) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(keys.pausedTriggerGroups(), group));
    }

    @Override
    public Set<String> selectPausedTriggerGroups() {
        return new LinkedHashSet<>(members(keys.pausedTriggerGroups()));
    }

    @Override
    public void insertPausedJobGroup(String group) {
        redis.opsForSet().add(keys.pausedJobGroups(), group);
    }

    @Override
    public void deletePausedJobGroup(String group) {
        redis.opsForSet().remove(keys.pausedJobGroups(), group);
    }

    @Override
    public boolean isJobGroupPaused(String group) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(keys.pausedJobGroups(), group));
    }

    @Override
    public Set<String> selectPausedJobGroups() {
        return new LinkedHashSet<>(members(keys.pausedJobGroups()));
    }

    // ------------------------------------------------------------------
    // fired triggers
    // ------------------------------------------------------------------

    private static Map<String, Object> firedFields(FiredTriggerRecord record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIRE_INSTANCE_ID, record.fireInstanceId());
        fields.put(INSTANCE_ID, record.instanceId());
        fields.put(TRIGGER_GROUP, record.triggerKey().getGroup());
        fields.put(TRIGGER_NAME, record.triggerKey().getName());
        fields.put(TriggerCodec.JOB_GROUP, record.jobKey().getGroup());
        fields.put(TriggerCodec.JOB_NAME, record.jobKey().getName());
        fields.put(STATE, record.state());
        fields.put(FIRED_TIME, record.firedTime().toEpochMilli());
        fields.put(SCHEDULED_TIME, record.scheduledTime().toEpochMilli());
        fields.put(TriggerCodec.PRIORITY, record.priority());
        fields.put(JobDetailCodec.NON_CONCURRENT, record.nonConcurrent());
        fields.put(JobDetailCodec.REQUESTS_RECOVERY, record.requestsRecovery());
        return fields;
    }

    private static FiredTriggerRecord toFiredRecord(Map<String, String> fields) {
        return new FiredTriggerRecord(fields.get(FIRE_INSTANCE_ID), fields.get(INSTANCE_ID),
                new TriggerKey(fields.get(TRIGGER_NAME), fields.get(TRIGGER_GROUP)),
                new JobKey(fields.get(TriggerCodec.JOB_NAME), fields.get(TriggerCodec.JOB_GROUP)), fields.get(STATE),
                Instant.ofEpochMilli(Long.parseLong(fields.get(FIRED_TIME))),
                Instant.ofEpochMilli(Long.parseLong(fields.get(SCHEDULED_TIME))),
                Integer.parseInt(fields.get(TriggerCodec.PRIORITY)),
                Boolean.parseBoolean(fields.get(JobDetailCodec.NON_CONCURRENT)),
                Boolean.parseBoolean(fields.get(JobDetailCodec.REQUESTS_RECOVERY)));
    }

    @Override
    public void insertFiredTrigger(FiredTriggerRecord record) {
        List<String> args = new ArrayList<>(List.of(keys.prefix(), record.fireInstanceId(),
                RedisKeys.escape(record.instanceId()), RedisKeys.k(record.jobKey()), RedisKeys.k(record.triggerKey()),
                record.instanceId()));
        addPairs(args, firedFields(record));
        run(RedisScripts.INSERT_FIRED, keys.fired(record.fireInstanceId()), args);
    }

    @Override
    public void updateFiredTrigger(FiredTriggerRecord record) {
        List<String> args = new ArrayList<>(List.of(keys.prefix(), record.fireInstanceId()));
        addPairs(args, firedFields(record));
        run(RedisScripts.UPDATE_FIRED, keys.fired(record.fireInstanceId()), args);
    }

    @Override
    public void deleteFiredTrigger(String fireInstanceId) {
        run(RedisScripts.DELETE_FIRED, keys.fired(fireInstanceId), List.of(keys.prefix(), fireInstanceId));
    }

    @Override
    public int deleteFiredTriggers() {
        return (int) run(RedisScripts.DELETE_FIRED_SET, keys.firedIds(), List.of(keys.prefix(), keys.firedIds()));
    }

    @Override
    public int deleteFiredTriggers(String instanceId) {
        String set = keys.firedByInstance(instanceId);
        return (int) run(RedisScripts.DELETE_FIRED_SET, set, List.of(keys.prefix(), set));
    }

    private List<FiredTriggerRecord> firedRecords(String idSet) {
        List<FiredTriggerRecord> records = new ArrayList<>();
        for (String id : members(idSet)) {
            Map<String, String> fields = hash(keys.fired(id));
            if (!fields.isEmpty()) {
                records.add(toFiredRecord(fields));
            }
        }
        return records;
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForInstance(String instanceId) {
        return firedRecords(keys.firedByInstance(instanceId));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForJob(JobKey jobKey) {
        return firedRecords(keys.firedByJob(RedisKeys.k(jobKey)));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForTrigger(TriggerKey triggerKey) {
        return firedRecords(keys.firedByTrigger(RedisKeys.k(triggerKey)));
    }

    @Override
    public Set<String> selectFiredTriggerInstanceIds() {
        return new LinkedHashSet<>(members(keys.firedInstances()));
    }

    // ------------------------------------------------------------------
    // scheduler states (one hash: instance id -> "lastCheckinMillis,intervalMillis")
    // ------------------------------------------------------------------

    @Override
    public void upsertSchedulerState(String instanceId, Instant checkin, Duration checkinInterval) {
        redis.opsForHash().put(keys.schedulers(), instanceId, checkin.toEpochMilli() + "," + checkinInterval.toMillis());
    }

    @Override
    public List<SchedulerStateRecord> selectSchedulerStateRecords() {
        List<SchedulerStateRecord> records = new ArrayList<>();
        hash(keys.schedulers()).forEach((instanceId, value) -> {
            String[] parts = value.split(",");
            records.add(new SchedulerStateRecord(instanceId, Instant.ofEpochMilli(Long.parseLong(parts[0])),
                    Duration.ofMillis(Long.parseLong(parts[1]))));
        });
        return records;
    }

    @Override
    public void deleteSchedulerState(String instanceId) {
        redis.opsForHash().delete(keys.schedulers(), instanceId);
    }

    @Override
    public void clearAllSchedulingData() {
        run(RedisScripts.CLEAR_ALL, keys.schedulers(), List.of(keys.prefix(), keys.pattern()));
    }
}
