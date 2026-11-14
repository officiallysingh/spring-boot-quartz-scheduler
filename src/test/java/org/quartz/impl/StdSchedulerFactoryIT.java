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
 *
 */
package org.quartz.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.core.QuartzScheduler;
import org.quartz.core.QuartzSchedulerResources;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spi.ThreadPool;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

class StdSchedulerFactoryIT extends AbstractMongoIT {

  private final List<Scheduler> created = new ArrayList<>();

  @AfterEach
  void shutdownSchedulers() throws SchedulerException {
    for (Scheduler scheduler : created) {
      scheduler.shutdown();
    }
  }

  private Scheduler create(StdSchedulerFactory factory) throws SchedulerException {
    Scheduler scheduler = factory.getScheduler();
    created.add(scheduler);
    return scheduler;
  }

  private static SchedulerSettings.Builder named(String name) {
    return SchedulerSettings.builder().name(name);
  }

  @Test
  void defaultSettingsAreApplied() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .jobStore(TestSchedulers.mongoStore(JobStoreSettings.defaults()))
                .build());

    assertThat(scheduler.getSchedulerName()).isEqualTo("DefaultQuartzScheduler");
    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("NON_CLUSTERED");
    assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(10);
    assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(MongoJobStore.class);
    assertThat(scheduler.getMetaData().getThreadPoolClass()).isEqualTo(SimpleThreadPool.class);

    MongoJobStore store = (MongoJobStore) resources(scheduler).getJobStore();
    assertThat(store.getSettings().misfireThreshold()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void settingsAreApplied() throws Exception {
    QuartzSettings settings =
        new QuartzSettings(
            named("SettingsApplied")
                .instanceId("node-1")
                .threadName("custom-scheduler-thread")
                .batchTriggerAcquisitionMaxCount(3)
                .batchTriggerAcquisitionFireAheadTimeWindow(Duration.ofMillis(250))
                .interruptJobsOnShutdown(true)
                .contextEntry("greeting", "hello")
                .build(),
            ThreadPoolSettings.builder().threadCount(2).threadNamePrefix("custom-worker").build());
    JobStoreSettings storeSettings =
        JobStoreSettings.builder().misfireThreshold(Duration.ofSeconds(7)).build();

    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .settings(settings)
                .jobStore(TestSchedulers.mongoStore(storeSettings))
                .build());
    QuartzSchedulerResources resources = resources(scheduler);

    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("node-1");
    assertThat(scheduler.getContext().getString("greeting")).isEqualTo("hello");
    assertThat(resources.getThreadName()).isEqualTo("custom-scheduler-thread");
    assertThat(resources.getMaxBatchSize()).isEqualTo(3);
    assertThat(resources.getBatchTimeWindow()).isEqualTo(Duration.ofMillis(250));
    assertThat(resources.isInterruptJobsOnShutdown()).isTrue();
    assertThat(((SimpleThreadPool) resources.getThreadPool()).getThreadNamePrefix())
        .isEqualTo("custom-worker");
    assertThat(resources.getThreadPool().getPoolSize()).isEqualTo(2);
    assertThat(((MongoJobStore) resources.getJobStore()).getSettings().misfireThreshold())
        .isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void pluginsAreInitializedWithTheirNamesAndFollowTheSchedulerLifecycle() throws Exception {
    StringBuilder result = new StringBuilder();
    SchedulerPlugin testPlugin =
        new SchedulerPlugin() {
          public void initialize(String name, Scheduler scheduler) throws SchedulerException {
            result.append(name).append("|").append(scheduler.getSchedulerName());
          }

          public void start() {
            result.append("|start");
          }

          public void shutdown() {
            result.append("|shutdown");
          }
        };

    Scheduler scheduler =
        StdSchedulerFactory.builder()
            .jobStore(TestSchedulers.mongoStore())
            .scheduler(named("MyScheduler").instanceId("Instance1").build())
            .threadPool(ThreadPoolSettings.builder().threadCount(1).build())
            .plugin("TestPlugin", testPlugin)
            .build()
            .getScheduler();
    scheduler.start();
    scheduler.shutdown();

    assertThat(result).hasToString("TestPlugin|MyScheduler|start|shutdown");
  }

  @Test
  void listenersAreRegisteredForEverything() throws Exception {
    JobListenerSupport jobListener =
        new JobListenerSupport() {
          public String getName() {
            return "jobListener";
          }
        };
    TriggerListenerSupport triggerListener =
        new TriggerListenerSupport() {
          public String getName() {
            return "triggerListener";
          }
        };

    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .jobStore(TestSchedulers.mongoStore())
                .scheduler(named("Listeners").build())
                .jobListener(jobListener)
                .triggerListener(triggerListener)
                .build());

    assertThat(scheduler.getListenerManager().getJobListener("jobListener")).isSameAs(jobListener);
    assertThat(scheduler.getListenerManager().getTriggerListener("triggerListener"))
        .isSameAs(triggerListener);
  }

  @Test
  void workerThreadNamesContainTheSchedulerName() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .jobStore(TestSchedulers.mongoStore())
                .scheduler(named("ThreadNames").build())
                .threadPool(ThreadPoolSettings.builder().threadCount(4).build())
                .build());
    ThreadPool tp = resources(scheduler).getThreadPool();
    List<?> workers = getField(tp, "workers");

    assertThat(workers.get(0).toString()).doesNotContain("null").contains("ThreadNames");
  }

  @Test
  void getSchedulerReturnsTheBoundSchedulerUntilItIsShutDown() throws Exception {
    StdSchedulerFactory factory =
        StdSchedulerFactory.builder()
            .scheduler(named("Rebind").build())
            .jobStore(TestSchedulers.mongoStore())
            .build();

    Scheduler first = create(factory);
    assertThat(factory.getScheduler()).isSameAs(first);

    first.shutdown();
    Scheduler second = create(factory);
    assertThat(second).isNotSameAs(first);
    assertThat(second.isShutdown()).isFalse();
  }

  @Test
  void autoInstanceIdIsNonClusteredForANonClusteredStore() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .jobStore(TestSchedulers.mongoStore())
                .scheduler(named("AutoNonClustered").instanceIdAuto(true).build())
                .instanceIdGenerator(() -> "generated")
                .build());

    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("NON_CLUSTERED");
  }

  @Test
  void autoInstanceIdComesFromTheGeneratorForAClusteredStore() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .scheduler(named("AutoClustered").instanceIdAuto(true).build())
                .jobStore(clusteredStore())
                .instanceIdGenerator(() -> "generated")
                .build());

    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("generated");
  }

  @Test
  void autoInstanceIdDefaultsToTheSimpleGenerator() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .scheduler(named("AutoSimple").instanceIdAuto(true).build())
                .jobStore(clusteredStore())
                .build());

    assertThat(scheduler.getSchedulerInstanceId()).isNotEqualTo("NON_CLUSTERED").isNotBlank();
  }

  @Test
  void customInstanceIdGenerator() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .scheduler(named("CustomGenerator").instanceIdAuto(true).build())
                .jobStore(clusteredStore())
                .instanceIdGenerator(() -> "generated-id")
                .build());

    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("generated-id");
  }

  @Test
  void failingInstanceIdGeneratorFailsTheFactory() {
    InstanceIdGenerator generator =
        () -> {
          throw new SchedulerException("no instance id");
        };

    assertThatThrownBy(
            () ->
                create(
                    StdSchedulerFactory.builder()
                        .scheduler(named("FailingGenerator").instanceIdAuto(true).build())
                        .jobStore(clusteredStore())
                        .instanceIdGenerator(generator)
                        .build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("instance id");
  }

  @Test
  void aClusteredStoreRejectsAnInstanceIdEveryInstanceWouldShare() {
    assertThat(
            org.quartz.ClusteredInstanceIdException.isSharedByEveryInstance(
                SchedulerSettings.DEFAULT_INSTANCE_ID))
        .isTrue();
    for (String shared : List.of(SchedulerSettings.DEFAULT_INSTANCE_ID, "SYS_PROP")) {
      assertThatThrownBy(
              () ->
                  create(
                      StdSchedulerFactory.builder()
                          .scheduler(named("Shared" + shared).instanceId(shared).build())
                          .jobStore(clusteredStore())
                          .build()))
          .isInstanceOfSatisfying(
              org.quartz.ClusteredInstanceIdException.class,
              ex -> assertThat(ex.getInstanceId()).isEqualTo(shared))
          .hasMessageContaining("clustered")
          .hasMessageContaining("'" + shared + "'");
    }
  }

  @Test
  void aStoreThatIsNotClusteredKeepsTheDefaultInstanceId() throws Exception {
    Scheduler scheduler =
        create(
            StdSchedulerFactory.builder()
                .scheduler(named("NotClustered").build())
                .jobStore(TestSchedulers.mongoStore())
                .build());

    assertThat(scheduler.getSchedulerInstanceId()).isEqualTo(SchedulerSettings.DEFAULT_INSTANCE_ID);
  }

  private static MongoJobStore clusteredStore() {
    return TestSchedulers.mongoStore(
        TestSchedulers.storeSettings().toBuilder().clustered(true).build());
  }

  private static QuartzSchedulerResources resources(Scheduler scheduler) throws Exception {
    QuartzScheduler qs = getField(scheduler, "sched");
    return getField(qs, "resources");
  }

  @SuppressWarnings("unchecked")
  private static <T> T getField(Object obj, String fieldName) throws Exception {
    Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return (T) field.get(obj);
  }
}
