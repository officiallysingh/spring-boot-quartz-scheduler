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

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** Every job execution runs with the scheduler's class loader as context class loader. */
class JobRunShellClassLoaderIT extends AbstractMongoIT {

  static final AtomicReference<ClassLoader> SEEN = new AtomicReference<>();

  public static class ClassLoaderRecordingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      SEEN.set(Thread.currentThread().getContextClassLoader());
    }
  }

  private final URLClassLoader applicationClassLoader =
      new URLClassLoader(new URL[0], getClass().getClassLoader());

  private Scheduler scheduler;

  @AfterEach
  void tearDown() throws Exception {
    SEEN.set(null);
    if (scheduler != null) {
      scheduler.shutdown(true);
    }
    applicationClassLoader.close();
  }

  @Test
  void jobsOnPlatformThreadsUseTheSchedulersClassLoaderAndTheThreadGetsItsOwnBack()
      throws Exception {
    SimpleThreadPool pool = new SimpleThreadPool(1, Thread.NORM_PRIORITY);
    scheduler =
        StdSchedulerFactory.builder()
            .scheduler(TestSchedulers.scheduler().name("platform").build())
            .threadPool(pool)
            .classLoader(applicationClassLoader)
            .jobStore(TestSchedulers.mongoStore())
            .build()
            .getScheduler();

    runJobOnce();

    assertThat(SEEN.get()).isSameAs(applicationClassLoader);
    // the pool's only thread ran the job; afterwards it has its own context class loader again
    AtomicReference<ClassLoader> afterwards = new AtomicReference<>();
    pool.runInThread(() -> afterwards.set(Thread.currentThread().getContextClassLoader()));
    await().atMost(Duration.ofSeconds(5)).until(() -> afterwards.get() != null);
    assertThat(afterwards.get()).isNotSameAs(applicationClassLoader);
  }

  @Test
  void jobsOnVirtualThreadsUseTheSchedulersClassLoader() throws Exception {
    scheduler =
        StdSchedulerFactory.builder()
            .scheduler(TestSchedulers.scheduler().name("virtual").build())
            .threadPool(ThreadPoolSettings.builder().threadCount(2).virtualThreads(true).build())
            .classLoader(applicationClassLoader)
            .jobStore(TestSchedulers.mongoStore())
            .build()
            .getScheduler();

    runJobOnce();

    assertThat(SEEN.get()).isSameAs(applicationClassLoader);
  }

  private void runJobOnce() throws Exception {
    scheduler.scheduleJob(
        JobBuilder.newJob(ClassLoaderRecordingJob.class).withIdentity("recording").build(),
        TriggerBuilder.newTrigger().withIdentity("recording").startNow().build());
    scheduler.start();
    await().atMost(Duration.ofSeconds(20)).until(() -> SEEN.get() != null);
  }
}
