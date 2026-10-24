/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.quartz.DailyTimeIntervalScheduleBuilder.MONDAY_THROUGH_FRIDAY;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.DateBuilder;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.PersistJobDataAfterExecution;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.simpl.CascadingClassLoadHelper;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;

/**
 * Contract tests every {@link JobStore} must pass (standalone). The first cases were submitted by
 * Johannes Zillmann as part of issue QUARTZ-306 (formerly {@code AbstractJobStoreTest}); the rest
 * were added with the persistent stores.
 *
 * <p>Implementations only provide {@link #createJobStore(String)} and {@link #cleanStore()}; the
 * kit has no knowledge of where a store keeps its data.
 */
public abstract class AbstractJobStoreContractTest {
  private JobStore fJobStore;
  private JobDetailImpl fJobDetail;
  private SampleSignaler fSignaler;

  /** Scheduler name every store under test is bound to. */
  protected static final String SCHED_NAME = "ContractTestScheduler";

  /** Instance id of the store under test. */
  protected static final String INSTANCE_ID = "node-1";

  /**
   * Creates a new, uninitialized store instance. Persistent stores created by this method share
   * their backing data; the kit sets the scheduler name and instance id and initializes them.
   */
  protected abstract JobStore createJobStore(String instanceId);

  /** Removes all data the stores under test keep, between tests. */
  protected abstract void cleanStore() throws Exception;

  @BeforeEach
  protected void setUp() throws Exception {
    cleanStore();
    this.fSignaler = new SampleSignaler();
    this.fJobStore = startStore(INSTANCE_ID, this.fSignaler);

    this.fJobDetail =
        (JobDetailImpl) JobBuilder.newJob(MyJob.class).withIdentity("job1", "jobGroup1").build();
    this.fJobDetail.setDurability(true);
    this.fJobStore.storeJob(this.fJobDetail, false);
  }

  @AfterEach
  protected void tearDown() throws Exception {
    this.fJobStore.shutdown();
    cleanStore();
  }

  /** Creates, names, initializes and starts a store. */
  protected JobStore startStore(String instanceId, SchedulerSignaler signaler)
      throws SchedulerException {
    JobStore store = createJobStore(instanceId);
    store.setInstanceName(SCHED_NAME);
    store.setInstanceId(instanceId);
    ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
    loadHelper.initialize();
    store.initialize(loadHelper, signaler);
    store.schedulerStarted();
    return store;
  }

  /** The store under test, emptied (the shared fixture job is removed too). */
  protected JobStore emptyStore() throws JobPersistenceException {
    this.fJobStore.clearAllSchedulingData();
    return this.fJobStore;
  }

  protected void applyMisfires(JobStore jobStore, long noLaterThan) throws Exception {
    if (jobStore instanceof PersistentJobStore persistent) {
      persistent.recoverMisfiredTriggers();
    } else {
      jobStore.acquireNextTriggers(Instant.ofEpochMilli(noLaterThan), 1, Duration.ofMillis(0L));
    }
  }

  @Test
  void testMisfireUsesSchedulingTimeFromSignaler() throws Exception {
    Instant triggerTime = Instant.ofEpochMilli(System.currentTimeMillis() + 86_400_000L);
    Instant schedulingTime = Instant.ofEpochMilli(triggerTime.toEpochMilli() + 300_000L);
    OperableTrigger trigger =
        simpleTrigger(
            "brokerMisfire",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            triggerTime,
            null,
            0,
            Duration.ofMillis(0L));
    trigger.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);
    trigger.computeFirstFireTime(null);
    this.fJobStore.storeTrigger(trigger, false);

    this.fSignaler.setCurrentTime(schedulingTime);

    applyMisfires(this.fJobStore, schedulingTime.toEpochMilli() + 1_000L);

