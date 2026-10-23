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

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.quartz.spring.TransactionTestJobs.BUSINESS;
import static org.quartz.spring.TransactionTestJobs.CHECKED_EXCEPTION;
import static org.quartz.spring.TransactionTestJobs.COUNTS;
import static org.quartz.spring.TransactionTestJobs.EXCEED_TIMEOUT;
import static org.quartz.spring.TransactionTestJobs.IN_TRANSACTION;
import static org.quartz.spring.TransactionTestJobs.JOB_EXCEPTION;
import static org.quartz.spring.TransactionTestJobs.OUTCOME;
import static org.quartz.spring.TransactionTestJobs.RUNTIME_EXCEPTION;
import static org.quartz.spring.TransactionTestJobs.SUCCEED;

import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.TriggerBuilder;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.mongodb.MongoJobStoreSettings;
import org.quartz.spi.OperableTrigger;
import org.quartz.spring.TransactionTestJobs.AuditJob;
import org.quartz.spring.TransactionTestJobs.CompletionRecorder;
import org.quartz.spring.TransactionTestJobs.CountingJob;
import org.quartz.spring.TransactionTestJobs.MandatoryJob;
import org.quartz.spring.TransactionTestJobs.NotSupportedJob;
import org.quartz.spring.TransactionTestJobs.OffsetClock;
import org.quartz.spring.TransactionTestJobs.PlainJob;
import org.quartz.spring.TransactionTestJobs.RecordingMongoTransactionManager;
import org.quartz.spring.TransactionTestJobs.RequiresNewJob;
import org.quartz.spring.TransactionTestJobs.TimeoutJob;
import org.quartz.spring.TransactionTestJobs.TransactionalJob;
import org.quartz.spring.TransactionTestJobs.VetoingTriggerListener;
import org.quartz.spring.boot.autoconfigure.QuartzAutoConfiguration;
import org.quartz.spring.boot.autoconfigure.QuartzProperties;
import org.quartz.spring.boot.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.quartz.support.AbstractMongoIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Jobs in Spring-managed {@code MongoTransactionManager} transactions on a scheduler with the
 * MongoDB job store. A fresh context per test, so the store re-creates its indexes after the
 * collections were dropped.
 */
