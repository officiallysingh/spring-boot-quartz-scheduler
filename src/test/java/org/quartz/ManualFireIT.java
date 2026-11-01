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
package org.quartz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Trigger.TriggerState;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** {@link Scheduler#triggerNow(TriggerKey)} and {@link Scheduler#triggerJob(JobKey)}. */
class ManualFireIT extends AbstractMongoIT {

  /** What each execution saw: its trigger and merged job data. */
  record Execution(TriggerKey trigger, JobDataMap data, int priority) {}

  static final List<Execution> EXECUTIONS = new CopyOnWriteArrayList<>();

  static final List<TriggerKey> FIRED_TRIGGERS = new CopyOnWriteArrayList<>();

  public static class RecordingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      EXECUTIONS.add(
          new Execution(
              context.getTrigger().getKey(),
              new JobDataMap(context.getMergedJobDataMap()),
              context.getTrigger().getPriority()));
    }
  }

  private Scheduler scheduler;

  @BeforeEach
  void setUp() throws Exception {
    EXECUTIONS.clear();
    FIRED_TRIGGERS.clear();
    scheduler = TestSchedulers.create(TestSchedulers.scheduler().name("manualFire"), 2);
    scheduler
        .getListenerManager()
        .addTriggerListener(
            new TriggerListenerSupport() {
              @Override
              public String getName() {
                return "recording";
              }

              @Override
              public void triggerFired(Trigger trigger, JobExecutionContext context) {
                FIRED_TRIGGERS.add(trigger.getKey());
              }
            });
    scheduler.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    scheduler.shutdown(true);
  }

  @Test
  void triggerNowRunsTheJobOnceWithTheTriggersDataAndLeavesTheTriggerAlone() throws Exception {
    Instant nextWeek = Instant.now().plus(Duration.ofDays(7));
    JobDetail job =
        JobBuilder.newJob(RecordingJob.class)
            .withIdentity("auction", "auctions")
            .usingJobData("fromJob", "j")
            .build();
    Trigger trigger =
        TriggerBuilder.newTrigger()
            .withIdentity("auction-start", "auctions")
            .startAt(nextWeek)
            .usingJobData("k", "v")
            .withPriority(8)
            .build();
    scheduler.scheduleJob(job, trigger);
    Trigger before = scheduler.getTrigger(trigger.getKey());

    scheduler.triggerNow(trigger.getKey());

    await().atMost(Duration.ofSeconds(20)).until(() -> EXECUTIONS.size() == 1);
    Execution execution = EXECUTIONS.getFirst();
    assertThat(execution.data())
        .containsEntry("k", "v")
        .containsEntry("fromJob", "j")
        .containsEntry(Scheduler.MANUAL_FIRE_OF_TRIGGER, "auctions.auction-start");
    assertThat(execution.trigger().getGroup()).isEqualTo(Scheduler.MANUAL_TRIGGER_GROUP);
    assertThat(execution.priority()).isEqualTo(8);
    assertThat(FIRED_TRIGGERS).containsExactly(execution.trigger());

    // exactly once, and the original trigger is unchanged
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(3))
        .until(() -> EXECUTIONS.size() == 1);
    Trigger after = scheduler.getTrigger(trigger.getKey());
    assertThat(after.getNextFireTime()).isEqualTo(before.getNextFireTime());
    assertThat(after.getPreviousFireTime()).isNull();
    assertThat(after.getJobDataMap().getWrappedMap()).isEqualTo(Map.of("k", "v"));
    assertThat(scheduler.getTriggerState(trigger.getKey())).isEqualTo(TriggerState.NORMAL);
    // the one-off trigger is gone once it has fired
    assertThat(scheduler.checkExists(execution.trigger())).isFalse();
  }

  @Test
  void triggerNowOfAMissingTriggerFails() {
    assertThatExceptionOfType(JobPersistenceException.class)
        .isThrownBy(() -> scheduler.triggerNow(TriggerKey.triggerKey("missing", "auctions")))
        .withMessageContaining("auctions.missing");
  }

  @Test
  void triggerJobUsesTheManualTriggerGroup() throws Exception {
    scheduler.addJob(
        JobBuilder.newJob(RecordingJob.class).withIdentity("report").storeDurably().build(), false);

    scheduler.triggerJob(JobKey.jobKey("report"), new JobDataMap(Map.of("extra", "1")));

    await().atMost(Duration.ofSeconds(20)).until(() -> EXECUTIONS.size() == 1);
    Execution execution = EXECUTIONS.getFirst();
    assertThat(execution.trigger().getGroup()).isEqualTo(Scheduler.MANUAL_TRIGGER_GROUP);
    assertThat(execution.data())
        .containsEntry("extra", "1")
        .doesNotContainKey(Scheduler.MANUAL_FIRE_OF_TRIGGER);
  }

  @Test
  void clusterQueriesAndInterruptsFailOnceTheSchedulerIsShutDown() throws Exception {
    scheduler.shutdown(true);

    assertThatExceptionOfType(SchedulerException.class)
        .isThrownBy(() -> scheduler.getSchedulerInstances())
        .withMessage("The Scheduler has been shutdown.");
    assertThatExceptionOfType(SchedulerException.class)
        .isThrownBy(() -> scheduler.getClusterExecutingJobs())
        .withMessage("The Scheduler has been shutdown.");
    assertThatExceptionOfType(SchedulerException.class)
        .isThrownBy(() -> scheduler.interruptAnywhere("any"))
        .withMessage("The Scheduler has been shutdown.");
  }
}
