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
package org.quartz.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.SchedulerRepository;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.store.SchedulerStateRecord;
import org.quartz.simpl.DefaultClassLoadHelper;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;

/**
 * Contract tests every clustered {@link JobStore} must pass: several scheduler instances sharing
 * one store. Implementations provide {@link #createJobStore(String)} and {@link #cleanStore()}
 * only.
 *
 * <p>{@code createJobStore} must return a <em>clustered</em> store, configured with {@link
 * #clusteredSettings()} and a lock lease of at most {@link #MAX_LOCK_LEASE}, all instances sharing
 * the same backing data.
 */
public abstract class AbstractClusteredJobStoreContractTest {

  protected static final String SCHED_NAME = "ClusteredContractTestScheduler";

  /** The longest lock lease the lock-expiry test waits for. */
  protected static final Duration MAX_LOCK_LEASE = Duration.ofSeconds(3);

  static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

  static final Map<String, String> RECOVERED_ON = new ConcurrentHashMap<>();

  static volatile CountDownLatch blockedJobStarted = new CountDownLatch(1);

  static volatile CountDownLatch releaseBlockedJob = new CountDownLatch(1);

  private final List<Scheduler> schedulers = new ArrayList<>();

  private final List<JobStore> stores = new ArrayList<>();

  /**
   * Settings the stores under test must use: clustered, with check-ins frequent enough for failure
   * detection within about a second.
   */
  protected static JobStoreSettings clusteredSettings() {
    return JobStoreSettings.builder()
        .clustered(true)
        .clusterCheckinInterval(Duration.ofMillis(500))
        .clusterCheckinMisfireThreshold(Duration.ofMillis(500))
        .acquireTriggersWithinLockRetry(Duration.ofMillis(20))
        .build();
  }

  /** Creates a new, uninitialized clustered store sharing the backing data. */
  protected abstract JobStore createJobStore(String instanceId);

  /** Removes all data the stores under test keep, between tests. */
  protected abstract void cleanStore() throws Exception;

  @BeforeEach
  void setUp() throws Exception {
    cleanStore();
    EXECUTIONS.clear();
    RECOVERED_ON.clear();
    blockedJobStarted = new CountDownLatch(1);
    releaseBlockedJob = new CountDownLatch(1);
  }

  @AfterEach
  void tearDown() throws Exception {
    releaseBlockedJob.countDown();
    for (Scheduler scheduler : schedulers) {
      if (!scheduler.isShutdown()) {
        scheduler.shutdown(true);
      }
    }
    for (JobStore store : stores) {
      store.shutdown();
    }
    cleanStore();
  }

  /** Starts a scheduler instance on a new store. */
  protected Scheduler startScheduler(String instanceId) throws SchedulerException {
    JobStore store = createJobStore(instanceId);
    assertThat(store.isClustered()).as("createJobStore must return a clustered store").isTrue();
    Scheduler scheduler =
        StdSchedulerFactory.builder()
            .scheduler(
                SchedulerSettings.builder()
                    .name(SCHED_NAME)
                    .instanceId(instanceId)
                    .idleWaitTime(Duration.ofSeconds(1))
                    .batchTriggerAcquisitionMaxCount(5)
                    .batchTriggerAcquisitionFireAheadTimeWindow(Duration.ofMillis(50))
                    .build())
            .threadPool(ThreadPoolSettings.builder().threadCount(5).build())
            .jobStore(store)
            .build()
            .getScheduler();
    // the factory shares schedulers by name; every node here is its own instance
    SchedulerRepository.getInstance().remove(SCHED_NAME);
    schedulers.add(scheduler);
    scheduler.start();
    return scheduler;
  }

  /** Starts a bare store (no scheduler around it). */
  protected PersistentJobStore startStore(String instanceId) throws SchedulerException {
    JobStore store = createJobStore(instanceId);
    assumeTrue(
        store instanceof PersistentJobStore,
        "store-level cluster checks need a PersistentJobStore");
    store.setInstanceName(SCHED_NAME);
    store.setInstanceId(instanceId);
    ClassLoadHelper loadHelper = new DefaultClassLoadHelper();
    loadHelper.initialize();
    store.initialize(
        loadHelper, new org.quartz.store.AbstractJobStoreContractTest.SampleSignaler());
    store.schedulerStarted();
    stores.add(store);
    return (PersistentJobStore) store;
  }

