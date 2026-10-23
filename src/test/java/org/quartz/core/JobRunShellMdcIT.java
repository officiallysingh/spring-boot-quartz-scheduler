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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;
import org.slf4j.MDC;

/** Each execution, listeners included, runs with the job's MDC keys, removed afterwards. */
class JobRunShellMdcIT extends AbstractMongoIT {

  static final Map<String, Map<String, String>> SEEN_BY_JOB = new ConcurrentHashMap<>();

  static final Map<String, Map<String, String>> SEEN_BY_LISTENER = new ConcurrentHashMap<>();

  public static class MdcCapturingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      SEEN_BY_JOB.put(context.getJobDetail().getKey().getName(), copyOfMdc());
    }
  }

  private Scheduler scheduler;

  private SimpleThreadPool pool;

  @BeforeEach
  void setUp() throws Exception {
    SEEN_BY_JOB.clear();
    SEEN_BY_LISTENER.clear();
    pool = new SimpleThreadPool(1, Thread.NORM_PRIORITY);
    scheduler =
        StdSchedulerFactory.builder()
            .scheduler(TestSchedulers.scheduler().name("mdc").build())
            .threadPool(pool)
            .jobStore(TestSchedulers.mongoStore())
            .jobListener(new MdcCapturingListener())
            .build()
            .getScheduler();
  }

  @AfterEach
  void tearDown() throws Exception {
    scheduler.shutdown(true);
  }

  @Test
  void jobAndListenersSeeTheExecutionsKeysAndTheThreadIsLeftClean() throws Exception {
    scheduler.scheduleJob(
        JobBuilder.newJob(MdcCapturingJob.class).withIdentity("first", "mail").build(),
        TriggerBuilder.newTrigger().withIdentity("firstTrigger", "mail").startNow().build());
    scheduler.scheduleJob(
        JobBuilder.newJob(MdcCapturingJob.class).withIdentity("second", "payments").build(),
        TriggerBuilder.newTrigger().withIdentity("secondTrigger", "payments").startNow().build());

    scheduler.start();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> SEEN_BY_JOB.size() == 2 && SEEN_BY_LISTENER.size() == 2);

    Map<String, String> first = SEEN_BY_JOB.get("first");
    assertThat(first)
        .containsEntry(JobRunShell.MDC_JOB_KEY, "mail.first")
        .containsEntry(JobRunShell.MDC_TRIGGER_KEY, "mail.firstTrigger")
        .containsKey(JobRunShell.MDC_FIRE_INSTANCE_ID);
    assertThat(SEEN_BY_JOB.get("second"))
        .containsEntry(JobRunShell.MDC_JOB_KEY, "payments.second")
        .containsEntry(JobRunShell.MDC_TRIGGER_KEY, "payments.secondTrigger");
    assertThat(SEEN_BY_JOB.get("second").get(JobRunShell.MDC_FIRE_INSTANCE_ID))
        .isNotEqualTo(first.get(JobRunShell.MDC_FIRE_INSTANCE_ID));
    assertThat(SEEN_BY_LISTENER.get("first")).isEqualTo(first);

    // the pool's only thread ran both jobs; the next task on it sees none of their keys
    AtomicReference<Map<String, String>> afterwards = new AtomicReference<>();
    pool.runInThread(() -> afterwards.set(copyOfMdc()));
    await().atMost(Duration.ofSeconds(5)).until(() -> afterwards.get() != null);
    assertThat(afterwards.get())
        .doesNotContainKeys(
            JobRunShell.MDC_JOB_KEY, JobRunShell.MDC_TRIGGER_KEY, JobRunShell.MDC_FIRE_INSTANCE_ID);
  }

  private static Map<String, String> copyOfMdc() {
    Map<String, String> mdc = MDC.getCopyOfContextMap();
    return (mdc != null) ? mdc : new HashMap<>();
  }

  static class MdcCapturingListener implements JobListener {
    @Override
    public String getName() {
      return "mdcCapturing";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {}

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {}

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
      SEEN_BY_LISTENER.put(context.getJobDetail().getKey().getName(), copyOfMdc());
    }
  }
}
