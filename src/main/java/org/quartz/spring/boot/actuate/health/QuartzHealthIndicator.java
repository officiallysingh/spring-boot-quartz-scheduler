/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.quartz.spring.boot.actuate.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.quartz.Scheduler;
import org.quartz.SchedulerInstance;
import org.quartz.SchedulerMetaData;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Health of a Quartz {@link Scheduler}: {@code DOWN} when it is shut down, when its job store
 * cannot be read, or when a clustered instance no longer counts as alive for the cluster ({@link
 * Scheduler#getSchedulerInstances()}: it has not checked in for longer than {@code
 * cluster-checkin-interval} + {@code cluster-checkin-misfire-threshold}, or another instance has
 * already recovered it); {@code UP} otherwise, including in standby. A clustered scheduler also
 * reports how many instances there are and how many are alive.
 */
public class QuartzHealthIndicator extends AbstractHealthIndicator {

  private final Scheduler scheduler;

  private final Clock clock;

  /**
   * Create a health indicator for the scheduler.
   *
   * @param scheduler the scheduler
   */
  public QuartzHealthIndicator(Scheduler scheduler) {
    this(scheduler, Clock.systemUTC());
  }

  QuartzHealthIndicator(Scheduler scheduler, Clock clock) {
    super("Quartz health check failed");
    this.scheduler = scheduler;
    this.clock = clock;
  }

  @Override
  protected void doHealthCheck(Health.Builder builder) throws Exception {
    SchedulerMetaData metaData = this.scheduler.getMetaData();
    builder
        .withDetail("scheduler", metaData.getSchedulerName())
        .withDetail("instanceId", metaData.getSchedulerInstanceId());
    if (metaData.isShutdown()) {
      builder.down().withDetail("state", "shutdown");
      return;
    }
    builder
        .withDetail("state", metaData.isInStandbyMode() ? "standby" : "started")
        .withDetail("jobStore", metaData.getJobStoreClass().getName())
        .withDetail("clustered", metaData.isJobStoreClustered());
    // a store read: fails (and so reports DOWN) when the store cannot be reached
    this.scheduler.getJobGroupNames();
    builder.up();
    // clustered stores check in from start on, in standby too
    if (metaData.isJobStoreClustered() && metaData.isStarted()) {
      checkIn(builder, this.scheduler.getSchedulerInstances());
    }
  }

  private void checkIn(Health.Builder builder, List<SchedulerInstance> instances) {
    builder
        .withDetail("instances", instances.size())
        .withDetail("aliveInstances", instances.stream().filter(SchedulerInstance::alive).count());
    SchedulerInstance current =
        instances.stream().filter(SchedulerInstance::current).findFirst().orElse(null);
    if (current == null) {
      builder
          .down()
          .withDetail("reason", "no cluster check-in record: recovered by another instance");
      return;
    }
    Instant lastCheckin = current.lastCheckin();
    if (lastCheckin != null) {
      Duration age = Duration.between(lastCheckin, this.clock.instant());
      builder.withDetail("lastCheckin", lastCheckin).withDetail("checkinAge", age);
      if (!current.alive()) {
        builder
            .down()
            .withDetail(
                "reason",
                "no cluster check-in for " + age + ": the other instances consider it failed");
      }
    }
  }
}
