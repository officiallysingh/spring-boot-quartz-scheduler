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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** What job and scheduler listeners receive when a job throws. */
class JobRunShellExceptionIT extends AbstractMongoIT {

  static final JobExecutionException THROWN_JOB_EXCEPTION =
      new JobExecutionException("declared failure");

  static final Map<String, JobExecutionException> SEEN_BY_JOB_LISTENER = new ConcurrentHashMap<>();

  static final Map<String, Duration> RUN_TIMES = new ConcurrentHashMap<>();

  static final Map<String, SchedulerException> SEEN_BY_SCHEDULER_LISTENER =
      new ConcurrentHashMap<>();

  public static class IllegalStateJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      throw new IllegalStateException("Auction must be published before starting");
    }
  }

  public static class JobExecutionExceptionJob implements Job {
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
      throw THROWN_JOB_EXCEPTION;
    }
  }

  private Scheduler scheduler;

  @BeforeEach
  void setUp() throws Exception {
    SEEN_BY_JOB_LISTENER.clear();
    RUN_TIMES.clear();
    SEEN_BY_SCHEDULER_LISTENER.clear();
    scheduler = TestSchedulers.create(TestSchedulers.scheduler().name("exceptions"), 2);
    scheduler
        .getListenerManager()
        .addJobListener(
            new JobListenerSupport() {
              @Override
              public String getName() {
                return "recording";
              }

              @Override
              public void jobWasExecuted(
                  JobExecutionContext context, JobExecutionException jobException) {
                Duration runTime = context.getJobRunTime();
                if (runTime != null) {
                  RUN_TIMES.put(context.getJobDetail().getKey().getName(), runTime);
                }
                if (jobException != null) {
                  SEEN_BY_JOB_LISTENER.put(context.getJobDetail().getKey().getName(), jobException);
                }
              }
            });
    scheduler
        .getListenerManager()
        .addSchedulerListener(
            new SchedulerListenerSupport() {
              @Override
              public void schedulerError(String msg, SchedulerException cause) {
                if (cause instanceof JobExecutionProcessException processException) {
                  SEEN_BY_SCHEDULER_LISTENER.put(
                      processException.getJobExecutionContext().getJobDetail().getKey().getName(),
                      cause);
                }
              }
            });
  }

  @AfterEach
  void tearDown() throws Exception {
    scheduler.shutdown(true);
  }

  @Test
  void anUnhandledExceptionIsTheDirectCauseOfTheListenersException() throws Exception {
    scheduler.scheduleJob(
        JobBuilder.newJob(IllegalStateJob.class).withIdentity("123", "auction").build(),
        TriggerBuilder.newTrigger().startNow().build());
    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).until(() -> SEEN_BY_JOB_LISTENER.containsKey("123"));
    JobExecutionException seen = SEEN_BY_JOB_LISTENER.get("123");
    assertThat(seen.getCause())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Auction must be published before starting");
    assertThat(seen.getMessage())
        .isEqualTo(
            "Job auction.123 threw an unhandled exception: java.lang.IllegalStateException: "
                + "Auction must be published before starting");
    assertThat(seen.refireImmediately()).isFalse();
    // a failed run has its (monotonic, never negative) run time too
    assertThat(RUN_TIMES.get("123")).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);

    // scheduler listeners still get the execution context with the job's throwable
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> SEEN_BY_SCHEDULER_LISTENER.containsKey("123"));
    assertThat(SEEN_BY_SCHEDULER_LISTENER.get("123").getCause()).isSameAs(seen.getCause());
  }

  @Test
  void aJobExecutionExceptionIsPassedThroughUnchanged() throws Exception {
    scheduler.scheduleJob(
        JobBuilder.newJob(JobExecutionExceptionJob.class).withIdentity("declared").build(),
        TriggerBuilder.newTrigger().startNow().build());
    scheduler.start();

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> SEEN_BY_JOB_LISTENER.containsKey("declared"));
    assertThat(SEEN_BY_JOB_LISTENER.get("declared")).isSameAs(THROWN_JOB_EXCEPTION);
    assertThat(SEEN_BY_SCHEDULER_LISTENER).doesNotContainKey("declared");
  }
}