@SpringBootTest(
    classes = SpringTransactionJobExecutionIT.Config.class,
    properties = {"spring.quartz.job-store.type=mongodb", "spring.quartz.scheduler.name=txApp"})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class SpringTransactionJobExecutionIT extends AbstractMongoIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Autowired private Scheduler scheduler;

  @Autowired private CompletionRecorder recorder;

  @Autowired
  @Qualifier("transactionManager")
  private RecordingMongoTransactionManager transactionManager;

  @Autowired
  @Qualifier("auditTransactionManager")
  private RecordingMongoTransactionManager auditTransactionManager;

  @Autowired private RecordingMongoJobStore jobStore;

  @BeforeEach
  void reset() {
    TransactionTestJobs.reset();
  }

  @Test
  void successfulJobIsCommitted() throws Exception {
    Optional<JobExecutionException> result = run(TransactionalJob.class, "commit", SUCCEED);

    assertThat(result).isEmpty();
    assertThat(IN_TRANSACTION).containsEntry("commit", true);
    assertThat(businessDocuments("commit")).isEqualTo(1);
    assertThat(transactionManager.begunNames()).containsExactly("DEFAULT.commit");
  }

  @Test
  void jobExecutionExceptionRollsBack() throws Exception {
    Optional<JobExecutionException> result = run(TransactionalJob.class, "failing", JOB_EXCEPTION);

    assertThat(result).get().extracting(Throwable::getMessage).isEqualTo("job failed");
    assertThat(IN_TRANSACTION).containsEntry("failing", true);
    assertThat(businessDocuments("failing")).isZero();
  }

  @Test
  void runtimeExceptionRollsBack() throws Exception {
    Optional<JobExecutionException> result =
        run(TransactionalJob.class, "throwing", RUNTIME_EXCEPTION);

    assertThat(result)
        .get()
        .satisfies((ex) -> assertThat(ex).hasRootCauseInstanceOf(IllegalStateException.class));
    assertThat(businessDocuments("throwing")).isZero();
  }

  @Test
  void checkedExceptionOutsideRollbackForCommitsAsWithTransactional() throws Exception {
    Optional<JobExecutionException> result =
        run(TransactionalJob.class, "checked", CHECKED_EXCEPTION);

    assertThat(result).isPresent();
    assertThat(businessDocuments("checked")).isEqualTo(1);
  }

  @Test
  void transactionManagerIsSelectedByBeanName() throws Exception {
    run(AuditJob.class, "audited", JOB_EXCEPTION);

    assertThat(auditTransactionManager.begunNames()).containsExactly("DEFAULT.audited");
    assertThat(transactionManager.begun).isEmpty();
    assertThat(businessDocuments("audited")).isZero();
  }

  @Test
  void propagationAndTimeoutReachTheTransactionManager() throws Exception {
    run(RequiresNewJob.class, "requiresNew", SUCCEED);

    assertThat(transactionManager.begun)
        .singleElement()
        .satisfies(
            (definition) -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getTimeout()).isEqualTo(30);
            });
    assertThat(IN_TRANSACTION).containsEntry("requiresNew", true);
    assertThat(businessDocuments("requiresNew")).isEqualTo(1);
  }

  @Test
  void notSupportedRunsWithoutTransactionSoWritesSurviveAFailure() throws Exception {
    run(NotSupportedJob.class, "notSupported", JOB_EXCEPTION);

    assertThat(IN_TRANSACTION).containsEntry("notSupported", false);
    assertThat(transactionManager.begun).isEmpty();
    assertThat(businessDocuments("notSupported")).isEqualTo(1);
  }

  @Test
  void mandatoryWithoutOuterTransactionFailsTheJobBeforeItRuns() throws Exception {
    Optional<JobExecutionException> result = run(MandatoryJob.class, "mandatory", SUCCEED);

    assertThat(result)
        .get()
        .satisfies(
            (ex) -> assertThat(ex).hasRootCauseInstanceOf(IllegalTransactionStateException.class));
    assertThat(IN_TRANSACTION).doesNotContainKey("mandatory");
  }

  @Test
  void timeoutRollsBackAndFailsTheJob() throws Exception {
    Optional<JobExecutionException> result = run(TimeoutJob.class, "slow", EXCEED_TIMEOUT);

    assertThat(result)
        .get()
        .satisfies(
            (ex) -> assertThat(ex).hasRootCauseInstanceOf(TransactionTimedOutException.class));
    assertThat(businessDocuments("slow")).isZero();
  }

  @Test
  void jobWithoutAnnotationRunsWithoutTransactionWhenTheSwitchIsOff() throws Exception {
    run(PlainJob.class, "plain", RUNTIME_EXCEPTION);

    assertThat(IN_TRANSACTION).containsEntry("plain", false);
    assertThat(transactionManager.begun).isEmpty();
    assertThat(businessDocuments("plain")).isEqualTo(1);
  }

  @Test
  void vetoedExecutionOpensNoTransaction() throws Exception {
    scheduler.scheduleJob(job(TransactionalJob.class, "vetoedJob", SUCCEED), now("vetoedJob"));

    await().atMost(TIMEOUT).until(() -> recorder.vetoed.contains("vetoedJob"));
    assertThat(transactionManager.begun).isEmpty();
    assertThat(IN_TRANSACTION).doesNotContainKey("vetoedJob");
  }

  @Test
  void transactionIsCompleteBeforeTheStoreHearsOfTheCompletion() throws Exception {
    run(TransactionalJob.class, "ordered", SUCCEED);

    await()
        .atMost(TIMEOUT)
        .until(() -> jobStore.businessDocumentsAtCompletion.containsKey("ordered"));
    assertThat(jobStore.businessDocumentsAtCompletion).containsEntry("ordered", 1L);
  }

  @Test
  void persistedJobDataJoinsTheMongoTransaction() throws Exception {
    JobDetail job =
        JobBuilder.newJob(CountingJob.class)
            .withIdentity("counting")
            .usingJobData("count", 0)
            .storeDurably()
            .build();
    scheduler.scheduleJob(
        job,
        TriggerBuilder.newTrigger()
            .withIdentity("counting")
            .startNow()
            .withSchedule(
                SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInMilliseconds(100)
                    .withRepeatCount(1))
            .build());

    // the first execution fails: its document and its job-data change are rolled back together,
    // so the second execution starts again from count 0
    await().atMost(TIMEOUT).until(() -> COUNTS.size() == 2);
    assertThat(COUNTS).containsExactly(1, 1);
    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(scheduler.getJobDetail(job.getKey()).getJobDataMap().getInt("count"))
                    .isEqualTo(1));
    assertThat(businessDocuments("counting")).isEqualTo(1);
  }

  private Optional<JobExecutionException> run(
      Class<? extends Job> jobClass, String name, String outcome) throws Exception {
    scheduler.scheduleJob(job(jobClass, name, outcome), now(name));
    await().atMost(TIMEOUT).until(() -> recorder.executed.containsKey(name));
    return recorder.executed.get(name);
  }

  private static JobDetail job(Class<? extends Job> jobClass, String name, String outcome) {
    return JobBuilder.newJob(jobClass).withIdentity(name).usingJobData(OUTCOME, outcome).build();
  }

  private static org.quartz.Trigger now(String name) {
    return TriggerBuilder.newTrigger().withIdentity(name).startNow().build();
  }

  static long businessDocuments(String job) {
    return mongoClient().getDatabase("test").getCollection(BUSINESS).countDocuments(eq("job", job));
  }

  /**
   * Records how many business documents of a job were visible when the store heard of its
   * completion.
   */
  static class RecordingMongoJobStore extends MongoJobStore {

    final Map<String, Long> businessDocumentsAtCompletion = new ConcurrentHashMap<>();

    RecordingMongoJobStore(MongoClient client, QuartzProperties properties) {
      super(
          client,
          "test",
          properties.jobStore().toSettings(),
          new MongoJobStoreSettings(
              properties.jobStore().mongodb().collectionPrefix(),
              properties.jobStore().mongodb().lockTimeout()));
    }

    @Override
    public void triggeredJobComplete(
        OperableTrigger trigger,
        JobDetail jobDetail,
        CompletedExecutionInstruction triggerInstCode) {
      JobKey key = jobDetail.getKey();
      businessDocumentsAtCompletion.put(key.getName(), businessDocuments(key.getName()));
      super.triggeredJobComplete(trigger, jobDetail, triggerInstCode);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    MongoAutoConfiguration.class,
    DataMongoAutoConfiguration.class,
    QuartzAutoConfiguration.class
  })
  static class Config {

    @Bean
    @Primary
    RecordingMongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
      return new RecordingMongoTransactionManager(databaseFactory);
    }

    @Bean
    RecordingMongoTransactionManager auditTransactionManager(MongoDatabaseFactory databaseFactory) {
      return new RecordingMongoTransactionManager(databaseFactory);
    }

    @Bean
    CompletionRecorder completionRecorder() {
      return new CompletionRecorder();
    }

    @Bean
    VetoingTriggerListener vetoingTriggerListener() {
      return new VetoingTriggerListener();
    }

    @Bean
    OffsetClock clock() {
      return new OffsetClock();
    }

    @Bean
    RecordingMongoJobStore recordingMongoJobStore(
        MongoClient mongoClient, QuartzProperties properties) {
      return new RecordingMongoJobStore(mongoClient, properties);
    }

    @Bean
    SchedulerFactoryBeanCustomizer recordingStore(RecordingMongoJobStore recordingMongoJobStore) {
      return (schedulerFactoryBean) -> schedulerFactoryBean.setJobStore(recordingMongoJobStore);
    }
  }
}
