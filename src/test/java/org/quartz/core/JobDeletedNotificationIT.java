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
package org.quartz.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/**
 * Scheduler listeners are told of a deleted job exactly once, whichever way it was deleted, and
 * after the trigger that was unscheduled with it.
 */
class JobDeletedNotificationIT extends AbstractMongoIT {

  private static final List<String> RUNS = new CopyOnWriteArrayList<>();

  public static class RecordingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      RUNS.add(context.getJobDetail().getKey().getName());
    }
  }

  private final List<String> events = new CopyOnWriteArrayList<>();

  private Scheduler scheduler;

  @BeforeEach
  void createScheduler() throws SchedulerException {
    RUNS.clear();
    // an idle wait far longer than any assertion: nothing here may depend on it
    scheduler =
        TestSchedulers.create(TestSchedulers.scheduler().idleWaitTime(Duration.ofMinutes(5)), 2);
    scheduler
        .getListenerManager()
        .addSchedulerListener(
            new SchedulerListenerSupport() {
              @Override
              public void jobUnscheduled(TriggerKey triggerKey) {
                events.add("unscheduled " + triggerKey.getName());
              }

              @Override
              public void jobDeleted(JobKey jobKey) {
                events.add("deleted " + jobKey.getName());
              }
            });
  }

  @AfterEach
  void shutdown() throws SchedulerException {
    scheduler.shutdown(true);
  }

  private static JobDetail job(String name, boolean durable) {
    return newJob(RecordingJob.class).withIdentity(name).storeDurably(durable).build();
  }

  private static Trigger inAnHour(String name, JobDetail job) {
    return newTrigger()
        .withIdentity(name)
        .forJob(job)
        .startAt(Instant.now().plus(Duration.ofHours(1)))
        .build();
  }

  @Test
  @SuppressWarnings("NullAway") // null is the point
  void startDelayedRejectsANullDelayAtOnce() {
    org.assertj.core.api.Assertions.assertThatNullPointerException()
        .isThrownBy(() -> scheduler.startDelayed(null))
        .withMessageContaining("delay");
  }

  @Test
  void deleteJobOfAJobThatIsNotDurableReportsItOnce() throws Exception {
    JobDetail job = job("transient", false);
    scheduler.scheduleJob(job, inAnHour("t1", job));
    scheduler.scheduleJob(inAnHour("t2", job));

    assertThat(scheduler.deleteJob(job.getKey())).isTrue();

    assertThat(events)
        .filteredOn(event -> event.startsWith("deleted"))
        .containsExactly("deleted transient");
    assertThat(events)
        .containsExactlyInAnyOrder("unscheduled t1", "unscheduled t2", "deleted transient");
    assertThat(events.getLast()).isEqualTo("deleted transient");
    assertThat(scheduler.checkExists(job.getKey())).isFalse();
  }

  @Test
  void deleteJobOfADurableJobReportsItOnce() throws Exception {
    JobDetail withTrigger = job("withTrigger", true);
    scheduler.scheduleJob(withTrigger, inAnHour("t1", withTrigger));
    scheduler.addJob(job("alone", true), false);

    assertThat(scheduler.deleteJob(withTrigger.getKey())).isTrue();
    assertThat(scheduler.deleteJob(JobKey.jobKey("alone"))).isTrue();
    assertThat(scheduler.deleteJob(JobKey.jobKey("missing"))).isFalse();

    assertThat(events).containsExactly("unscheduled t1", "deleted withTrigger", "deleted alone");
  }

  @Test
  void deleteJobsReportsEachDeletedJobOnce() throws Exception {
    JobDetail transientJob = job("transient", false);
    scheduler.scheduleJob(transientJob, inAnHour("t1", transientJob));
    scheduler.addJob(job("durable", true), false);

    scheduler.deleteJobs(
        List.of(transientJob.getKey(), JobKey.jobKey("durable"), JobKey.jobKey("missing")));

    assertThat(events).containsExactly("deleted transient", "deleted durable");
  }

  @Test
  void unscheduleJobReportsTheTriggerAndThenTheJobThatWentWithIt() throws Exception {
    JobDetail transientJob = job("transient", false);
    JobDetail durable = job("durable", true);
    scheduler.scheduleJob(transientJob, inAnHour("t1", transientJob));
    scheduler.scheduleJob(inAnHour("t2", transientJob));
    scheduler.scheduleJob(durable, inAnHour("d1", durable));

    assertThat(scheduler.unscheduleJob(TriggerKey.triggerKey("t1"))).isTrue();
    assertThat(scheduler.unscheduleJob(TriggerKey.triggerKey("d1"))).isTrue();
    assertThat(events).containsExactly("unscheduled t1", "unscheduled d1");

    assertThat(scheduler.unscheduleJob(TriggerKey.triggerKey("t2"))).isTrue();
    assertThat(events)
        .containsExactly("unscheduled t1", "unscheduled d1", "unscheduled t2", "deleted transient");
    assertThat(scheduler.checkExists(durable.getKey())).isTrue();
  }

  @Test
  void unscheduleJobsReportsTheTriggersAndThenTheJobsThatWentWithThem() throws Exception {
    JobDetail first = job("first", false);
    JobDetail second = job("second", false);
    scheduler.scheduleJob(first, inAnHour("t1", first));
    scheduler.scheduleJob(second, inAnHour("t2", second));

    scheduler.unscheduleJobs(List.of(TriggerKey.triggerKey("t1"), TriggerKey.triggerKey("t2")));

    assertThat(events)
        .containsExactly("unscheduled t1", "unscheduled t2", "deleted first", "deleted second");
  }

  @Test
  void aTriggerThatCompletesReportsItsJobOnce() throws Exception {
    JobDetail job = job("oneShot", false);
    scheduler.start();
    scheduler.scheduleJob(job, newTrigger().withIdentity("now").forJob(job).startNow().build());

    await().atMost(Duration.ofSeconds(20)).until(() -> events.contains("deleted oneShot"));
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(5))
        .until(() -> events.equals(List.of("deleted oneShot")));
    assertThat(RUNS).containsExactly("oneShot");
  }

  @Test
  void aListenerMayScheduleFromJobDeletedAndTheNewTriggerFiresWithoutTheIdleWait()
      throws Exception {
    scheduler
        .getListenerManager()
        .addSchedulerListener(
            new SchedulerListenerSupport() {
              @Override
              public void jobDeleted(JobKey jobKey) {
                if (jobKey.getName().equals("first")) {
                  JobDetail next = job("next", false);
                  try {
                    scheduler.scheduleJob(
                        next,
                        newTrigger()
                            .withIdentity("nextNow")
                            .forJob(next)
                            .withSchedule(simpleSchedule())
                            .startNow()
                            .build());
                  } catch (SchedulerException ex) {
                    throw new IllegalStateException(ex);
                  }
                }
              }
            });
    JobDetail first = job("first", false);
    scheduler.start();
    scheduler.scheduleJob(first, newTrigger().withIdentity("now").forJob(first).startNow().build());

    await().atMost(Duration.ofSeconds(30)).until(() -> RUNS.contains("next"));
    await().atMost(Duration.ofSeconds(30)).until(() -> events.contains("deleted next"));
    assertThat(events).containsExactly("deleted first", "deleted next");
  }
}
