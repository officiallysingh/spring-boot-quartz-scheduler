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
package org.quartz.impl.mongodb;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.or;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.jspecify.annotations.Nullable;
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

/**
 * {@link StoreDelegate} over MongoDB collections. Every primitive is a single MongoDB
 * operation (state transitions are conditional updates); when the store runs an operation
 * in a transaction, the current {@link ClientSession} is used.
 */
class MongoStoreDelegate implements StoreDelegate {

    static final String SCHED_NAME = "schedName";
    static final String STATE = "state";
    static final String JOB_DATA = "jobData";
    static final String JOB_DATA_BLOB = "jobDataBlob";
    static final String KIND = "kind";
    static final String CALENDAR = "calendar";
    static final String FIRE_INSTANCE_ID = "fireInstanceId";
    static final String INSTANCE_ID = "instanceId";
    static final String TRIGGER_NAME = "triggerName";
    static final String TRIGGER_GROUP = "triggerGroup";
    static final String FIRED_TIME = "firedTime";
    static final String SCHEDULED_TIME = "scheduledTime";
    static final String LAST_CHECKIN = "lastCheckin";
    static final String CHECKIN_INTERVAL = "checkinInterval";

    static final String KIND_TRIGGER = "trigger";
    static final String KIND_JOB = "job";

    private final String schedName;

    private final MongoCollections collections;

    private final @Nullable ClassLoadHelper loadHelper;

    private final Supplier<@Nullable ClientSession> session;

    MongoStoreDelegate(String schedName, MongoCollections collections, @Nullable ClassLoadHelper loadHelper,
            Supplier<@Nullable ClientSession> session) {
        this.schedName = schedName;
        this.collections = collections;
        this.loadHelper = loadHelper;
        this.session = session;
    }

    /** The collections of a store, by name. */
    record MongoCollections(MongoCollection<Document> jobs, MongoCollection<Document> triggers,
            MongoCollection<Document> calendars, MongoCollection<Document> pausedGroups,
            MongoCollection<Document> firedTriggers, MongoCollection<Document> schedulers,
            MongoCollection<Document> locks) {

        static MongoCollections of(MongoDatabase database, String prefix) {
            return new MongoCollections(database.getCollection(prefix + "jobs"),
                    database.getCollection(prefix + "triggers"), database.getCollection(prefix + "calendars"),
                    database.getCollection(prefix + "paused_groups"), database.getCollection(prefix + "fired_triggers"),
                    database.getCollection(prefix + "schedulers"), database.getCollection(prefix + "locks"));
        }
    }

    // ------------------------------------------------------------------
    // session-aware helpers
    // ------------------------------------------------------------------

    private Bson sched() {
        return eq(SCHED_NAME, schedName);
    }

    private Bson sched(Bson filter) {
        return and(sched(), filter);
    }

    private Bson key(String group, String name) {
        return and(sched(), eq(TriggerCodec.GROUP, group), eq(TriggerCodec.NAME, name));
    }

    private Bson jobKey(JobKey key) {
        return key(key.getGroup(), key.getName());
    }

    private Bson triggerKey(TriggerKey key) {
        return key(key.getGroup(), key.getName());
    }

    private Bson triggersOfJob(JobKey key) {
        return and(sched(), eq(TriggerCodec.JOB_GROUP, key.getGroup()), eq(TriggerCodec.JOB_NAME, key.getName()));
    }

    private FindIterable<Document> find(MongoCollection<Document> collection, Bson filter) {
        ClientSession s = session.get();
        return (s != null) ? collection.find(s, filter) : collection.find(filter);
    }

    private ClassLoadHelper classLoadHelper() {
        return Objects.requireNonNull(this.loadHelper, "loadHelper");
    }

    private @Nullable Document first(MongoCollection<Document> collection, Bson filter) {
        return find(collection, filter).first();
    }

    private long count(MongoCollection<Document> collection, Bson filter) {
        ClientSession s = session.get();
        return (s != null) ? collection.countDocuments(s, filter) : collection.countDocuments(filter);
    }

    private void insert(MongoCollection<Document> collection, Document document) {
        ClientSession s = session.get();
        if (s != null) {
            collection.insertOne(s, document);
        }
        else {
            collection.insertOne(document);
        }
    }

