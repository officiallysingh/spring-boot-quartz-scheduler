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
package org.quartz.support;

import java.time.Duration;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.mongodb.MongoJobStoreSettings;

/**
 * Builds schedulers for integration tests, stored in the shared MongoDB container (database {@value
 * #DATABASE}), with the values a {@code Properties}-configured {@code StdSchedulerFactory} used for
 * absent keys before Phase 5 (DECISIONS.md D-012): scheduler name {@code QuartzScheduler}, a 5 s
 * misfire threshold (the former in-memory store's default) and worker threads that do not inherit
 * the context class loader. Callers clean the store with {@link AbstractMongoIT} (D-016).
 */
public final class TestSchedulers {

  private TestSchedulers() {}

  public static SchedulerSettings.Builder scheduler() {
    return SchedulerSettings.builder().name("QuartzScheduler");
  }

  public static ThreadPoolSettings threadPool(int threadCount) {
    return ThreadPoolSettings.builder().threadCount(threadCount).build();
  }

  public static final String DATABASE = "test";

  public static JobStoreSettings storeSettings() {
    return JobStoreSettings.builder().misfireThreshold(Duration.ofSeconds(5)).build();
  }

  /** A new MongoDB store on the shared container. */
  public static MongoJobStore mongoStore(JobStoreSettings settings) {
    return new MongoJobStore(
        AbstractMongoIT.mongoClient(), DATABASE, settings, MongoJobStoreSettings.defaults());
  }

  public static MongoJobStore mongoStore() {
    return mongoStore(storeSettings());
  }

  public static StdSchedulerFactory.Builder factory(
      SchedulerSettings.Builder scheduler, int threadCount) {
    return StdSchedulerFactory.builder()
        .scheduler(scheduler.build())
        .threadPool(threadPool(threadCount))
        .jobStore(mongoStore());
  }

  public static Scheduler create(SchedulerSettings.Builder scheduler, int threadCount)
      throws SchedulerException {
    return factory(scheduler, threadCount).build().getScheduler();
  }
}
