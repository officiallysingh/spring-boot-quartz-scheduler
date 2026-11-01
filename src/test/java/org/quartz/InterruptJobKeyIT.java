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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** {@link Scheduler#interrupt(JobKey)} attempts every running instance of the job. */
class InterruptJobKeyIT extends AbstractMongoIT {

  static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

  static final Set<String> INTERRUPTED = ConcurrentHashMap.newKeySet();

  static volatile CountDownLatch release = new CountDownLatch(1);

  /** Blocks until interrupted (or released). */
  public static class InterruptibleJob implements InterruptableJob {

    private final CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public void execute(JobExecutionContext context) {
      RUNNING.add(context.getTrigger().getKey().getName());
      try {
        if (interrupted.await(60, TimeUnit.SECONDS)) {
          INTERRUPTED.add(context.getTrigger().getKey().getName());
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void interrupt() {
      interrupted.countDown();
    }
  }

  /** Blocks until released; cannot be interrupted. */
  public static class PlainJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      RUNNING.add(context.getTrigger().getKey().getName());
      try {
        release.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private Scheduler scheduler;

  @BeforeEach
  void setUp() throws Exception {
    RUNNING.clear();
    INTERRUPTED.clear();
    release = new CountDownLatch(1);
    scheduler = TestSchedulers.create(TestSchedulers.scheduler().name("interruptJobKey"), 5);
    // the execution fired by trigger "plain" gets a job instance that is not interruptible
    JobFactory factory =
        new JobFactory() {
          @Override
          public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) {
            return bundle.getTrigger().getKey().getName().equals("plain")
                ? new PlainJob()
                : new InterruptibleJob();
          }
        };
    scheduler.setJobFactory(factory);
    scheduler.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    release.countDown();
    scheduler.shutdown(true);
  }

  @Test
  void everyInterruptibleInstanceIsInterruptedAndTheFailureReportedAtTheEnd() throws Exception {
    JobDetail job =
        JobBuilder.newJob(InterruptibleJob.class).withIdentity("job").storeDurably().build();
    scheduler.addJob(job, false);
    // whatever order they are attempted in, both interruptible instances must be interrupted
    for (String name : new String[] {"first", "plain", "second"}) {
      scheduler.scheduleJob(
          TriggerBuilder.newTrigger().withIdentity(name).forJob(job).startNow().build());
    }
    await().atMost(Duration.ofSeconds(20)).until(() -> RUNNING.size() == 3);

    UnableToInterruptJobException failure =
        catchThrowableOfType(
            UnableToInterruptJobException.class, () -> scheduler.interrupt(job.getKey()));

    assertThat(failure).hasMessageContaining("1 of 3 executions of job DEFAULT.job");
    assertThat(failure.getSuppressed())
        .singleElement()
        .satisfies(
            suppressed ->
                assertThat(suppressed)
                    .isInstanceOf(UnableToInterruptJobException.class)
                    .hasMessageContaining(InterruptableJob.class.getName()));
    await().atMost(Duration.ofSeconds(10)).until(() -> INTERRUPTED.size() == 2);
    assertThat(INTERRUPTED).containsExactlyInAnyOrder("first", "second");
  }

  @Test
  void withoutFailuresItReportsWhetherAnythingWasInterrupted() throws Exception {
    JobDetail job =
        JobBuilder.newJob(InterruptibleJob.class).withIdentity("job").storeDurably().build();
    scheduler.addJob(job, false);
    assertThat(scheduler.interrupt(job.getKey())).isFalse();

    scheduler.scheduleJob(
        TriggerBuilder.newTrigger().withIdentity("only").forJob(job).startNow().build());
    await().atMost(Duration.ofSeconds(20)).until(() -> RUNNING.contains("only"));
    assertThat(scheduler.interrupt(job.getKey())).isTrue();
    await().atMost(Duration.ofSeconds(10)).until(() -> INTERRUPTED.contains("only"));
  }
}
