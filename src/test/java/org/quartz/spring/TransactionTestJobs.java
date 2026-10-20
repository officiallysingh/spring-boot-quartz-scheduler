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
package org.quartz.spring;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.ExecuteInTransaction;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.Trigger;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Jobs and helpers shared by the transactional execution ITs. Every job writes one
 * {@code {job: <name>}} document to the {@value #BUSINESS} collection through the
 * application's {@link MongoTemplate}, records whether it ran in a transaction, and then
 * ends as its {@code outcome} job data entry says.
 */
final class TransactionTestJobs {

    static final String BUSINESS = "business";

    static final String OUTCOME = "outcome";

    static final String SUCCEED = "succeed";
    static final String JOB_EXCEPTION = "jobException";
    static final String RUNTIME_EXCEPTION = "runtimeException";
    static final String CHECKED_EXCEPTION = "checkedException";
    static final String EXCEED_TIMEOUT = "exceedTimeout";

    /** Job name to whether the job saw an active transaction. */
    static final Map<String, Boolean> IN_TRANSACTION = new ConcurrentHashMap<>();

    /** Values of "count" written by {@link CountingJob} executions, in order. */
    static final List<Integer> COUNTS = new CopyOnWriteArrayList<>();

    static final AtomicInteger EXECUTIONS = new AtomicInteger();

    private TransactionTestJobs() {
    }

    static void reset() {
        IN_TRANSACTION.clear();
        COUNTS.clear();
        EXECUTIONS.set(0);
    }

    public static class PlainJob implements Job {

        @Autowired
        MongoTemplate mongoTemplate;

        @Autowired(required = false)
        OffsetClock clock;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            String name = context.getJobDetail().getKey().getName();
            IN_TRANSACTION.put(name, TransactionSynchronizationManager.isActualTransactionActive());
            mongoTemplate.insert(new Document("job", name), BUSINESS);
            switch (Objects.requireNonNullElse(context.getMergedJobDataMap().getString(OUTCOME), SUCCEED)) {
                case JOB_EXCEPTION -> throw new JobExecutionException("job failed");
                case RUNTIME_EXCEPTION -> throw new IllegalStateException("job failed");
                case CHECKED_EXCEPTION -> throw TransactionTestJobs.<RuntimeException>sneakyThrow(new IOException("job failed"));
                case EXCEED_TIMEOUT -> clock.advance(Duration.ofSeconds(2));
                default -> {
                }
            }
        }
    }

    @ExecuteInTransaction
    public static class TransactionalJob extends PlainJob {
    }

    @ExecuteInTransaction(transactionManager = "auditTransactionManager")
    public static class AuditJob extends PlainJob {
    }

    @ExecuteInTransaction(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public static class RequiresNewJob extends PlainJob {
    }

    @ExecuteInTransaction(propagation = Propagation.NOT_SUPPORTED)
    public static class NotSupportedJob extends PlainJob {
    }

    @ExecuteInTransaction(propagation = Propagation.MANDATORY)
    public static class MandatoryJob extends PlainJob {
    }

    @ExecuteInTransaction(timeout = 1)
    public static class TimeoutJob extends PlainJob {
    }

    /**
     * Increments "count" in its persisted job data and writes a document; the first
     * execution (per test) then fails, later ones succeed.
     */
    @ExecuteInTransaction
    @PersistJobDataAfterExecution
    @DisallowConcurrentExecution
    public static class CountingJob implements Job {

        @Autowired
        MongoTemplate mongoTemplate;

        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            JobDataMap data = context.getJobDetail().getJobDataMap();
            int count = data.getInt("count") + 1;
            data.put("count", count);
            COUNTS.add(count);
            mongoTemplate.insert(new Document("job", context.getJobDetail().getKey().getName()), BUSINESS);
            if (EXECUTIONS.incrementAndGet() == 1) {
                throw new JobExecutionException("first execution fails");
            }
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T sneakyThrow(Throwable ex) throws T {
        throw (T) ex;
    }

    /** Records finished and vetoed executions by job name. */
    static class CompletionRecorder extends JobListenerSupport {

        final Map<String, Optional<JobExecutionException>> executed = new ConcurrentHashMap<>();

        final List<String> vetoed = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "completionRecorder";
        }

        @Override
        public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
            executed.put(context.getJobDetail().getKey().getName(), Optional.ofNullable(jobException));
        }

        @Override
        public void jobExecutionVetoed(JobExecutionContext context) {
            vetoed.add(context.getJobDetail().getKey().getName());
        }
    }

    /** Vetoes every job whose name starts with "vetoed". */
    static class VetoingTriggerListener extends TriggerListenerSupport {

        @Override
        public String getName() {
            return "vetoing";
        }

        @Override
        public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
            return trigger.getJobKey().getName().startsWith("vetoed");
        }
    }

    /** {@link MongoTransactionManager} that records the definitions it began transactions for. */
    static class RecordingMongoTransactionManager extends MongoTransactionManager {

        final List<TransactionDefinition> begun = new CopyOnWriteArrayList<>();

        RecordingMongoTransactionManager(MongoDatabaseFactory databaseFactory) {
            super(databaseFactory);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.add(definition);
            super.doBegin(transaction, definition);
        }

        List<String> begunNames() {
            return begun.stream().map(TransactionDefinition::getName).toList();
        }
    }

    /** The system clock plus an offset that tests can move forward. */
    static class OffsetClock extends Clock {

        private volatile Duration offset = Duration.ZERO;

        void advance(Duration amount) {
            offset = offset.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }
    }
}