  public static class CountingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      EXECUTIONS
          .computeIfAbsent(context.getJobDetail().getKey().getName(), k -> new AtomicInteger())
          .incrementAndGet();
    }
  }

  /** Blocks on its first run; records where it ran when recovered. */
  public static class BlockingRecoverableJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      if (context.isRecovering()) {
        RECOVERED_ON.put(context.getJobDetail().getKey().getName(), safeInstanceId(context));
        return;
      }
      blockedJobStarted.countDown();
      try {
        releaseBlockedJob.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }

    private static String safeInstanceId(JobExecutionContext context) {
      try {
        return context.getScheduler().getSchedulerInstanceId();
      } catch (SchedulerException ex) {
        return "unknown";
      }
    }
  }

  @Test
  void everyTriggerFiresExactlyOnceAcrossTheCluster() throws Exception {
    Scheduler nodeA = startScheduler("node-a");
    startScheduler("node-b");
    int jobCount = 200;
    Instant start = Instant.now().plusSeconds(1);
    for (int i = 0; i < jobCount; i++) {
      JobDetail job = JobBuilder.newJob(CountingJob.class).withIdentity("job" + i).build();
      Trigger trigger =
          TriggerBuilder.newTrigger()
              .withIdentity("trigger" + i)
              .startAt(start.plusMillis(i * 5L))
              .withSchedule(SimpleScheduleBuilder.simpleSchedule())
              .build();
      nodeA.scheduleJob(job, trigger);
    }

    await().atMost(Duration.ofSeconds(60)).until(() -> EXECUTIONS.size() == jobCount);
    // and nothing runs a second time afterwards
    await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(5))
        .until(() -> EXECUTIONS.values().stream().allMatch(count -> count.get() == 1));
    assertThat(EXECUTIONS.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(jobCount);
  }

  @Test
  void aCrashedNodesRecoverableJobIsRecoveredByAnotherNode() throws Exception {
    Scheduler nodeA = startScheduler("node-a");
    JobDetail job =
        JobBuilder.newJob(BlockingRecoverableJob.class)
            .withIdentity("recoverable")
            .requestRecovery()
            .storeDurably()
            .build();
    nodeA.scheduleJob(
        job, TriggerBuilder.newTrigger().withIdentity("recoverableTrigger").startNow().build());
    assertThat(blockedJobStarted.await(30, TimeUnit.SECONDS)).as("job started on node-a").isTrue();

    startScheduler("node-b");
    // "crash": node-a stops without waiting for its job and never reports completion
    nodeA.shutdown(false);

    await().atMost(Duration.ofSeconds(30)).until(() -> RECOVERED_ON.containsKey("recoverable"));
    assertThat(RECOVERED_ON.get("recoverable")).isEqualTo("node-b");
  }

  @Test
  void aNodeThatStopsCheckingInIsDetectedAndRemoved() throws Exception {
    PersistentJobStore storeA = startStore("node-a");
    PersistentJobStore storeB = startStore("node-b");
    assertThat(storeB.getSchedulerStates())
        .extracting(SchedulerStateRecord::instanceId)
        .contains("node-a", "node-b");

    storeA.shutdown(); // stops checking in

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                storeB.getSchedulerStates().stream()
                    .map(SchedulerStateRecord::instanceId)
                    .noneMatch("node-a"::equals));
    assertThat(storeB.getSchedulerStates())
        .extracting(SchedulerStateRecord::instanceId)
        .contains("node-b");
  }

  @Test
  void aLockHeldByADeadNodeExpires() throws Exception {
    PersistentJobStore storeA = startStore("node-a");
    PersistentJobStore storeB = startStore("node-b");

    // node-a takes the trigger lock and dies without releasing it
    Thread dying =
        new Thread(
            () -> {
              try {
                storeA.getLock().obtain(PersistentJobStore.LOCK_TRIGGER_ACCESS);
              } catch (JobPersistenceException ex) {
                throw new IllegalStateException(ex);
              }
            });
    dying.start();
    dying.join();

    CompletableFuture<Void> write =
        CompletableFuture.runAsync(
            () -> {
              try {
                storeB.storeJob(
                    JobBuilder.newJob(CountingJob.class)
                        .withIdentity("afterExpiry")
                        .storeDurably()
                        .build(),
                    false);
              } catch (JobPersistenceException ex) {
                throw new IllegalStateException(ex);
              }
            });

    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(1))
        .until(() -> !write.isDone());
    await().atMost(MAX_LOCK_LEASE.plusSeconds(5)).until(write::isDone);
    assertThat(write).isCompleted();
    assertThat(storeB.checkExists(org.quartz.JobKey.jobKey("afterExpiry"))).isTrue();
  }
}
