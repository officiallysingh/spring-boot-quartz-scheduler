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
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.quartz.JobPersistenceException;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.mongodb.MongoStoreDelegate.MongoCollections;
import org.quartz.impl.store.LeaseStoreLock;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.store.StoreDelegate;
import org.quartz.impl.store.StoreLock;
import org.quartz.impl.store.codec.JobDetailCodec;
import org.quartz.impl.store.codec.TriggerCodec;
import org.quartz.spi.ClassLoadHelper;

/**
 * A job store keeping all scheduling data in MongoDB.
 * <p>
 * Collections ({@code {prefix}jobs}, {@code triggers}, {@code calendars},
 * {@code paused_groups}, {@code fired_triggers}, {@code schedulers}, {@code locks}) carry the
 * scheduler name in every document, so several schedulers can share them; indexes are
 * created on startup. Every store primitive is a single atomic MongoDB operation, so
 * correctness never depends on multi-document transactions. When the deployment supports
 * them (replica set or sharded cluster), each store operation additionally runs in a
 * transaction, so a crash cannot leave it half-applied; trigger acquisition then always
 * takes the trigger lock to avoid write conflicts.
 * <p>
 * Clustered stores lock with lease documents in {@code {prefix}locks}
 * ({@code expiresAt} also has a TTL index as a safety net).
 */
public class MongoJobStore extends PersistentJobStore {

    private final MongoClient client;

    private final String databaseName;

    private final MongoJobStoreSettings mongoSettings;

    private final ThreadLocal<ClientSession> session = new ThreadLocal<>();

    private MongoCollections collections;

    private volatile boolean transactional;

    public MongoJobStore(MongoClient client, String databaseName, JobStoreSettings settings,
            MongoJobStoreSettings mongoSettings) {
        super(settings);
        this.client = Objects.requireNonNull(client, "client");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        this.mongoSettings = Objects.requireNonNull(mongoSettings, "mongoSettings");
    }

    public MongoJobStoreSettings getMongoSettings() {
        return mongoSettings;
    }

    /** Whether store operations run in MongoDB transactions (known after initialization). */
    public boolean isTransactional() {
        return transactional;
    }

    @Override
    protected StoreDelegate createDelegate(String schedulerName, ClassLoadHelper loadHelper) {
        collections = MongoCollections.of(client.getDatabase(databaseName), mongoSettings.collectionPrefix());
        return new MongoStoreDelegate(schedulerName, collections, loadHelper, session::get);
    }

    @Override
    protected StoreLock createClusterLock(String schedulerName, String owner) {
        return new MongoLeaseLock(collections.locks(), schedulerName, owner, mongoSettings.lockTimeout(),
                getSettings().acquireTriggersWithinLockRetry());
    }

    @Override
    protected void initializeStore() throws JobPersistenceException {
        try {
            createIndexes();
            transactional = supportsTransactions();
            log.info("MongoDB job store on database '{}' (prefix '{}'): transactions {}.", databaseName,
                    mongoSettings.collectionPrefix(), transactional ? "enabled" : "not supported by the deployment");
        }
        catch (MongoException ex) {
            throw new JobPersistenceException("Couldn't prepare MongoDB collections: " + ex.getMessage(), ex);
        }
    }