    private void replace(MongoCollection<Document> collection, Bson filter, Document document) {
        ClientSession s = session.get();
        ReplaceOptions options = new ReplaceOptions().upsert(true);
        if (s != null) {
            collection.replaceOne(s, filter, document, options);
        }
        else {
            collection.replaceOne(filter, document, options);
        }
    }

    private long updateOne(MongoCollection<Document> collection, Bson filter, Bson update) {
        ClientSession s = session.get();
        return ((s != null) ? collection.updateOne(s, filter, update) : collection.updateOne(filter, update))
            .getModifiedCount();
    }

    private long updateMany(MongoCollection<Document> collection, Bson filter, Bson update) {
        ClientSession s = session.get();
        return ((s != null) ? collection.updateMany(s, filter, update) : collection.updateMany(filter, update))
            .getModifiedCount();
    }

    private long deleteOne(MongoCollection<Document> collection, Bson filter) {
        ClientSession s = session.get();
        return ((s != null) ? collection.deleteOne(s, filter) : collection.deleteOne(filter)).getDeletedCount();
    }

    private long deleteMany(MongoCollection<Document> collection, Bson filter) {
        ClientSession s = session.get();
        return ((s != null) ? collection.deleteMany(s, filter) : collection.deleteMany(filter)).getDeletedCount();
    }

    private List<String> distinct(MongoCollection<Document> collection, String field, Bson filter) {
        ClientSession s = session.get();
        List<String> values = new ArrayList<>();
        ((s != null) ? collection.distinct(s, field, filter, String.class)
                : collection.distinct(field, filter, String.class)).into(values);
        return values;
    }

    // ------------------------------------------------------------------
    // encoding
    // ------------------------------------------------------------------

    private void putJobData(Document document, @Nullable JobDataMap data) throws JobPersistenceException {
        if (data == null || data.isEmpty()) {
            document.put(JOB_DATA, new Document());
        }
        else if (JobDataCodec.isNative(data)) {
            document.put(JOB_DATA, new Document(new LinkedHashMap<>(data.getWrappedMap())));
        }
        else {
            document.put(JOB_DATA_BLOB, new Binary(JobDataCodec.serialize(data)));
        }
    }

    private JobDataMap jobData(Document document) throws JobPersistenceException {
        Binary blob = document.get(JOB_DATA_BLOB, Binary.class);
        if (blob != null) {
            return JobDataCodec.deserialize(blob.getData(), classLoadHelper());
        }
        return JobDataCodec.fromNative(document.get(JOB_DATA, Document.class));
    }

    /** Field map for the codecs: BSON binaries become byte arrays. */
    private static Map<String, Object> fields(Document document) {
        Map<String, Object> fields = new LinkedHashMap<>(document);
        fields.replaceAll((name, value) -> (value instanceof Binary binary) ? binary.getData() : value);
        return fields;
    }

    private static TriggerKey toTriggerKey(Document document) {
        return new TriggerKey(document.getString(TriggerCodec.NAME), document.getString(TriggerCodec.GROUP));
    }

    private static JobKey toJobKey(Document document) {
        return new JobKey(document.getString(JobDetailCodec.NAME), document.getString(JobDetailCodec.GROUP));
    }