    assertEquals(
        schedulingTime, this.fJobStore.retrieveTrigger(trigger.getKey()).getNextFireTime());
  }

  @SuppressWarnings("deprecation")
  @Test
  void testAcquireNextTrigger() throws Exception {

    Instant baseFireTimeDate = DateBuilder.evenMinuteDateAfterNow();
    long baseFireTime = baseFireTimeDate.toEpochMilli();

    OperableTrigger trigger1 =
        simpleTrigger(
            "trigger1",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 200000),
            Instant.ofEpochMilli(baseFireTime + 200000),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger2 =
        simpleTrigger(
            "trigger2",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 50000),
            Instant.ofEpochMilli(baseFireTime + 200000),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger3 =
        simpleTrigger(
            "trigger1",
            "triggerGroup2",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 100000),
            Instant.ofEpochMilli(baseFireTime + 200000),
            2,
            Duration.ofMillis(2000));

    trigger1.computeFirstFireTime(null);
    trigger2.computeFirstFireTime(null);
    trigger3.computeFirstFireTime(null);
    this.fJobStore.storeTrigger(trigger1, false);
    this.fJobStore.storeTrigger(trigger2, false);
    this.fJobStore.storeTrigger(trigger3, false);

    long firstFireTime =
        Instant.ofEpochMilli(trigger1.getNextFireTime().toEpochMilli()).toEpochMilli();

    assertTrue(
        this.fJobStore
            .acquireNextTriggers(Instant.ofEpochMilli(10), 1, Duration.ofMillis(0L))
            .isEmpty());
    assertEquals(
        trigger2.getKey(),
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(firstFireTime + 10000), 1, Duration.ofMillis(0L))
            .get(0)
            .getKey());
    assertEquals(
        trigger3.getKey(),
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(firstFireTime + 10000), 1, Duration.ofMillis(0L))
            .get(0)
            .getKey());
    assertEquals(
        trigger1.getKey(),
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(firstFireTime + 10000), 1, Duration.ofMillis(0L))
            .get(0)
            .getKey());
    assertTrue(
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(firstFireTime + 10000), 1, Duration.ofMillis(0L))
            .isEmpty());

    // release trigger3
    this.fJobStore.releaseAcquiredTrigger(trigger3);
    assertEquals(
        trigger3,
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(
                    Instant.ofEpochMilli(trigger1.getNextFireTime().toEpochMilli()).toEpochMilli()
                        + 10000),
                1,
                Duration.ofMillis(1L))
            .get(0));
  }

  @SuppressWarnings("deprecation")
  @Test
  void testAcquireNextTriggerBatch() throws Exception {

    long baseFireTime = System.currentTimeMillis() - 1000;

    OperableTrigger early =
        simpleTrigger(
            "early",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime),
            Instant.ofEpochMilli(baseFireTime + 5),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger1 =
        simpleTrigger(
            "trigger1",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 200000),
            Instant.ofEpochMilli(baseFireTime + 200005),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger2 =
        simpleTrigger(
            "trigger2",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 210000),
            Instant.ofEpochMilli(baseFireTime + 210005),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger3 =
        simpleTrigger(
            "trigger3",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 220000),
            Instant.ofEpochMilli(baseFireTime + 220005),
            2,
            Duration.ofMillis(2000));
    OperableTrigger trigger4 =
        simpleTrigger(
            "trigger4",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 230000),
            Instant.ofEpochMilli(baseFireTime + 230005),
            2,
            Duration.ofMillis(2000));

    OperableTrigger trigger10 =
        simpleTrigger(
            "trigger10",
            "triggerGroup2",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 500000),
            Instant.ofEpochMilli(baseFireTime + 700000),
            2,
            Duration.ofMillis(2000));

    early.computeFirstFireTime(null);
    early.setMisfireInstruction(Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY);
    trigger1.computeFirstFireTime(null);
    trigger2.computeFirstFireTime(null);
    trigger3.computeFirstFireTime(null);
    trigger4.computeFirstFireTime(null);
    trigger10.computeFirstFireTime(null);
    this.fJobStore.storeTrigger(early, false);
    this.fJobStore.storeTrigger(trigger1, false);
    this.fJobStore.storeTrigger(trigger2, false);
    this.fJobStore.storeTrigger(trigger3, false);
    this.fJobStore.storeTrigger(trigger4, false);
    this.fJobStore.storeTrigger(trigger10, false);

    long firstFireTime =
        Instant.ofEpochMilli(trigger1.getNextFireTime().toEpochMilli()).toEpochMilli();

    List<OperableTrigger> acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 10000), 4, Duration.ofMillis(1000L));
    assertEquals(1, acquiredTriggers.size());
    assertEquals(early.getKey(), acquiredTriggers.get(0).getKey());
    this.fJobStore.releaseAcquiredTrigger(early);

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 10000), 4, Duration.ofMillis(205000));
    assertEquals(2, acquiredTriggers.size());
    assertEquals(early.getKey(), acquiredTriggers.get(0).getKey());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(1).getKey());
    this.fJobStore.releaseAcquiredTrigger(early);
    this.fJobStore.releaseAcquiredTrigger(trigger1);

    this.fJobStore.removeTrigger(early.getKey());

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 10000), 5, Duration.ofMillis(100000L));
    assertEquals(4, acquiredTriggers.size());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(0).getKey());
    assertEquals(trigger2.getKey(), acquiredTriggers.get(1).getKey());
    assertEquals(trigger3.getKey(), acquiredTriggers.get(2).getKey());
    assertEquals(trigger4.getKey(), acquiredTriggers.get(3).getKey());
    this.fJobStore.releaseAcquiredTrigger(trigger1);
    this.fJobStore.releaseAcquiredTrigger(trigger2);
    this.fJobStore.releaseAcquiredTrigger(trigger3);
    this.fJobStore.releaseAcquiredTrigger(trigger4);

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 10000), 6, Duration.ofMillis(100000L));
    assertEquals(4, acquiredTriggers.size());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(0).getKey());
    assertEquals(trigger2.getKey(), acquiredTriggers.get(1).getKey());
    assertEquals(trigger3.getKey(), acquiredTriggers.get(2).getKey());
    assertEquals(trigger4.getKey(), acquiredTriggers.get(3).getKey());
    this.fJobStore.releaseAcquiredTrigger(trigger1);
    this.fJobStore.releaseAcquiredTrigger(trigger2);
    this.fJobStore.releaseAcquiredTrigger(trigger3);
    this.fJobStore.releaseAcquiredTrigger(trigger4);

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 1), 5, Duration.ofMillis(0L));
    assertEquals(1, acquiredTriggers.size());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(0).getKey());
    this.fJobStore.releaseAcquiredTrigger(trigger1);

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 250), 5, Duration.ofMillis(19999L));
    assertEquals(2, acquiredTriggers.size());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(0).getKey());
    assertEquals(trigger2.getKey(), acquiredTriggers.get(1).getKey());
    this.fJobStore.releaseAcquiredTrigger(trigger1);
    this.fJobStore.releaseAcquiredTrigger(trigger2);
    this.fJobStore.releaseAcquiredTrigger(trigger3);

    acquiredTriggers =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 150), 5, Duration.ofMillis(5000L));
    assertEquals(1, acquiredTriggers.size());
    assertEquals(trigger1.getKey(), acquiredTriggers.get(0).getKey());
    this.fJobStore.releaseAcquiredTrigger(trigger1);
  }

  @SuppressWarnings("deprecation")
  @Test
  void testTriggerStates() throws Exception {
    OperableTrigger trigger =
        simpleTrigger(
            "trigger1",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(System.currentTimeMillis() + 100000),
            Instant.ofEpochMilli(System.currentTimeMillis() + 200000),
            2,
            Duration.ofMillis(2000));
    trigger.computeFirstFireTime(null);
    assertEquals(TriggerState.NONE, this.fJobStore.getTriggerState(trigger.getKey()));
    this.fJobStore.storeTrigger(trigger, false);
    assertEquals(TriggerState.NORMAL, this.fJobStore.getTriggerState(trigger.getKey()));

    this.fJobStore.pauseTrigger(trigger.getKey());
    assertEquals(TriggerState.PAUSED, this.fJobStore.getTriggerState(trigger.getKey()));

    this.fJobStore.resumeTrigger(trigger.getKey());
    assertEquals(TriggerState.NORMAL, this.fJobStore.getTriggerState(trigger.getKey()));

    trigger =
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(
                    Instant.ofEpochMilli(trigger.getNextFireTime().toEpochMilli()).toEpochMilli()
                        + 10000),
                1,
                Duration.ofMillis(1L))
            .get(0);
    assertNotNull(trigger);
    this.fJobStore.releaseAcquiredTrigger(trigger);
    trigger =
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(
                    Instant.ofEpochMilli(trigger.getNextFireTime().toEpochMilli()).toEpochMilli()
                        + 10000),
                1,
                Duration.ofMillis(1L))
            .get(0);
    assertNotNull(trigger);
    assertTrue(
        this.fJobStore
            .acquireNextTriggers(
                Instant.ofEpochMilli(
                    Instant.ofEpochMilli(trigger.getNextFireTime().toEpochMilli()).toEpochMilli()
                        + 10000),
                1,
                Duration.ofMillis(1L))
            .isEmpty());
  }

  // See: http://jira.opensymphony.com/browse/QUARTZ-606 (was @Disabled upstream; its
  // message/expected
  // arguments had been swapped, the store behaviour itself is correct)
  @SuppressWarnings("deprecation")
  @Test
  void testStoreTriggerReplacesTrigger() throws Exception {

    String jobName = "StoreTriggerReplacesTrigger";
    String jobGroup = "StoreTriggerReplacesTriggerGroup";
    JobDetailImpl detail =
        (JobDetailImpl) JobBuilder.newJob(MyJob.class).withIdentity(jobName, jobGroup).build();
    fJobStore.storeJob(detail, false);

    String trName = "StoreTriggerReplacesTrigger";
    String trGroup = "StoreTriggerReplacesTriggerGroup";
    OperableTrigger tr =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger()
                .withIdentity(trName, trGroup)
                .startAt(Instant.now())
                .build();
    tr.setJobKey(new JobKey(jobName, jobGroup));
    tr.setCalendarName(null);

    fJobStore.storeTrigger(tr, false);
    assertEquals(tr, fJobStore.retrieveTrigger(tr.getKey()));

    try {
      fJobStore.storeTrigger(tr, false);
      fail("an attempt to store duplicate trigger succeeded");
    } catch (ObjectAlreadyExistsException oaee) {
      // expected
    }

    tr.setCalendarName("QQ");
    fJobStore.storeTrigger(tr, true); // fails here
    assertEquals(tr, fJobStore.retrieveTrigger(tr.getKey()));
    assertEquals(
        "QQ",
        fJobStore.retrieveTrigger(tr.getKey()).getCalendarName(),
        "StoreJob doesn't replace triggers");
  }

  @SuppressWarnings("deprecation")
  @Test
  void testPauseJobGroupPausesNewJob() throws Exception {
    final String jobName1 = "PauseJobGroupPausesNewJob";
    final String jobName2 = "PauseJobGroupPausesNewJob2";
    final String jobGroup = "PauseJobGroupPausesNewJobGroup";

    JobDetailImpl detail =
        (JobDetailImpl) JobBuilder.newJob(MyJob.class).withIdentity(jobName1, jobGroup).build();
    detail.setDurability(true);
    fJobStore.storeJob(detail, false);
    fJobStore.pauseJobs(GroupMatcher.jobGroupEquals(jobGroup));

    detail =
        (JobDetailImpl) JobBuilder.newJob(MyJob.class).withIdentity(jobName2, jobGroup).build();
    detail.setDurability(true);
    fJobStore.storeJob(detail, false);

    String trName = "PauseJobGroupPausesNewJobTrigger";
    String trGroup = "PauseJobGroupPausesNewJobTriggerGroup";
    OperableTrigger tr =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger()
                .withIdentity(trName, trGroup)
                .startAt(Instant.now())
                .build();
    tr.setJobKey(new JobKey(jobName2, jobGroup));
    fJobStore.storeTrigger(tr, false);
    assertEquals(TriggerState.PAUSED, fJobStore.getTriggerState(tr.getKey()));
  }

  @Test
  void testStoreAndRetrieveJobs() throws Exception {
    JobStore store = emptyStore();

    // Store jobs.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i, group).build();
      store.storeJob(job, false);
    }
    // Retrieve jobs.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobKey jobKey = JobKey.jobKey("job" + i, group);
      JobDetail storedJob = store.retrieveJob(jobKey);
      assertEquals(jobKey, storedJob.getKey());
    }
    // Retrieve by group
    assertEquals(
        5,
        store.getJobKeys(GroupMatcher.jobGroupEquals("a")).size(),
        "Wrong number of jobs in group 'a'");
    assertEquals(
        5,
        store.getJobKeys(GroupMatcher.jobGroupEquals("b")).size(),
        "Wrong number of jobs in group 'b'");
  }

  @Test
  void testStoreAndRetrieveJobsGroups() throws Exception {
    JobStore store = emptyStore();

    List<JobDetail> expectedJobs = new ArrayList<>(10);
    // these will NOT be in order because of hashmap
    // Store jobs.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i, group).build();
      store.storeJob(job, false);
      expectedJobs.add(job);
    }
    List<JobKey> expectedKey =
        expectedJobs.stream().map(JobDetail::getKey).collect(Collectors.toList());
    List<JobDetail> actual = store.getJobDetails(GroupMatcher.anyGroup());
    Set<JobKey> jobKeys = actual.stream().map(JobDetail::getKey).collect(Collectors.toSet());
    // Retrieve jobs.
    for (int i = 0; i < 10; i++) {
      assertTrue(jobKeys.contains(actual.get(i).getKey()));
      // assertEquals(expectedJobs.get(i).getKey(), actual.get(i).getKey(), "Job does not matche
      // expected");
    }

    List<JobDetail> listA = store.getJobDetails(GroupMatcher.jobGroupEquals("a"));
    jobKeys = listA.stream().map(JobDetail::getKey).collect(Collectors.toSet());
    assertEquals(5, listA.size(), "Wrong number of jobs in group 'a'");
    for (int i = 0; i < 5; i++) {
      assertTrue(jobKeys.contains(expectedKey.get(i)));
    }
    List<JobDetail> listB = store.getJobDetails(GroupMatcher.jobGroupEquals("b"));
    jobKeys = listB.stream().map(JobDetail::getKey).collect(Collectors.toSet());

    // Retrieve by group
    assertEquals(5, listB.size(), "Wrong number of jobs in group 'b'");
    for (int i = 5; i < 10; i++) {
      assertTrue(jobKeys.contains(expectedKey.get(i)));
    }
  }

  private JobStore createVariedTriggers() throws JobPersistenceException, SchedulerConfigException {
    JobStore store = emptyStore();

    // Store jobs and triggers.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i, group).build();
      store.storeJob(job, true);
      Trigger trigger;
      if (i == 0 || i == 5) {
        trigger =
            TriggerBuilder.newTrigger()
                .withIdentity("job" + i, group)
                .withSchedule(
                    CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                        .withIntervalInDays(1))
                .forJob(job)
                .build();
      } else if (i == 1 || i == 6) {
        trigger =
            TriggerBuilder.newTrigger()
                .withIdentity("job" + i, group)
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 12 * * ?"))
                .forJob(job)
                .build();
      } else if (i == 2 || i == 7) {
        trigger =
            TriggerBuilder.newTrigger()
                .withIdentity("job" + i, group)
                .withSchedule(
                    DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                        .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
                        .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(17, 0))
                        .onDaysOfTheWeek(MONDAY_THROUGH_FRIDAY)
                        .withIntervalInHours(1))
                .forJob(job)
                .build();
      } else if (i == 3 || i == 8) {
        trigger =
            TriggerBuilder.newTrigger()
                .withIdentity("job" + i, group)
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMinutes(15)
                        .repeatForever())
                .forJob(job)
                .build();
      } else {
        continue;
      }
      store.storeTrigger((OperableTrigger) trigger, true);
    }
    return store;
  }

  @Test
  void testStoreAndRetrieveTriggers() throws Exception {
    JobStore store = emptyStore();

    // Store jobs and triggers.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i, group).build();
      store.storeJob(job, true);
      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule();
      Trigger trigger =
          TriggerBuilder.newTrigger()
              .withIdentity("job" + i, group)
              .withSchedule(schedule)
              .forJob(job)
              .build();
      store.storeTrigger((OperableTrigger) trigger, true);
    }
    // Retrieve job and trigger.
    for (int i = 0; i < 10; i++) {
      String group = i < 5 ? "a" : "b";
      JobKey jobKey = JobKey.jobKey("job" + i, group);
      JobDetail storedJob = store.retrieveJob(jobKey);
      assertEquals(jobKey, storedJob.getKey());

      TriggerKey triggerKey = TriggerKey.triggerKey("job" + i, group);
      Trigger storedTrigger = store.retrieveTrigger(triggerKey);
      assertEquals(triggerKey, storedTrigger.getKey());
    }
    // Retrieve by group
    assertEquals(
        5,
        store.getJobKeys(GroupMatcher.jobGroupEquals("a")).size(),
        "Wrong number of jobs in group 'a'");
    assertEquals(
        5,
        store.getJobKeys(GroupMatcher.jobGroupEquals("b")).size(),
        "Wrong number of jobs in group 'b'");
  }

  @TestFactory
  Stream<DynamicTest> testEnhancedTriggerAndJobMatchers() throws Exception {
    final JobStore store = createVariedTriggers();
    final String A_GROUP = "a";
    final String B_GROUP = "b";
    return Stream.of(
        dynamicTest(
            "Get Group Jobs Enhanced",
            () -> {
              assertEquals(
                  5,
                  store.getJobKeys(GroupMatcher.jobGroupEquals(A_GROUP)).size(),
                  "Wrong number of jobs in group: " + A_GROUP);
              assertEquals(
                  5,
                  store.getJobKeys(GroupMatcher.jobGroupEquals(B_GROUP)).size(),
                  "Wrong number of jobs in group: " + B_GROUP);
            }),
        dynamicTest(
            "Enhanced Trigger and JobMatcher test",
            () -> {
              for (int i = 0; i < 10; i++) {
                String group = i < 5 ? A_GROUP : B_GROUP;
                JobKey jobKey = JobKey.jobKey("job" + i, group);
                JobDetail storedJob = store.retrieveJob(jobKey);
                assertEquals(jobKey, storedJob.getKey());

                TriggerKey triggerKey = TriggerKey.triggerKey("job" + i, group);
                Trigger storedTrigger = store.retrieveTrigger(triggerKey);
                if (i != 4 && i != 9) {
                  assertEquals(triggerKey, storedTrigger.getKey());
                } else {
                  assertNull(storedTrigger);
                }
              }
            }),
        dynamicTest(
            "testStoreAndRetrieveTriggersJustTriggerGroupMatchers",
            () -> {
              // TODO: get by matcher
              List<OperableTrigger> aTriggers =
                  store.getTriggersByTriggerGroup(GroupMatcher.triggerGroupEquals(A_GROUP));
              List<OperableTrigger> bTriggers =
                  store.getTriggersByTriggerGroup(GroupMatcher.triggerGroupEquals(B_GROUP));
              assertEquals(4, aTriggers.size(), "Wrong number of jobs in group: " + A_GROUP);
              assertEquals(4, bTriggers.size(), "Wrong number of jobs in group: " + B_GROUP);
            }),
        dynamicTest(
            "Match exact - only Job Group",
            () -> {
              // TODO: get by matcher
              List<OperableTrigger> aTriggers =
                  store.getTriggersByJobGroup(GroupMatcher.jobGroupEquals(A_GROUP));
              List<OperableTrigger> bTriggers =
                  store.getTriggersByJobGroup(GroupMatcher.jobGroupEquals(B_GROUP));
              assertEquals(4, aTriggers.size(), "Wrong number of jobs in group: " + A_GROUP);
              assertEquals(4, bTriggers.size(), "Wrong number of jobs in group: " + B_GROUP);
            }),
        dynamicTest(
            "Match exact both",
            () -> {
              List<OperableTrigger> aTriggers =
                  store.getTriggersByJobAndTriggerGroup(
                      GroupMatcher.jobGroupEquals(A_GROUP),
                      GroupMatcher.triggerGroupEquals(A_GROUP));
              List<OperableTrigger> bTriggers =
                  store.getTriggersByJobAndTriggerGroup(
                      GroupMatcher.jobGroupEquals(B_GROUP),
                      GroupMatcher.triggerGroupEquals(B_GROUP));
              assertEquals(4, aTriggers.size(), "Wrong number of jobs in group: " + A_GROUP);
              assertEquals(4, bTriggers.size(), "Wrong number of jobs in group: " + B_GROUP);
            }));
  }

  @Test
  void testMatchers() throws Exception {
    JobStore store = emptyStore();

    JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job1", "aaabbbccc").build();
    store.storeJob(job, true);
    SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule();
    Trigger trigger =
        TriggerBuilder.newTrigger()
            .withIdentity("trig1", "aaabbbccc")
            .withSchedule(schedule)
            .forJob(job)
            .build();
    store.storeTrigger((OperableTrigger) trigger, true);

    job = JobBuilder.newJob(MyJob.class).withIdentity("job1", "xxxyyyzzz").build();
    store.storeJob(job, true);
    schedule = SimpleScheduleBuilder.simpleSchedule();
    trigger =
        TriggerBuilder.newTrigger()
            .withIdentity("trig1", "xxxyyyzzz")
            .withSchedule(schedule)
            .forJob(job)
            .build();
    store.storeTrigger((OperableTrigger) trigger, true);

    job = JobBuilder.newJob(MyJob.class).withIdentity("job2", "xxxyyyzzz").build();
    store.storeJob(job, true);
    schedule = SimpleScheduleBuilder.simpleSchedule();
    trigger =
        TriggerBuilder.newTrigger()
            .withIdentity("trig2", "xxxyyyzzz")
            .withSchedule(schedule)
            .forJob(job)
            .build();
    store.storeTrigger((OperableTrigger) trigger, true);

    Set<JobKey> jkeys = store.getJobKeys(GroupMatcher.anyJobGroup());
    assertEquals(3, jkeys.size(), "Wrong number of jobs found by anything matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupEquals("xxxyyyzzz"));
    assertEquals(2, jkeys.size(), "Wrong number of jobs found by equals matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupEquals("aaabbbccc"));
    assertEquals(1, jkeys.size(), "Wrong number of jobs found by equals matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupStartsWith("aa"));
    assertEquals(1, jkeys.size(), "Wrong number of jobs found by starts with matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupStartsWith("xx"));
    assertEquals(2, jkeys.size(), "Wrong number of jobs found by starts with matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupEndsWith("cc"));
    assertEquals(1, jkeys.size(), "Wrong number of jobs found by ends with matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupEndsWith("zzz"));
    assertEquals(2, jkeys.size(), "Wrong number of jobs found by ends with matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupContains("bc"));
    assertEquals(1, jkeys.size(), "Wrong number of jobs found by contains with matcher");

    jkeys = store.getJobKeys(GroupMatcher.jobGroupContains("yz"));
    assertEquals(2, jkeys.size(), "Wrong number of jobs found by contains with matcher");

    Set<TriggerKey> tkeys = store.getTriggerKeys(GroupMatcher.anyTriggerGroup());
    assertEquals(3, tkeys.size(), "Wrong number of triggers found by anything matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupEquals("xxxyyyzzz"));
    assertEquals(2, tkeys.size(), "Wrong number of triggers found by equals matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupEquals("aaabbbccc"));
    assertEquals(1, tkeys.size(), "Wrong number of triggers found by equals matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupStartsWith("aa"));
    assertEquals(1, tkeys.size(), "Wrong number of triggers found by starts with matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupStartsWith("xx"));
    assertEquals(2, tkeys.size(), "Wrong number of triggers found by starts with matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupEndsWith("cc"));
    assertEquals(1, tkeys.size(), "Wrong number of triggers found by ends with matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupEndsWith("zzz"));
    assertEquals(2, tkeys.size(), "Wrong number of triggers found by ends with matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupContains("bc"));
    assertEquals(1, tkeys.size(), "Wrong number of triggers found by contains with matcher");

    tkeys = store.getTriggerKeys(GroupMatcher.triggerGroupContains("yz"));
    assertEquals(2, tkeys.size(), "Wrong number of triggers found by contains with matcher");
  }

  @Test
  void testAcquireTriggers() throws Exception {
    JobStore store = emptyStore();

    // Setup: Store jobs and triggers.
    long MIN = 60 * 1000L;
    Instant startTime0 = Instant.ofEpochMilli(System.currentTimeMillis() + MIN); // a min from now.
    for (int i = 0; i < 10; i++) {
      Instant startTime = Instant.ofEpochMilli(startTime0.toEpochMilli() + i * MIN); // a min apart
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
      OperableTrigger trigger =
          (OperableTrigger)
              TriggerBuilder.newTrigger()
                  .withIdentity("job" + i)
                  .withSchedule(schedule)
                  .forJob(job)
                  .startAt(startTime)
                  .build();

      // Manually trigger the first fire time computation that scheduler would do. Otherwise
      // the store.acquireNextTriggers() will not work properly.
      Instant fireTime = trigger.computeFirstFireTime(null);
      assertNotNull(fireTime);

      store.storeJobAndTrigger(job, trigger);
    }

    // Test acquire one trigger at a time
    for (int i = 0; i < 10; i++) {
      long noLaterThan = (startTime0.toEpochMilli() + i * MIN);
      int maxCount = 1;
      long timeWindow = 0;
      List<OperableTrigger> triggers =
          store.acquireNextTriggers(
              Instant.ofEpochMilli(noLaterThan), maxCount, Duration.ofMillis(timeWindow));
      assertEquals(1, triggers.size());
      assertEquals("job" + i, triggers.get(0).getKey().getName());

      // Let's remove the trigger now.
      store.removeJob(triggers.get(0).getJobKey());
    }
  }

  @Test
  void testAcquireTriggersInBatch() throws Exception {
    JobStore store = emptyStore();

    // Setup: Store jobs and triggers.
    long MIN = 60 * 1000L;
    Instant startTime0 = Instant.ofEpochMilli(System.currentTimeMillis() + MIN); // a min from now.
    for (int i = 0; i < 10; i++) {
      Instant startTime = Instant.ofEpochMilli(startTime0.toEpochMilli() + i * MIN); // a min apart
      JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("job" + i).build();
      SimpleScheduleBuilder schedule = SimpleScheduleBuilder.repeatMinutelyForever(2);
      OperableTrigger trigger =
          (OperableTrigger)
              TriggerBuilder.newTrigger()
                  .withIdentity("job" + i)
                  .withSchedule(schedule)
                  .forJob(job)
                  .startAt(startTime)
                  .build();

      // Manually trigger the first fire time computation that scheduler would do. Otherwise
      // the store.acquireNextTriggers() will not work properly.
      Instant fireTime = trigger.computeFirstFireTime(null);
      assertNotNull(fireTime);

      store.storeJobAndTrigger(job, trigger);
    }

    // Test acquire batch of triggers at a time
    long noLaterThan = startTime0.toEpochMilli() + 10 * MIN;
    int maxCount = 7;
    // time window needs to be big to be able to pick up multiple triggers when they are a minute
    // apart
    long timeWindow = 8 * MIN;
    List<OperableTrigger> triggers =
        store.acquireNextTriggers(
            Instant.ofEpochMilli(noLaterThan), maxCount, Duration.ofMillis(timeWindow));
    assertEquals(7, triggers.size());
    for (int i = 0; i < 7; i++) {
      assertEquals("job" + i, triggers.get(i).getKey().getName());
    }
  }

  @Test
  void testResetErrorTrigger() throws Exception {

    Instant baseFireTimeDate = DateBuilder.evenMinuteDateAfterNow();
    long baseFireTime = baseFireTimeDate.toEpochMilli();

    // create and store a trigger
    OperableTrigger trigger1 =
        simpleTrigger(
            "trigger1",
            "triggerGroup1",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            Instant.ofEpochMilli(baseFireTime + 200000),
            Instant.ofEpochMilli(baseFireTime + 200000),
            2,
            Duration.ofMillis(2000));

    trigger1.computeFirstFireTime(null);
    this.fJobStore.storeTrigger(trigger1, false);

    long firstFireTime =
        Instant.ofEpochMilli(trigger1.getNextFireTime().toEpochMilli()).toEpochMilli();

    // pretend to fire it
    List<OperableTrigger> aqTs =
        this.fJobStore.acquireNextTriggers(
            Instant.ofEpochMilli(firstFireTime + 10000), 1, Duration.ofMillis(0L));
    assertEquals(trigger1.getKey(), aqTs.get(0).getKey());

    List<TriggerFiredResult> fTs = this.fJobStore.triggersFired(aqTs);
    TriggerFiredResult ft = fTs.get(0);

    // get the trigger into error state
    this.fJobStore.triggeredJobComplete(
        ft.getTriggerFiredBundle().getTrigger(),
        ft.getTriggerFiredBundle().getJobDetail(),
        Trigger.CompletedExecutionInstruction.SET_TRIGGER_ERROR);
    TriggerState state = this.fJobStore.getTriggerState(trigger1.getKey());
    assertEquals(TriggerState.ERROR, state);

    // test reset
    this.fJobStore.resetTriggerFromErrorState(trigger1.getKey());
    state = this.fJobStore.getTriggerState(trigger1.getKey());
    assertEquals(TriggerState.NORMAL, state);
  }

  /**
   * Replacing an existing trigger with one of a different concrete type must rewrite extended
   * trigger rows (e.g. SIMPLE -> CRON). Otherwise the base QRTZ_TRIGGERS.TRIGGER_TYPE is updated
   * while the old extension row remains and the new type's extension table is empty, corrupting
   * subsequent reads.
   *
   * @see <a href="https://github.com/quartz-scheduler/quartz/issues/920">#920</a>
   */
  @Test
  void testStoreTriggerReplaceChangesTriggerType() throws Exception {
    Instant start = Instant.now();
    OperableTrigger simple =
        simpleTrigger(
            "typeChangeTrigger",
            "typeChangeGroup",
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            start,
            null,
            SimpleTrigger.REPEAT_INDEFINITELY,
            Duration.ofMillis(400_000L));
    this.fJobStore.storeTrigger(simple, false);

    OperableTrigger loadedSimple = this.fJobStore.retrieveTrigger(simple.getKey());
    assertNotNull(loadedSimple);
    assertTrue(loadedSimple instanceof SimpleTrigger);

    OperableTrigger cron =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity(simple.getKey())
                .forJob(this.fJobDetail)
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 12 * * ?"))
                .startAt(start)
                .build();

    assertDoesNotThrow(() -> this.fJobStore.storeTrigger(cron, true));

    OperableTrigger reloaded = this.fJobStore.retrieveTrigger(simple.getKey());
    assertNotNull(reloaded, "trigger must remain readable after type-changing replace");
    assertTrue(reloaded instanceof CronTrigger, "replaced trigger should be stored as CronTrigger");
    assertEquals("0 0 12 * * ?", ((CronTrigger) reloaded).getCronExpression());

    // Reverse direction: CRON -> SIMPLE
    OperableTrigger simpleAgain =
        simpleTrigger(
            simple.getKey().getName(),
            simple.getKey().getGroup(),
            this.fJobDetail.getName(),
            this.fJobDetail.getGroup(),
            start,
            null,
            3,
            Duration.ofMillis(60_000L));
    assertDoesNotThrow(() -> this.fJobStore.storeTrigger(simpleAgain, true));

    OperableTrigger reloadedSimple = this.fJobStore.retrieveTrigger(simple.getKey());
    assertNotNull(reloadedSimple);
    assertTrue(reloadedSimple instanceof SimpleTrigger);
    assertEquals(3, ((SimpleTrigger) reloadedSimple).getRepeatCount());
    assertEquals(Duration.ofMillis(60_000L), ((SimpleTrigger) reloadedSimple).getRepeatInterval());
  }

  @Test
  void testStoreJobReplacesJob() throws Exception {

    JobDetailImpl testJob =
        (JobDetailImpl)
            JobBuilder.newJob(MyJob.class).withIdentity("testDupeJob", "testDupeGroup").build();
    testJob.setDescription("123abc");
    testJob.setDurability(true);

    assertDoesNotThrow(
        () -> {
          fJobStore.storeJob(testJob, false);
        });

    JobDetail origJob = fJobStore.retrieveJob(testJob.getKey());
    assertNotNull(origJob);
    assertEquals(testJob.getKey(), origJob.getKey());
    assertEquals(testJob.getDescription(), origJob.getDescription());

    JobDetailImpl dupeJob =
        (JobDetailImpl)
            JobBuilder.newJob(MyJob.class).withIdentity("testDupeJob", "testDupeGroup").build();
    dupeJob.setDescription("123abc");
    dupeJob.setDurability(true);

    assertThrows(
        SchedulerException.class,
        () -> {
          fJobStore.storeJob(dupeJob, false);
        },
        "storing a duplicate job should not succeed with replaceExisting=false");

    assertDoesNotThrow(
        () -> {
          fJobStore.storeJob(dupeJob, true);
        },
        "storing a duplicate job should succeed with replaceExisting=true");

    JobDetail reloadedJob = fJobStore.retrieveJob(testJob.getKey());
    assertNotNull(reloadedJob);
    assertEquals(testJob.getKey(), reloadedJob.getKey());
    assertEquals(testJob.getDescription(), reloadedJob.getDescription());
  }

  // ---------------------------------------------------------------------
  // Contract cases added in Phase 7 (jobs, triggers, calendars, groups,
  // firing, completion, blocking, job data, misfires, recovery)
  // ---------------------------------------------------------------------

  private OperableTrigger simpleTrigger(
      String name, String group, JobKey jobKey, Instant start, int repeatCount, Duration interval) {
    SimpleTriggerImpl trigger =
        simpleTrigger(
            name, group, jobKey.getName(), jobKey.getGroup(), start, null, repeatCount, interval);
    trigger.computeFirstFireTime(null);
    return trigger;
  }

  private OperableTrigger dueTrigger(String name, JobKey jobKey) {
    return simpleTrigger(
        name, "contract", jobKey, Instant.now().minusMillis(100), 0, Duration.ZERO);
  }

  private List<TriggerFiredResult> acquireAndFire(OperableTrigger trigger) throws Exception {
    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO);
    assertThat(acquired).extracting(Trigger::getKey).containsExactly(trigger.getKey());
    return fJobStore.triggersFired(acquired);
  }

  @Test
  void testCountsGroupsAndExistence() throws Exception {
    JobStore store = emptyStore();
    JobDetail jobA =
        JobBuilder.newJob(MyJob.class).withIdentity("a1", "groupA").storeDurably().build();
    JobDetail jobB =
        JobBuilder.newJob(MyJob.class).withIdentity("b1", "groupB").storeDurably().build();
    store.storeJob(jobA, false);
    store.storeJob(jobB, false);
    store.storeTrigger(
        simpleTrigger("t1", "tg1", jobA.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO),
        false);
    store.storeTrigger(
        simpleTrigger("t2", "tg2", jobA.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO),
        false);

    assertThat(store.getNumberOfJobs()).isEqualTo(2);
    assertThat(store.getNumberOfTriggers()).isEqualTo(2);
    assertThat(store.getJobGroupNames()).containsExactlyInAnyOrder("groupA", "groupB");
    assertThat(store.getTriggerGroupNames()).containsExactlyInAnyOrder("tg1", "tg2");
    assertThat(store.checkExists(jobA.getKey())).isTrue();
    assertThat(store.checkExists(JobKey.jobKey("nope"))).isFalse();
    assertThat(store.checkExists(TriggerKey.triggerKey("t1", "tg1"))).isTrue();
    assertThat(store.checkExists(TriggerKey.triggerKey("t1", "tg2"))).isFalse();
    assertThat(store.getTriggersForJob(jobA.getKey()))
        .extracting(Trigger::getKey)
        .containsExactlyInAnyOrder(
            TriggerKey.triggerKey("t1", "tg1"), TriggerKey.triggerKey("t2", "tg2"));
    assertThat(store.getTriggersForJob(jobB.getKey())).isEmpty();
    assertThat(store.retrieveJob(JobKey.jobKey("nope"))).isNull();
    assertThat(store.retrieveTrigger(TriggerKey.triggerKey("nope"))).isNull();
  }

  @Test
  void testRemoveJobRemovesItsTriggers() throws Exception {
    fJobStore.storeTrigger(
        simpleTrigger(
            "r1", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO),
        false);
    fJobStore.storeTrigger(
        simpleTrigger(
            "r2", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO),
        false);

    assertThat(fJobStore.removeJob(fJobDetail.getKey())).isTrue();
    assertThat(fJobStore.checkExists(TriggerKey.triggerKey("r1", "g"))).isFalse();
    assertThat(fJobStore.checkExists(TriggerKey.triggerKey("r2", "g"))).isFalse();
    assertThat(fJobStore.getNumberOfTriggers()).isZero();
    assertThat(fJobStore.removeJob(fJobDetail.getKey())).isFalse();
  }

  @Test
  void testNonDurableJobIsRemovedWithItsLastTrigger() throws Exception {
    JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("transient").build();
    OperableTrigger t1 =
        simpleTrigger("n1", "g", job.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    OperableTrigger t2 =
        simpleTrigger("n2", "g", job.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    fJobStore.storeJobAndTrigger(job, t1);
    fJobStore.storeTrigger(t2, false);

    assertThat(fJobStore.removeTrigger(t1.getKey())).isTrue();
    assertThat(fJobStore.checkExists(job.getKey())).isTrue();
    assertThat(fJobStore.removeTrigger(t2.getKey())).isTrue();
    assertThat(fJobStore.checkExists(job.getKey())).isFalse();
    assertThat(fJobStore.removeTrigger(t2.getKey())).isFalse();
  }

  @Test
  void testStoreTriggerRequiresItsJobAndRejectsDuplicates() throws Exception {
    OperableTrigger orphan =
        simpleTrigger("orphan", "g", JobKey.jobKey("missing"), Instant.now(), 0, Duration.ZERO);
    assertThatThrownBy(() -> fJobStore.storeTrigger(orphan, false))
        .isInstanceOf(JobPersistenceException.class);

    OperableTrigger trigger =
        simpleTrigger(
            "dupe", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    fJobStore.storeTrigger(trigger, false);
    assertThatThrownBy(() -> fJobStore.storeTrigger(trigger, false))
        .isInstanceOf(ObjectAlreadyExistsException.class);
  }

  @Test
  void testStoreJobAndTriggerRejectsAnExistingJob() throws Exception {
    OperableTrigger trigger =
        simpleTrigger(
            "withJob", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    assertThatThrownBy(() -> fJobStore.storeJobAndTrigger(fJobDetail, trigger))
        .isInstanceOf(ObjectAlreadyExistsException.class);
  }

  @Test
  void testReplaceTrigger() throws Exception {
    OperableTrigger original =
        simpleTrigger(
            "orig", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    fJobStore.storeTrigger(original, false);
    OperableTrigger replacement =
        simpleTrigger(
            "repl",
            "g",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(120),
            5,
            Duration.ofSeconds(10));

    assertThat(fJobStore.replaceTrigger(original.getKey(), replacement)).isTrue();
    assertThat(fJobStore.checkExists(original.getKey())).isFalse();
    SimpleTrigger stored = (SimpleTrigger) fJobStore.retrieveTrigger(replacement.getKey());
    assertThat(stored.getRepeatCount()).isEqualTo(5);
    assertThat(stored.getRepeatInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(fJobStore.replaceTrigger(TriggerKey.triggerKey("missing"), replacement)).isFalse();
  }

  @Test
  void testStoreJobsAndTriggersAndBulkRemoval() throws Exception {
    JobDetail job1 = JobBuilder.newJob(MyJob.class).withIdentity("bulk1").build();
    JobDetail job2 = JobBuilder.newJob(MyJob.class).withIdentity("bulk2").build();
    Map<JobDetail, Set<? extends Trigger>> batch = new java.util.LinkedHashMap<>();
    batch.put(
        job1,
        Set.of(
            simpleTrigger(
                "bt1", "bulk", job1.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO)));
    batch.put(
        job2,
        Set.of(
            simpleTrigger(
                "bt2", "bulk", job2.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO),
            simpleTrigger(
                "bt3", "bulk", job2.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO)));
    fJobStore.storeJobsAndTriggers(batch, false);

    assertThat(fJobStore.getTriggerKeys(GroupMatcher.triggerGroupEquals("bulk"))).hasSize(3);
    assertThatThrownBy(() -> fJobStore.storeJobsAndTriggers(batch, false))
        .isInstanceOf(ObjectAlreadyExistsException.class);
    fJobStore.storeJobsAndTriggers(batch, true);

    assertThat(
            fJobStore.removeTriggers(
                List.of(
                    TriggerKey.triggerKey("bt1", "bulk"), TriggerKey.triggerKey("bt2", "bulk"))))
        .isTrue();
    assertThat(fJobStore.getTriggerKeys(GroupMatcher.triggerGroupEquals("bulk")))
        .containsExactly(TriggerKey.triggerKey("bt3", "bulk"));
    assertThat(fJobStore.removeJobs(List.of(job2.getKey(), JobKey.jobKey("nope")))).isFalse();
    assertThat(fJobStore.checkExists(job2.getKey())).isFalse();
  }

  @Test
  void testTriggerTypesRoundTrip() throws Exception {
    Instant start = Instant.parse("2030-01-02T03:04:05Z");
    OperableTrigger cron =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("cron", "types")
                .forJob(fJobDetail)
                .startAt(start)
                .withPriority(7)
                .withDescription("cron trigger")
                .usingJobData("k", "v")
                .withSchedule(
                    CronScheduleBuilder.cronSchedule("0 15 10 ? * MON-FRI")
                        .inTimeZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    OperableTrigger calendarInterval =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("calint", "types")
                .forJob(fJobDetail)
                .startAt(start)
                .endAt(start.plus(Duration.ofDays(400)))
                .withSchedule(
                    CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                        .withIntervalInMonths(2)
                        .inTimeZone(java.time.ZoneId.of("Europe/London"))
                        .preserveHourOfDayAcrossDaylightSavings(true)
                        .skipDayIfHourDoesNotExist(true))
                .build();
    OperableTrigger daily =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("daily", "types")
                .forJob(fJobDetail)
                .startAt(start)
                .withSchedule(
                    DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                        .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 30))
                        .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(17, 0))
                        .onDaysOfTheWeek(MONDAY_THROUGH_FRIDAY)
                        .withIntervalInMinutes(45)
                        .withRepeatCount(20))
                .build();
    for (OperableTrigger t : List.of(cron, calendarInterval, daily)) {
      t.computeFirstFireTime(null);
      fJobStore.storeTrigger(t, false);
    }

    CronTrigger storedCron = (CronTrigger) fJobStore.retrieveTrigger(cron.getKey());
    assertThat(storedCron.getCronExpression()).isEqualTo("0 15 10 ? * MON-FRI");
    assertThat(storedCron.getTimeZone()).isEqualTo(java.time.ZoneId.of("Asia/Kolkata"));
    assertThat(storedCron.getMisfireInstruction())
        .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    assertThat(storedCron.getPriority()).isEqualTo(7);
    assertThat(storedCron.getDescription()).isEqualTo("cron trigger");
    assertThat(storedCron.getJobDataMap().getString("k")).isEqualTo("v");
    assertThat(storedCron.getStartTime()).isEqualTo(start);
    assertThat(storedCron.getNextFireTime()).isEqualTo(cron.getNextFireTime());
    assertThat(storedCron.getJobKey()).isEqualTo(fJobDetail.getKey());

    CalendarIntervalTrigger storedCalInt =
        (CalendarIntervalTrigger) fJobStore.retrieveTrigger(calendarInterval.getKey());
    assertThat(storedCalInt.getRepeatInterval()).isEqualTo(2);
    assertThat(storedCalInt.getRepeatIntervalUnit()).isEqualTo(DateBuilder.IntervalUnit.MONTH);
    assertThat(storedCalInt.getTimeZone()).isEqualTo(java.time.ZoneId.of("Europe/London"));
    assertThat(storedCalInt.isPreserveHourOfDayAcrossDaylightSavings()).isTrue();
    assertThat(storedCalInt.isSkipDayIfHourDoesNotExist()).isTrue();
    assertThat(storedCalInt.getEndTime()).isEqualTo(start.plus(Duration.ofDays(400)));
    assertThat(storedCalInt.getNextFireTime()).isEqualTo(calendarInterval.getNextFireTime());

    DailyTimeIntervalTrigger storedDaily =
        (DailyTimeIntervalTrigger) fJobStore.retrieveTrigger(daily.getKey());
    assertThat(storedDaily.getStartTimeOfDay()).isEqualTo(TimeOfDay.hourAndMinuteOfDay(9, 30));
    assertThat(storedDaily.getEndTimeOfDay()).isEqualTo(TimeOfDay.hourAndMinuteOfDay(17, 0));
    assertThat(storedDaily.getDaysOfWeek()).isEqualTo(MONDAY_THROUGH_FRIDAY);
    assertThat(storedDaily.getRepeatInterval()).isEqualTo(45);
    assertThat(storedDaily.getRepeatIntervalUnit()).isEqualTo(DateBuilder.IntervalUnit.MINUTE);
    assertThat(storedDaily.getRepeatCount()).isEqualTo(20);
    assertThat(storedDaily.getNextFireTime()).isEqualTo(daily.getNextFireTime());
  }

  @Test
  void testJobDetailAndJobDataRoundTrip() throws Exception {
    JobDetail job =
        JobBuilder.newJob(StatefulJob.class)
            .withIdentity("data", "dataGroup")
            .withDescription("with data")
            .requestRecovery()
            .storeDurably()
            .usingJobData("string", "s")
            .usingJobData("int", 42)
            .usingJobData("long", 7L)
            .usingJobData("double", 2.5d)
            .usingJobData("boolean", true)
            .build();
    job.getJobDataMap().put("list", new ArrayList<>(List.of("x", "y")));
    fJobStore.storeJob(job, false);

    JobDetail stored = fJobStore.retrieveJob(job.getKey());
    assertThat(stored.getJobClass()).isEqualTo(StatefulJob.class);
    assertThat(stored.getDescription()).isEqualTo("with data");
    assertThat(stored.requestsRecovery()).isTrue();
    assertThat(stored.isDurable()).isTrue();
    assertThat(stored.isConcurrentExecutionDisallowed()).isTrue();
    assertThat(stored.isPersistJobDataAfterExecution()).isTrue();
    assertThat(stored.getJobDataMap().getString("string")).isEqualTo("s");
    assertThat(stored.getJobDataMap().getInt("int")).isEqualTo(42);
    assertThat(stored.getJobDataMap().getLong("long")).isEqualTo(7L);
    assertThat(stored.getJobDataMap().getDouble("double")).isEqualTo(2.5d);
    assertThat(stored.getJobDataMap().getBoolean("boolean")).isTrue();
    assertThat(stored.getJobDataMap().get("list")).isEqualTo(List.of("x", "y"));
  }

  @Test
  void testCalendars() throws Exception {
    WeeklyCalendar weekdays = new WeeklyCalendar();
    weekdays.setTimeZone(java.time.ZoneOffset.UTC);
    weekdays.setDescription("no weekends");
    fJobStore.storeCalendar("weekdays", weekdays, false, false);

    org.quartz.Calendar stored = fJobStore.retrieveCalendar("weekdays");
    assertThat(stored).isInstanceOf(WeeklyCalendar.class);
    assertThat(stored.getDescription()).isEqualTo("no weekends");
    assertThat(fJobStore.getCalendarNames()).containsExactly("weekdays");
    assertThat(fJobStore.getNumberOfCalendars()).isEqualTo(1);
    assertThat(fJobStore.retrieveCalendar("nope")).isNull();
    assertThatThrownBy(
            () -> fJobStore.storeCalendar("weekdays", new WeeklyCalendar(), false, false))
        .isInstanceOf(ObjectAlreadyExistsException.class);

    OperableTrigger trigger =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("calTrigger")
                .forJob(fJobDetail)
                .modifiedByCalendar("weekdays")
                .withSchedule(SimpleScheduleBuilder.repeatHourlyForever())
                .startAt(Instant.parse("2030-01-05T12:00:00Z"))
                .build(); // 2030-01-05 is a Saturday
    trigger.computeFirstFireTime(weekdays);
    fJobStore.storeTrigger(trigger, false);
    assertThat(fJobStore.retrieveTrigger(trigger.getKey()).getNextFireTime())
        .isEqualTo(Instant.parse("2030-01-07T00:00:00Z"));

    // replacing the calendar with updateTriggers=true recomputes the trigger's next fire time
    WeeklyCalendar everyDay = new WeeklyCalendar();
    everyDay.setTimeZone(java.time.ZoneOffset.UTC);
    everyDay.setDayExcluded(java.util.Calendar.SATURDAY, false);
    everyDay.setDayExcluded(java.util.Calendar.SUNDAY, false);
    fJobStore.storeCalendar("weekdays", everyDay, true, true);
    assertThat(fJobStore.retrieveTrigger(trigger.getKey()).getNextFireTime())
        .isEqualTo(Instant.parse("2030-01-05T12:00:00Z"));

    assertThatThrownBy(() -> fJobStore.removeCalendar("weekdays"))
        .isInstanceOf(JobPersistenceException.class);
    fJobStore.removeTrigger(trigger.getKey());
    assertThat(fJobStore.removeCalendar("weekdays")).isTrue();
    assertThat(fJobStore.removeCalendar("weekdays")).isFalse();
  }

  @Test
  void testPauseAndResumeTriggerGroups() throws Exception {
    OperableTrigger a =
        simpleTrigger(
            "pa",
            "pauseGroup",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(60),
            0,
            Duration.ZERO);
    OperableTrigger b =
        simpleTrigger(
            "pb",
            "otherGroup",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(60),
            0,
            Duration.ZERO);
    fJobStore.storeTrigger(a, false);
    fJobStore.storeTrigger(b, false);

    assertThat(fJobStore.pauseTriggers(GroupMatcher.triggerGroupStartsWith("pause")))
        .containsExactly("pauseGroup");
    assertThat(fJobStore.getTriggerState(a.getKey())).isEqualTo(TriggerState.PAUSED);
    assertThat(fJobStore.getTriggerState(b.getKey())).isEqualTo(TriggerState.NORMAL);
    assertThat(fJobStore.getPausedTriggerGroups()).contains("pauseGroup");

    OperableTrigger late =
        simpleTrigger(
            "pc",
            "pauseGroup",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(60),
            0,
            Duration.ZERO);
    fJobStore.storeTrigger(late, false);
    assertThat(fJobStore.getTriggerState(late.getKey())).isEqualTo(TriggerState.PAUSED);

    assertThat(fJobStore.resumeTriggers(GroupMatcher.triggerGroupEquals("pauseGroup")))
        .containsExactly("pauseGroup");
    assertThat(fJobStore.getTriggerState(a.getKey())).isEqualTo(TriggerState.NORMAL);
    assertThat(fJobStore.getTriggerState(late.getKey())).isEqualTo(TriggerState.NORMAL);
    assertThat(fJobStore.getPausedTriggerGroups()).doesNotContain("pauseGroup");
  }

  @Test
  void testPauseAndResumeJobs() throws Exception {
    JobDetail other =
        JobBuilder.newJob(MyJob.class).withIdentity("j2", "otherJobs").storeDurably().build();
    fJobStore.storeJob(other, false);
    OperableTrigger mine =
        simpleTrigger(
            "mine", "g", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    OperableTrigger theirs =
        simpleTrigger(
            "theirs", "g", other.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    fJobStore.storeTrigger(mine, false);
    fJobStore.storeTrigger(theirs, false);

    fJobStore.pauseJob(fJobDetail.getKey());
    assertThat(fJobStore.getTriggerState(mine.getKey())).isEqualTo(TriggerState.PAUSED);
    assertThat(fJobStore.getTriggerState(theirs.getKey())).isEqualTo(TriggerState.NORMAL);
    fJobStore.resumeJob(fJobDetail.getKey());
    assertThat(fJobStore.getTriggerState(mine.getKey())).isEqualTo(TriggerState.NORMAL);

    assertThat(fJobStore.pauseJobs(GroupMatcher.jobGroupEquals("otherJobs")))
        .containsExactly("otherJobs");
    assertThat(fJobStore.getTriggerState(theirs.getKey())).isEqualTo(TriggerState.PAUSED);
    assertThat(fJobStore.resumeJobs(GroupMatcher.jobGroupEquals("otherJobs")))
        .containsExactly("otherJobs");
    assertThat(fJobStore.getTriggerState(theirs.getKey())).isEqualTo(TriggerState.NORMAL);
  }

  @Test
  void testPauseAllAndResumeAll() throws Exception {
    OperableTrigger a =
        simpleTrigger(
            "all1", "g1", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    OperableTrigger b =
        simpleTrigger(
            "all2", "g2", fJobDetail.getKey(), Instant.now().plusSeconds(60), 0, Duration.ZERO);
    fJobStore.storeTrigger(a, false);
    fJobStore.storeTrigger(b, false);

    fJobStore.pauseAll();
    assertThat(fJobStore.getTriggerState(a.getKey())).isEqualTo(TriggerState.PAUSED);
    assertThat(fJobStore.getTriggerState(b.getKey())).isEqualTo(TriggerState.PAUSED);
    assertThat(fJobStore.getPausedTriggerGroups()).contains("g1", "g2");
    assertThat(fJobStore.acquireNextTriggers(Instant.now().plusSeconds(120), 5, Duration.ZERO))
        .isEmpty();

    fJobStore.resumeAll();
    assertThat(fJobStore.getTriggerState(a.getKey())).isEqualTo(TriggerState.NORMAL);
    assertThat(fJobStore.getTriggerState(b.getKey())).isEqualTo(TriggerState.NORMAL);
    assertThat(fJobStore.getPausedTriggerGroups()).doesNotContain("g1", "g2");
  }

  @Test
  void testAcquireOrdersByFireTimeThenPriority() throws Exception {
    Instant fireTime = Instant.now().plusSeconds(30);
    OperableTrigger low =
        simpleTrigger("low", "prio", fJobDetail.getKey(), fireTime, 0, Duration.ZERO);
    low.setPriority(1);
    OperableTrigger high =
        simpleTrigger("high", "prio", fJobDetail.getKey(), fireTime, 0, Duration.ZERO);
    high.setPriority(10);
    OperableTrigger earlier =
        simpleTrigger(
            "earlier", "prio", fJobDetail.getKey(), fireTime.minusSeconds(1), 0, Duration.ZERO);
    earlier.setPriority(0);
    fJobStore.storeTrigger(low, false);
    fJobStore.storeTrigger(high, false);
    fJobStore.storeTrigger(earlier, false);

    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(fireTime, 3, Duration.ofSeconds(5));
    assertThat(acquired)
        .extracting(t -> t.getKey().getName())
        .containsExactly("earlier", "high", "low");
    assertThat(acquired).allSatisfy(t -> assertThat(t.getFireInstanceId()).isNotBlank());
    assertThat(acquired).extracting(OperableTrigger::getFireInstanceId).doesNotHaveDuplicates();
  }

  @Test
  void testFireAndCompleteWithDeleteTrigger() throws Exception {
    JobDetail job = JobBuilder.newJob(MyJob.class).withIdentity("oneShot").build();
    OperableTrigger trigger = dueTrigger("oneShotTrigger", job.getKey());
    fJobStore.storeJobAndTrigger(job, trigger);

    List<TriggerFiredResult> fired = acquireAndFire(trigger);
    TriggerFiredBundle bundle = fired.get(0).getTriggerFiredBundle();
    assertThat(bundle).isNotNull();
    assertThat(bundle.getJobDetail().getKey()).isEqualTo(job.getKey());
    assertThat(bundle.isRecovering()).isFalse();
    assertThat(bundle.getTrigger().getNextFireTime()).isNull();

    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.DELETE_TRIGGER);
    assertThat(fJobStore.checkExists(trigger.getKey())).isFalse();
    assertThat(fJobStore.checkExists(job.getKey())).isFalse();
  }

  @Test
  void testFireAdvancesARepeatingTrigger() throws Exception {
    Instant start = Instant.now().minusMillis(100);
    OperableTrigger trigger =
        simpleTrigger(
            "repeating", "contract", fJobDetail.getKey(), start, 3, Duration.ofMinutes(1));
    fJobStore.storeTrigger(trigger, false);

    TriggerFiredBundle bundle = acquireAndFire(trigger).get(0).getTriggerFiredBundle();
    assertThat(bundle.getScheduledFireTime()).isEqualTo(trigger.getNextFireTime());
    fJobStore.triggeredJobComplete(
        bundle.getTrigger(), bundle.getJobDetail(), Trigger.CompletedExecutionInstruction.NOOP);

    OperableTrigger stored = fJobStore.retrieveTrigger(trigger.getKey());
    assertThat(stored.getNextFireTime())
        .isEqualTo(trigger.getNextFireTime().plus(Duration.ofMinutes(1)));
    assertThat(stored.getPreviousFireTime()).isEqualTo(trigger.getNextFireTime());
    assertThat(((SimpleTrigger) stored).getTimesTriggered()).isEqualTo(1);
    assertThat(fJobStore.getTriggerState(trigger.getKey())).isEqualTo(TriggerState.NORMAL);
  }

  @Test
  void testCompletionInstructionsSetTriggerStates() throws Exception {
    OperableTrigger first =
        simpleTrigger(
            "first",
            "instr",
            fJobDetail.getKey(),
            Instant.now().minusMillis(100),
            5,
            Duration.ofMinutes(1));
    OperableTrigger sibling =
        simpleTrigger(
            "sibling",
            "instr",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(600),
            5,
            Duration.ofMinutes(1));
    fJobStore.storeTrigger(first, false);
    fJobStore.storeTrigger(sibling, false);

    TriggerFiredBundle bundle = acquireAndFire(first).get(0).getTriggerFiredBundle();
    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.SET_TRIGGER_COMPLETE);
    assertThat(fJobStore.getTriggerState(first.getKey())).isEqualTo(TriggerState.COMPLETE);
    assertThat(fJobStore.getTriggerState(sibling.getKey())).isEqualTo(TriggerState.NORMAL);

    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR);
    assertThat(fJobStore.getTriggerState(first.getKey())).isEqualTo(TriggerState.ERROR);
    assertThat(fJobStore.getTriggerState(sibling.getKey())).isEqualTo(TriggerState.ERROR);

    fJobStore.resetTriggerFromErrorState(sibling.getKey());
    assertThat(fJobStore.getTriggerState(sibling.getKey())).isEqualTo(TriggerState.NORMAL);
  }

  @Test
  void testReleaseAcquiredTriggerMakesItAvailableAgain() throws Exception {
    OperableTrigger trigger = dueTrigger("release", fJobDetail.getKey());
    fJobStore.storeTrigger(trigger, false);

    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO);
    assertThat(acquired).hasSize(1);
    assertThat(fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .isEmpty();
    fJobStore.releaseAcquiredTrigger(acquired.get(0));
    assertThat(fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .extracting(Trigger::getKey)
        .containsExactly(trigger.getKey());
  }

  @Test
  void testTriggersFiredSkipsTriggersPausedOrRemovedAfterAcquisition() throws Exception {
    OperableTrigger paused = dueTrigger("pausedAfter", fJobDetail.getKey());
    OperableTrigger removed = dueTrigger("removedAfter", fJobDetail.getKey());
    fJobStore.storeTrigger(paused, false);
    fJobStore.storeTrigger(removed, false);
    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 2, Duration.ofSeconds(1));
    assertThat(acquired).hasSize(2);

    fJobStore.pauseTrigger(paused.getKey());
    fJobStore.removeTrigger(removed.getKey());

    assertThat(fJobStore.triggersFired(acquired))
        .allSatisfy(result -> assertThat(result.getTriggerFiredBundle()).isNull());
  }

  @Test
  void testDisallowConcurrentExecutionBlocksOtherTriggersOfTheJob() throws Exception {
    JobDetail job =
        JobBuilder.newJob(StatefulJob.class).withIdentity("nonConcurrent").storeDurably().build();
    fJobStore.storeJob(job, false);
    OperableTrigger t1 = dueTrigger("nc1", job.getKey());
    OperableTrigger t2 = dueTrigger("nc2", job.getKey());
    fJobStore.storeTrigger(t1, false);
    fJobStore.storeTrigger(t2, false);

    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 2, Duration.ofSeconds(1));
    assertThat(acquired).hasSize(1);
    TriggerKey firedKey = acquired.get(0).getKey();
    TriggerKey otherKey = firedKey.equals(t1.getKey()) ? t2.getKey() : t1.getKey();

    TriggerFiredBundle bundle = fJobStore.triggersFired(acquired).get(0).getTriggerFiredBundle();
    assertThat(fJobStore.getTriggerState(otherKey)).isEqualTo(TriggerState.BLOCKED);
    assertThat(
            fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 2, Duration.ofSeconds(1)))
        .isEmpty();

    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.DELETE_TRIGGER);
    assertThat(fJobStore.getTriggerState(otherKey)).isEqualTo(TriggerState.NORMAL);
    assertThat(
            fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 2, Duration.ofSeconds(1)))
        .extracting(Trigger::getKey)
        .containsExactly(otherKey);
  }

  @Test
  void testPausingABlockedTriggerKeepsItBlockedUntilCompletion() throws Exception {
    JobDetail job =
        JobBuilder.newJob(StatefulJob.class).withIdentity("pausedBlocked").storeDurably().build();
    fJobStore.storeJob(job, false);
    OperableTrigger running = dueTrigger("running", job.getKey());
    OperableTrigger waiting =
        simpleTrigger(
            "waiting", "contract", job.getKey(), Instant.now().plusSeconds(600), 0, Duration.ZERO);
    fJobStore.storeTrigger(running, false);
    fJobStore.storeTrigger(waiting, false);

    TriggerFiredBundle bundle = acquireAndFire(running).get(0).getTriggerFiredBundle();
    fJobStore.pauseTrigger(waiting.getKey());
    assertThat(fJobStore.getTriggerState(waiting.getKey())).isEqualTo(TriggerState.PAUSED);
    fJobStore.triggeredJobComplete(
        bundle.getTrigger(), bundle.getJobDetail(), Trigger.CompletedExecutionInstruction.NOOP);
    assertThat(fJobStore.getTriggerState(waiting.getKey())).isEqualTo(TriggerState.PAUSED);
    fJobStore.resumeTrigger(waiting.getKey());
    assertThat(fJobStore.getTriggerState(waiting.getKey())).isEqualTo(TriggerState.NORMAL);
  }

  @Test
  void testPersistJobDataAfterExecution() throws Exception {
    JobDetail job =
        JobBuilder.newJob(StatefulJob.class)
            .withIdentity("stateful")
            .storeDurably()
            .usingJobData("count", 0)
            .build();
    fJobStore.storeJob(job, false);
    OperableTrigger trigger = dueTrigger("statefulTrigger", job.getKey());
    fJobStore.storeTrigger(trigger, false);

    TriggerFiredBundle bundle = acquireAndFire(trigger).get(0).getTriggerFiredBundle();
    bundle.getJobDetail().getJobDataMap().put("count", 1);
    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.DELETE_TRIGGER);

    assertThat(fJobStore.retrieveJob(job.getKey()).getJobDataMap().getInt("count")).isEqualTo(1);
  }

  @Test
  void testJobDataIsNotPersistedWithoutTheAnnotation() throws Exception {
    JobDetail job =
        JobBuilder.newJob(MyJob.class)
            .withIdentity("stateless")
            .storeDurably()
            .usingJobData("count", 0)
            .build();
    fJobStore.storeJob(job, false);
    OperableTrigger trigger = dueTrigger("statelessTrigger", job.getKey());
    fJobStore.storeTrigger(trigger, false);

    TriggerFiredBundle bundle = acquireAndFire(trigger).get(0).getTriggerFiredBundle();
    bundle.getJobDetail().getJobDataMap().put("count", 1);
    fJobStore.triggeredJobComplete(
        bundle.getTrigger(),
        bundle.getJobDetail(),
        Trigger.CompletedExecutionInstruction.DELETE_TRIGGER);

    assertThat(fJobStore.retrieveJob(job.getKey()).getJobDataMap().getInt("count")).isZero();
  }

  @Test
  void testMisfireRescheduleNextWithRemainingCount() throws Exception {
    Instant now = Instant.now();
    OperableTrigger trigger =
        simpleTrigger(
            "misfireRemaining",
            "misfires",
            fJobDetail.getKey(),
            now.minus(Duration.ofMinutes(10)).minusMillis(500),
            20,
            Duration.ofMinutes(1));
    trigger.setMisfireInstruction(
        SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
    fJobStore.storeTrigger(trigger, false);
    fSignaler.setCurrentTime(now);
    int misfiresBefore = fSignaler.fMisfireCount;

    applyMisfires(fJobStore, now.toEpochMilli() + 1_000L);

    SimpleTrigger stored = (SimpleTrigger) fJobStore.retrieveTrigger(trigger.getKey());
    assertThat(stored.getNextFireTime()).isAfter(now);
    assertThat(stored.getNextFireTime()).isBefore(now.plus(Duration.ofMinutes(1)));
    assertThat(stored.getRepeatCount()).isEqualTo(20);
    assertThat(stored.getTimesTriggered()).isEqualTo(11); // missed: -10 min ... -0.5 s
    assertThat(fSignaler.fMisfireCount).isGreaterThan(misfiresBefore);
  }

  @Test
  void testMisfireCronDoNothingSkipsToTheNextFireTime() throws Exception {
    Instant now = Instant.now();
    OperableTrigger trigger =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("misfireCron", "misfires")
                .forJob(fJobDetail)
                .startAt(now.minus(Duration.ofHours(2)))
                .withSchedule(
                    CronScheduleBuilder.cronSchedule("0 0 * * * ?")
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    trigger.computeFirstFireTime(null);
    fJobStore.storeTrigger(trigger, false);
    fSignaler.setCurrentTime(now);

    applyMisfires(fJobStore, now.toEpochMilli() + 1_000L);

    Instant next = fJobStore.retrieveTrigger(trigger.getKey()).getNextFireTime();
    assertThat(next).isAfter(now);
    assertThat(next).isBeforeOrEqualTo(now.plus(Duration.ofHours(1)));
  }

  @Test
  void testMisfireOfAOneShotTriggerWithNothingLeftCompletesIt() throws Exception {
    Instant now = Instant.now();
    OperableTrigger trigger =
        simpleTrigger(
            "misfireDone",
            "misfires",
            fJobDetail.getKey(),
            now.minus(Duration.ofMinutes(10)),
            0,
            Duration.ZERO);
    trigger.setMisfireInstruction(
        SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
    fJobStore.storeTrigger(trigger, false);
    fSignaler.setCurrentTime(now);

    applyMisfires(fJobStore, now.toEpochMilli() + 1_000L);

    assertThat(fJobStore.acquireNextTriggers(now.plusSeconds(60), 5, Duration.ZERO))
        .extracting(Trigger::getKey)
        .doesNotContain(trigger.getKey());
  }

  @Test
  void testTriggersWithinTheMisfireThresholdAreAcquiredNotMisfired() throws Exception {
    OperableTrigger trigger =
        simpleTrigger(
            "slightlyLate",
            "misfires",
            fJobDetail.getKey(),
            Instant.now().minusMillis(200),
            0,
            Duration.ZERO);
    fJobStore.storeTrigger(trigger, false);
    int misfiresBefore = fSignaler.fMisfireCount;

    assertThat(fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .extracting(Trigger::getKey)
        .containsExactly(trigger.getKey());
    assertThat(fSignaler.fMisfireCount).isEqualTo(misfiresBefore);
  }

  @Test
  void testClearAllSchedulingData() throws Exception {
    fJobStore.storeTrigger(
        simpleTrigger(
            "c1",
            "clearGroup",
            fJobDetail.getKey(),
            Instant.now().plusSeconds(60),
            0,
            Duration.ZERO),
        false);
    fJobStore.storeCalendar("cal", new WeeklyCalendar(), false, false);
    fJobStore.pauseTriggers(GroupMatcher.triggerGroupEquals("clearGroup"));

    fJobStore.clearAllSchedulingData();

    assertThat(fJobStore.getNumberOfJobs()).isZero();
    assertThat(fJobStore.getNumberOfTriggers()).isZero();
    assertThat(fJobStore.getNumberOfCalendars()).isZero();
    assertThat(fJobStore.getPausedTriggerGroups()).isEmpty();
    assertThat(fJobStore.getJobGroupNames()).isEmpty();
  }

  @Test
  void testRecoveryOfJobsInProgressAfterRestart() throws Exception {
    assertThat(fJobStore.supportsPersistence()).isTrue();
    JobDetail job =
        JobBuilder.newJob(MyJob.class)
            .withIdentity("recoverable")
            .requestRecovery()
            .storeDurably()
            .build();
    fJobStore.storeJob(job, false);
    OperableTrigger trigger = dueTrigger("recoverableTrigger", job.getKey());
    fJobStore.storeTrigger(trigger, false);
    OperableTrigger pending =
        simpleTrigger(
            "pendingAcquired",
            "contract",
            fJobDetail.getKey(),
            Instant.now().plusMillis(500),
            0,
            Duration.ZERO);
    fJobStore.storeTrigger(pending, false);
    acquireAndFire(trigger);
    assertThat(fJobStore.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .extracting(Trigger::getKey)
        .containsExactly(pending.getKey());

    // "crash": the scheduler never reports completion; a new store instance starts on the same data
    fJobStore.shutdown();
    fJobStore = startStore(INSTANCE_ID, fSignaler);

    List<OperableTrigger> acquired =
        fJobStore.acquireNextTriggers(Instant.now().plusSeconds(2), 5, Duration.ofSeconds(2));
    assertThat(acquired).extracting(Trigger::getKey).contains(pending.getKey());
    OperableTrigger recovery =
        acquired.stream()
            .filter(t -> t.getKey().getGroup().equals(Scheduler.DEFAULT_RECOVERY_GROUP))
            .findFirst()
            .orElseThrow();
    assertThat(recovery.getJobKey()).isEqualTo(job.getKey());
    TriggerFiredBundle bundle =
        fJobStore.triggersFired(List.of(recovery)).get(0).getTriggerFiredBundle();
    assertThat(bundle.isRecovering()).isTrue();
  }

  @Test
  void testShutdownIsIdempotent() {
    fJobStore.shutdown();
    assertDoesNotThrow(() -> fJobStore.shutdown());
  }

  /** A job that disallows concurrent execution and persists its data. */
  @DisallowConcurrentExecution
  @PersistJobDataAfterExecution
  public static class StatefulJob implements Job {
    public void execute(JobExecutionContext context) {
      //
    }
  }

  public static class SampleSignaler implements SchedulerSignaler {
    volatile int fMisfireCount = 0;
    private volatile Instant currentTime;

    public void setCurrentTime(Instant currentTime) {
      this.currentTime = currentTime;
    }

    @Override
    public Instant getCurrentTime() {
      Instant configuredTime = currentTime;
      return configuredTime == null
          ? Instant.now()
          : Instant.ofEpochMilli(configuredTime.toEpochMilli());
    }

    @Override
    public void notifyTriggerListenersMisfired(Trigger trigger) {
      System.out.println(
          "Trigger misfired: " + trigger.getKey() + ", fire time: " + trigger.getNextFireTime());
      fMisfireCount++;
    }

    public void signalSchedulingChange(Instant candidateNewNextFireTime) {}

    public void notifySchedulerListenersFinalized(Trigger trigger) {}

    public void notifySchedulerListenersJobDeleted(JobKey jobKey) {}

    public void notifySchedulerListenersError(String string, SchedulerException jpe) {}
  }

  /** An empty job for testing purpose. */
  public static class MyJob implements Job {
    public void execute(JobExecutionContext context) throws JobExecutionException {
      //
    }
  }

  /** A simple trigger for the given job, set up field by field. */
  protected static SimpleTriggerImpl simpleTrigger(
      String name,
      String group,
      String jobName,
      String jobGroup,
      Instant startTime,
      Instant endTime,
      int repeatCount,
      Duration repeatInterval) {
    SimpleTriggerImpl trigger = new SimpleTriggerImpl();
    trigger.setName(name);
    trigger.setGroup(group);
    trigger.setJobName(jobName);
    trigger.setJobGroup(jobGroup);
    trigger.setStartTime(startTime);
    trigger.setEndTime(endTime);
    trigger.setRepeatCount(repeatCount);
    trigger.setRepeatInterval(repeatInterval);
    return trigger;
  }
}