    private void createIndexes() {
        String sched = MongoStoreDelegate.SCHED_NAME;
        IndexOptions unique = new IndexOptions().unique(true);
        collections.jobs().createIndex(Indexes.ascending(sched, JobDetailCodec.GROUP, JobDetailCodec.NAME), unique);
        collections.triggers().createIndexes(List.of(
                new IndexModel(Indexes.ascending(sched, TriggerCodec.GROUP, TriggerCodec.NAME), unique),
                new IndexModel(Indexes.compoundIndex(Indexes.ascending(sched, MongoStoreDelegate.STATE,
                        TriggerCodec.NEXT_FIRE_TIME), Indexes.descending(TriggerCodec.PRIORITY))),
                new IndexModel(Indexes.ascending(sched, TriggerCodec.JOB_GROUP, TriggerCodec.JOB_NAME)),
                new IndexModel(Indexes.ascending(sched, TriggerCodec.CALENDAR_NAME))));
        collections.calendars().createIndex(Indexes.ascending(sched, TriggerCodec.NAME), unique);
        collections.pausedGroups().createIndex(
                Indexes.ascending(sched, MongoStoreDelegate.KIND, TriggerCodec.GROUP), unique);
        collections.firedTriggers().createIndexes(List.of(
                new IndexModel(Indexes.ascending(sched, MongoStoreDelegate.FIRE_INSTANCE_ID), unique),
                new IndexModel(Indexes.ascending(sched, MongoStoreDelegate.INSTANCE_ID)),
                new IndexModel(Indexes.ascending(sched, TriggerCodec.JOB_GROUP, TriggerCodec.JOB_NAME)),
                new IndexModel(Indexes.ascending(sched, MongoStoreDelegate.TRIGGER_GROUP,
                        MongoStoreDelegate.TRIGGER_NAME))));
        collections.schedulers().createIndex(Indexes.ascending(sched, MongoStoreDelegate.INSTANCE_ID), unique);
        collections.locks().createIndex(Indexes.ascending(MongoLeaseLock.EXPIRES_AT),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    private boolean supportsTransactions() {
        Document hello = client.getDatabase("admin").runCommand(new Document("hello", 1));
        return hello.containsKey("setName") || "isdbgrid".equals(hello.getString("msg"));
    }

    @Override
    protected boolean acquireTriggersWithinLock() {
        return transactional || super.acquireTriggersWithinLock();
    }

    @Override
    protected <T> T inTransaction(StoreCallback<T> callback) throws JobPersistenceException {
        if (!transactional || session.get() != null) {
            return callback.execute();
        }
        try (ClientSession clientSession = client.startSession()) {
            clientSession.startTransaction();
            session.set(clientSession);
            try {
                T result = callback.execute();
                commit(clientSession);
                return result;
            }
            catch (JobPersistenceException | RuntimeException ex) {
                if (clientSession.hasActiveTransaction()) {
                    clientSession.abortTransaction();
                }
                throw ex;
            }
            finally {
                session.remove();
            }
        }
    }

    private static void commit(ClientSession clientSession) {
        for (int attempt = 1; ; attempt++) {
            try {
                clientSession.commitTransaction();
                return;
            }
            catch (MongoException ex) {
                // a commit may be retried safely when its outcome is unknown; the operation itself is never re-run
                if (attempt >= 3 || !ex.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    throw ex;
                }
            }
        }
    }

    /** Lease lock documents: {@code _id = schedName/lockName}, owner and expiry. */
    static final class MongoLeaseLock extends LeaseStoreLock {

        static final String OWNER = "owner";
        static final String EXPIRES_AT = "expiresAt";

        private static final int DUPLICATE_KEY = 11000;

        private final MongoCollection<Document> locks;

        private final String schedName;

        MongoLeaseLock(MongoCollection<Document> locks, String schedName, String owner, Duration leaseTime,
                Duration retryInterval) {
            super(owner, leaseTime, retryInterval);
            this.locks = locks;
            this.schedName = schedName;
        }

        private String id(String lockName) {
            return schedName + "/" + lockName;
        }

        @Override
        protected boolean tryAcquire(String lockName, String owner, Duration leaseTime) {
            Date now = new Date();
            Date expiresAt = new Date(now.getTime() + leaseTime.toMillis());
            try {
                Document lock = locks.findOneAndUpdate(
                        and(eq("_id", id(lockName)), or(lt(EXPIRES_AT, now), eq(OWNER, owner))),
                        Updates.combine(Updates.set(OWNER, owner), Updates.set(EXPIRES_AT, expiresAt)),
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
                return lock != null && owner.equals(lock.getString(OWNER));
            }
            catch (MongoException ex) {
                if (ex.getCode() == DUPLICATE_KEY) {
                    return false; // held by another instance: the upsert collided with its document
                }
                throw ex;
            }
        }

        @Override
        protected void releaseLease(String lockName, String owner) {
            locks.deleteOne(and(eq("_id", id(lockName)), eq(OWNER, owner)));
        }
    }
}
