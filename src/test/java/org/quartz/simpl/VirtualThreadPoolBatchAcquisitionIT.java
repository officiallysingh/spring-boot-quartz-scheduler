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
package org.quartz.simpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.spi.JobStore;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/**
 * With virtual threads the scheduler acquires several triggers per round trip to the store, as many
 * as both {@code batchTriggerAcquisitionMaxCount} and the pool's free capacity allow.
 */
class VirtualThreadPoolBatchAcquisitionIT extends AbstractMongoIT {

  /** The {@code maxCount} and result size of each {@code acquireNextTriggers} call. */
  record Acquisition(int maxCount, int acquired) {}

  static final AtomicInteger EXECUTED = new AtomicInteger();

  public static class CountingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      EXECUTED.incrementAndGet();
    }
  }

  private final List<Acquisition> acquisitions = new CopyOnWriteArrayList<>();

  private Scheduler scheduler;

  @BeforeEach
  void reset() {
    EXECUTED.set(0);
  }

  @AfterEach
  void shutdown() throws Exception {
    if (scheduler != null) {
      scheduler.shutdown(true);
    }
  }

  @Test
  void acquiresAWholeBatchWhenThePoolHasRoom() throws Exception {
    scheduler = scheduler("batch", 10, 5);
    scheduleJobsDueTogether(5);

    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).until(() -> EXECUTED.get() == 5);
    assertThat(acquisitions).contains(new Acquisition(5, 5));
  }

  @Test
  void batchIsLimitedByThePoolsFreeCapacity() throws Exception {
    scheduler = scheduler("capacity", 3, 5);
    scheduleJobsDueTogether(5);

    scheduler.start();

    await().atMost(Duration.ofSeconds(20)).until(() -> EXECUTED.get() == 5);
    assertThat(acquisitions)
        .isNotEmpty()
        .allSatisfy(a -> assertThat(a.maxCount()).isLessThanOrEqualTo(3));
    assertThat(acquisitions).contains(new Acquisition(3, 3));
  }

  private Scheduler scheduler(String name, int maxConcurrency, int batchSize) throws Exception {
    return StdSchedulerFactory.builder()
        .scheduler(
            TestSchedulers.scheduler()
                .name(name)
                .batchTriggerAcquisitionMaxCount(batchSize)
                .batchTriggerAcquisitionFireAheadTimeWindow(Duration.ofSeconds(1))
                .build())
        .threadPool(
            ThreadPoolSettings.builder().threadCount(maxConcurrency).virtualThreads(true).build())
        .jobStore(recording(TestSchedulers.mongoStore()))
        .build()
        .getScheduler();
  }

  /** Jobs whose triggers are all due at the same time, two seconds from now. */
  private void scheduleJobsDueTogether(int count) throws Exception {
    Instant due = Instant.now().plusSeconds(2);
    for (int i = 0; i < count; i++) {
      scheduler.scheduleJob(
          JobBuilder.newJob(CountingJob.class).withIdentity("job" + i).build(),
          TriggerBuilder.newTrigger().withIdentity("trigger" + i).startAt(due).build());
    }
  }

  private JobStore recording(JobStore store) {
    return (JobStore)
        Proxy.newProxyInstance(
            JobStore.class.getClassLoader(),
            new Class<?>[] {JobStore.class},
            (proxy, method, args) -> {
              Object result;
              try {
                result = method.invoke(store, args);
              } catch (InvocationTargetException ex) {
                throw ex.getCause();
              }
              if (method.getName().equals("acquireNextTriggers")) {
                int acquired = ((List<?>) result).size();
                if (acquired > 0) {
                  acquisitions.add(new Acquisition((Integer) args[1], acquired));
                }
              }
              return result;
            });
  }
}