    private List<TriggerKey> triggerKeys(FindIterable<Document> documents) {
        List<TriggerKey> keys = new ArrayList<>();
        for (Document document : documents.projection(Projections.include(TriggerCodec.NAME, TriggerCodec.GROUP))) {
            keys.add(toTriggerKey(document));
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // jobs
    // ------------------------------------------------------------------

    private Document jobDocument(JobDetail job) throws JobPersistenceException {
        Document document = new Document(SCHED_NAME, schedName);
        document.putAll(JobDetailCodec.encode(job));
        putJobData(document, job.getJobDataMap());
        return document;
    }

    @Override
    public void insertJob(JobDetail job) throws JobPersistenceException {
        insert(collections.jobs(), jobDocument(job));
    }

    @Override
    public void updateJob(JobDetail job) throws JobPersistenceException {
        replace(collections.jobs(), jobKey(job.getKey()), jobDocument(job));
    }

    @Override
    public void updateJobData(JobDetail job) throws JobPersistenceException {
        Document data = new Document();
        putJobData(data, job.getJobDataMap());
        Bson update = data.containsKey(JOB_DATA_BLOB)
                ? Updates.combine(Updates.set(JOB_DATA_BLOB, data.get(JOB_DATA_BLOB)), Updates.unset(JOB_DATA))
                : Updates.combine(Updates.set(JOB_DATA, data.get(JOB_DATA)), Updates.unset(JOB_DATA_BLOB));
        updateOne(collections.jobs(), jobKey(job.getKey()), update);
    }

    @Override
    public boolean jobExists(JobKey jobKey) {
        return count(collections.jobs(), jobKey(jobKey)) > 0;
    }

    @Override
    public @Nullable JobDetail selectJob(JobKey jobKey) throws JobPersistenceException {
        Document document = first(collections.jobs(), jobKey(jobKey));
        return (document != null) ? JobDetailCodec.decode(fields(document), jobData(document), classLoadHelper()) : null;
    }

    @Override
    public boolean deleteJob(JobKey jobKey) {
        return deleteOne(collections.jobs(), jobKey(jobKey)) > 0;
    }

    @Override
    public int selectNumJobs() {
        return (int) count(collections.jobs(), sched());
    }

    @Override
    public List<String> selectJobGroups() {
        return distinct(collections.jobs(), JobDetailCodec.GROUP, sched());
    }

    @Override
    public Set<JobKey> selectJobKeys(String group) {
        Set<JobKey> keys = new LinkedHashSet<>();
        for (Document document : find(collections.jobs(), sched(eq(JobDetailCodec.GROUP, group)))
            .projection(Projections.include(JobDetailCodec.NAME, JobDetailCodec.GROUP))) {
            keys.add(toJobKey(document));
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // triggers
    // ------------------------------------------------------------------

    private Document triggerDocument(OperableTrigger trigger, String state) throws JobPersistenceException {
        Document document = new Document(SCHED_NAME, schedName);
        Map<String, Object> fields = TriggerCodec.encode(trigger);
        fields.replaceAll((name, value) -> (value instanceof byte[] bytes) ? new Binary(bytes) : value);
        document.putAll(fields);
        document.put(STATE, state);
        putJobData(document, trigger.getJobDataMap());
        return document;
    }

    @Override
    public void insertTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        insert(collections.triggers(), triggerDocument(trigger, state));
    }

    @Override
    public void updateTrigger(OperableTrigger trigger, String state) throws JobPersistenceException {
        replace(collections.triggers(), triggerKey(trigger.getKey()), triggerDocument(trigger, state));
    }

    @Override
    public boolean triggerExists(TriggerKey triggerKey) {
        return count(collections.triggers(), triggerKey(triggerKey)) > 0;
    }

    @Override
    public @Nullable OperableTrigger selectTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        Document document = first(collections.triggers(), triggerKey(triggerKey));
        return (document != null) ? TriggerCodec.decode(fields(document), jobData(document), classLoadHelper()) : null;
    }

    @Override
    public JobDataMap selectTriggerJobData(TriggerKey triggerKey) throws JobPersistenceException {
        Document document = first(collections.triggers(), triggerKey(triggerKey));
        return (document != null) ? jobData(document) : new JobDataMap();
    }

    @Override
    public @Nullable String selectTriggerState(TriggerKey triggerKey) {
        Document document = find(collections.triggers(), triggerKey(triggerKey))
            .projection(Projections.include(STATE)).first();
        return (document != null) ? document.getString(STATE) : null;
    }

    @Override
    public boolean deleteTrigger(TriggerKey triggerKey) {
        return deleteOne(collections.triggers(), triggerKey(triggerKey)) > 0;
    }

    @Override
    public int selectNumTriggers() {
        return (int) count(collections.triggers(), sched());
    }

    @Override
    public int selectNumTriggersForJob(JobKey jobKey) {
        return (int) count(collections.triggers(), triggersOfJob(jobKey));
    }

    @Override
    public List<String> selectTriggerGroups() {
        return distinct(collections.triggers(), TriggerCodec.GROUP, sched());
    }

    @Override
    public Set<TriggerKey> selectTriggerKeys(String group) {
        return new LinkedHashSet<>(triggerKeys(find(collections.triggers(), sched(eq(TriggerCodec.GROUP, group)))));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForJob(JobKey jobKey) {
        return triggerKeys(find(collections.triggers(), triggersOfJob(jobKey)));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysForCalendar(String calendarName) {
        return triggerKeys(find(collections.triggers(), sched(eq(TriggerCodec.CALENDAR_NAME, calendarName))));
    }

    @Override
    public List<TriggerKey> selectTriggerKeysInState(String state) {
        return triggerKeys(find(collections.triggers(), sched(eq(STATE, state))));
    }

    @Override
    public int updateTriggerState(TriggerKey triggerKey, String newState) {
        return (int) updateOne(collections.triggers(), triggerKey(triggerKey), Updates.set(STATE, newState));
    }

    @Override
    public int updateTriggerStateFromOtherStates(TriggerKey triggerKey, String newState, Collection<String> oldStates) {
        return (int) updateOne(collections.triggers(), and(triggerKey(triggerKey), in(STATE, oldStates)),
                Updates.set(STATE, newState));
    }

    @Override
    public int updateTriggerGroupStateFromOtherStates(String group, String newState, Collection<String> oldStates) {
        return (int) updateMany(collections.triggers(), sched(and(eq(TriggerCodec.GROUP, group), in(STATE, oldStates))),
                Updates.set(STATE, newState));
    }

    @Override
    public int updateTriggerStatesForJob(JobKey jobKey, String newState) {
        return (int) updateMany(collections.triggers(), triggersOfJob(jobKey), Updates.set(STATE, newState));
    }

    @Override
    public int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState) {
        return (int) updateMany(collections.triggers(), and(triggersOfJob(jobKey), eq(STATE, oldState)),
                Updates.set(STATE, newState));
    }

    @Override
    public int updateTriggerStatesFromOtherStates(String newState, Collection<String> oldStates) {
        return (int) updateMany(collections.triggers(), sched(in(STATE, oldStates)), Updates.set(STATE, newState));
    }

    private static final Bson FIRE_ORDER = Sorts.orderBy(Sorts.ascending(TriggerCodec.NEXT_FIRE_TIME),
            Sorts.descending(TriggerCodec.PRIORITY));

    @Override
    public List<TriggerKey> selectTriggersToAcquire(Instant noLaterThan, Instant misfireTime, int maxCount) {
        Bson filter = sched(and(eq(STATE, PersistentJobStore.STATE_WAITING),
                lte(TriggerCodec.NEXT_FIRE_TIME, noLaterThan.toEpochMilli()),
                or(eq(TriggerCodec.MISFIRE_INSTRUCTION, Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY),
                        gte(TriggerCodec.NEXT_FIRE_TIME, misfireTime.toEpochMilli()))));
        return triggerKeys(find(collections.triggers(), filter).sort(FIRE_ORDER).limit(Math.max(maxCount, 1)));
    }

    private Bson misfired(String state, Instant misfireTime) {
        return sched(and(eq(STATE, state),
                ne(TriggerCodec.MISFIRE_INSTRUCTION, Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY),
                lt(TriggerCodec.NEXT_FIRE_TIME, misfireTime.toEpochMilli())));
    }

    @Override
    public List<TriggerKey> selectMisfiredTriggersInState(String state, Instant misfireTime, int limit) {
        FindIterable<Document> found = find(collections.triggers(), misfired(state, misfireTime)).sort(FIRE_ORDER);
        if (limit >= 0) {
            found = found.limit(limit);
        }
        return triggerKeys(found);
    }

    @Override
    public int countMisfiredTriggersInState(String state, Instant misfireTime) {
        return (int) count(collections.triggers(), misfired(state, misfireTime));
    }

    // ------------------------------------------------------------------
    // calendars
    // ------------------------------------------------------------------

    private Bson calendar(String name) {
        return sched(eq(TriggerCodec.NAME, name));
    }

    @Override
    public void insertCalendar(String name, Calendar calendar) throws JobPersistenceException {
        insert(collections.calendars(), new Document(SCHED_NAME, schedName).append(TriggerCodec.NAME, name)
            .append(CALENDAR, new Binary(SerializationCodec.serialize(calendar))));
    }

    @Override
    public void updateCalendar(String name, Calendar calendar) throws JobPersistenceException {
        updateOne(collections.calendars(), calendar(name),
                Updates.set(CALENDAR, new Binary(SerializationCodec.serialize(calendar))));
    }

    @Override
    public boolean calendarExists(String name) {
        return count(collections.calendars(), calendar(name)) > 0;
    }

    @Override
    public @Nullable Calendar selectCalendar(String name) throws JobPersistenceException {
        Document document = first(collections.calendars(), calendar(name));
        return (document != null)
                ? SerializationCodec.deserialize(document.get(CALENDAR, Binary.class).getData(), Calendar.class,
                        classLoadHelper())
                : null;
    }

    @Override
    public boolean deleteCalendar(String name) {
        return deleteOne(collections.calendars(), calendar(name)) > 0;
    }

    @Override
    public List<String> selectCalendarNames() {
        return distinct(collections.calendars(), TriggerCodec.NAME, sched());
    }

    @Override
    public int selectNumCalendars() {
        return (int) count(collections.calendars(), sched());
    }

    // ------------------------------------------------------------------
    // paused groups
    // ------------------------------------------------------------------

    private Bson pausedGroup(String kind, String group) {
        return sched(and(eq(KIND, kind), eq(TriggerCodec.GROUP, group)));
    }

    private void insertPausedGroup(String kind, String group) {
        replace(collections.pausedGroups(), pausedGroup(kind, group),
                new Document(SCHED_NAME, schedName).append(KIND, kind).append(TriggerCodec.GROUP, group));
    }

    private Set<String> pausedGroups(String kind) {
        return new LinkedHashSet<>(distinct(collections.pausedGroups(), TriggerCodec.GROUP, sched(eq(KIND, kind))));
    }

    @Override
    public void insertPausedTriggerGroup(String group) {
        insertPausedGroup(KIND_TRIGGER, group);
    }

    @Override
    public void deletePausedTriggerGroup(String group) {
        deleteOne(collections.pausedGroups(), pausedGroup(KIND_TRIGGER, group));
    }

    @Override
    public boolean isTriggerGroupPaused(String group) {
        return count(collections.pausedGroups(), pausedGroup(KIND_TRIGGER, group)) > 0;
    }

    @Override
    public Set<String> selectPausedTriggerGroups() {
        return pausedGroups(KIND_TRIGGER);
    }

    @Override
    public void insertPausedJobGroup(String group) {
        insertPausedGroup(KIND_JOB, group);
    }

    @Override
    public void deletePausedJobGroup(String group) {
        deleteOne(collections.pausedGroups(), pausedGroup(KIND_JOB, group));
    }

    @Override
    public boolean isJobGroupPaused(String group) {
        return count(collections.pausedGroups(), pausedGroup(KIND_JOB, group)) > 0;
    }

    @Override
    public Set<String> selectPausedJobGroups() {
        return pausedGroups(KIND_JOB);
    }

    // ------------------------------------------------------------------
    // fired triggers
    // ------------------------------------------------------------------

    private Document firedDocument(FiredTriggerRecord record) {
        return new Document(SCHED_NAME, schedName)
            .append(FIRE_INSTANCE_ID, record.fireInstanceId())
            .append(INSTANCE_ID, record.instanceId())
            .append(TRIGGER_GROUP, record.triggerKey().getGroup())
            .append(TRIGGER_NAME, record.triggerKey().getName())
            .append(TriggerCodec.JOB_GROUP, record.jobKey().getGroup())
            .append(TriggerCodec.JOB_NAME, record.jobKey().getName())
            .append(STATE, record.state())
            .append(FIRED_TIME, record.firedTime().toEpochMilli())
            .append(SCHEDULED_TIME, record.scheduledTime().toEpochMilli())
            .append(TriggerCodec.PRIORITY, record.priority())
            .append(JobDetailCodec.NON_CONCURRENT, record.nonConcurrent())
            .append(JobDetailCodec.REQUESTS_RECOVERY, record.requestsRecovery());
    }

    private static FiredTriggerRecord toFiredRecord(Document document) {
        return new FiredTriggerRecord(document.getString(FIRE_INSTANCE_ID), document.getString(INSTANCE_ID),
                new TriggerKey(document.getString(TRIGGER_NAME), document.getString(TRIGGER_GROUP)),
                new JobKey(document.getString(TriggerCodec.JOB_NAME), document.getString(TriggerCodec.JOB_GROUP)),
                document.getString(STATE), Instant.ofEpochMilli(document.getLong(FIRED_TIME)),
                Instant.ofEpochMilli(document.getLong(SCHEDULED_TIME)), document.getInteger(TriggerCodec.PRIORITY),
                document.getBoolean(JobDetailCodec.NON_CONCURRENT), document.getBoolean(JobDetailCodec.REQUESTS_RECOVERY));
    }

    private Bson fired(String fireInstanceId) {
        return sched(eq(FIRE_INSTANCE_ID, fireInstanceId));
    }

    private List<FiredTriggerRecord> firedRecords(Bson filter) {
        List<FiredTriggerRecord> records = new ArrayList<>();
        for (Document document : find(collections.firedTriggers(), filter)) {
            records.add(toFiredRecord(document));
        }
        return records;
    }

    @Override
    public void insertFiredTrigger(FiredTriggerRecord record) {
        insert(collections.firedTriggers(), firedDocument(record));
    }

    @Override
    public void updateFiredTrigger(FiredTriggerRecord record) {
        updateOne(collections.firedTriggers(), fired(record.fireInstanceId()), Updates.combine(
                Updates.set(INSTANCE_ID, record.instanceId()), Updates.set(STATE, record.state()),
                Updates.set(FIRED_TIME, record.firedTime().toEpochMilli()),
                Updates.set(TriggerCodec.JOB_GROUP, record.jobKey().getGroup()),
                Updates.set(TriggerCodec.JOB_NAME, record.jobKey().getName()),
                Updates.set(JobDetailCodec.NON_CONCURRENT, record.nonConcurrent()),
                Updates.set(JobDetailCodec.REQUESTS_RECOVERY, record.requestsRecovery())));
    }

    @Override
    public void deleteFiredTrigger(String fireInstanceId) {
        deleteOne(collections.firedTriggers(), fired(fireInstanceId));
    }

    @Override
    public int deleteFiredTriggers() {
        return (int) deleteMany(collections.firedTriggers(), sched());
    }

    @Override
    public int deleteFiredTriggers(String instanceId) {
        return (int) deleteMany(collections.firedTriggers(), sched(eq(INSTANCE_ID, instanceId)));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForInstance(String instanceId) {
        return firedRecords(sched(eq(INSTANCE_ID, instanceId)));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForJob(JobKey jobKey) {
        return firedRecords(sched(and(eq(TriggerCodec.JOB_GROUP, jobKey.getGroup()),
                eq(TriggerCodec.JOB_NAME, jobKey.getName()))));
    }

    @Override
    public List<FiredTriggerRecord> selectFiredTriggerRecordsForTrigger(TriggerKey triggerKey) {
        return firedRecords(sched(and(eq(TRIGGER_GROUP, triggerKey.getGroup()), eq(TRIGGER_NAME, triggerKey.getName()))));
    }

    @Override
    public Set<String> selectFiredTriggerInstanceIds() {
        return new LinkedHashSet<>(distinct(collections.firedTriggers(), INSTANCE_ID, sched()));
    }

    // ------------------------------------------------------------------
    // scheduler states
    // ------------------------------------------------------------------

    @Override
    public void upsertSchedulerState(String instanceId, Instant checkin, Duration checkinInterval) {
        replace(collections.schedulers(), sched(eq(INSTANCE_ID, instanceId)),
                new Document(SCHED_NAME, schedName).append(INSTANCE_ID, instanceId)
                    .append(LAST_CHECKIN, checkin.toEpochMilli()).append(CHECKIN_INTERVAL, checkinInterval.toMillis()));
    }

    @Override
    public List<SchedulerStateRecord> selectSchedulerStateRecords() {
        List<SchedulerStateRecord> records = new ArrayList<>();
        for (Document document : find(collections.schedulers(), sched())) {
            records.add(new SchedulerStateRecord(document.getString(INSTANCE_ID),
                    Instant.ofEpochMilli(document.getLong(LAST_CHECKIN)),
                    Duration.ofMillis(document.getLong(CHECKIN_INTERVAL))));
        }
        return records;
    }

    @Override
    public void deleteSchedulerState(String instanceId) {
        deleteOne(collections.schedulers(), sched(eq(INSTANCE_ID, instanceId)));
    }

    @Override
    public void clearAllSchedulingData() {
        deleteMany(collections.jobs(), sched());
        deleteMany(collections.triggers(), sched());
        deleteMany(collections.calendars(), sched());
        deleteMany(collections.pausedGroups(), sched());
        deleteMany(collections.firedTriggers(), sched());
    }
}
