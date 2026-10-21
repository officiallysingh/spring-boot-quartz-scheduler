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

import org.quartz.Scheduler;
import org.quartz.SchedulerMetaData;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.spi.JobStore;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Health of a Quartz {@link Scheduler}: {@code DOWN} when it is shut down, when its job store
 * cannot be read, or when a clustered instance has not checked in for longer than the other
 * instances wait before recovering it ({@code cluster-checkin-interval} +
 * {@code cluster-checkin-misfire-threshold}); {@code UP} otherwise, including in standby.
 */
public class QuartzHealthIndicator extends AbstractHealthIndicator {

	private final Scheduler scheduler;

	private final JobStore jobStore;

	private final Clock clock;

	/**
	 * @param scheduler the scheduler
	 * @param jobStore its job store, if known: enables the cluster check-in check
	 */
	public QuartzHealthIndicator(Scheduler scheduler, JobStore jobStore) {
		this(scheduler, jobStore, Clock.systemUTC());
	}

	QuartzHealthIndicator(Scheduler scheduler, JobStore jobStore, Clock clock) {
		super("Quartz health check failed");
		this.scheduler = scheduler;
		this.jobStore = jobStore;
		this.clock = clock;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) throws Exception {
		SchedulerMetaData metaData = this.scheduler.getMetaData();
		builder.withDetail("scheduler", metaData.getSchedulerName())
			.withDetail("instanceId", metaData.getSchedulerInstanceId());
		if (metaData.isShutdown()) {
			builder.down().withDetail("state", "shutdown");
			return;
		}
		builder.withDetail("state", metaData.isInStandbyMode() ? "standby" : "started")
			.withDetail("jobStore", metaData.getJobStoreClass().getName())
			.withDetail("clustered", metaData.isJobStoreClustered());
		// a store read: fails (and so reports DOWN) when the store cannot be reached
		this.scheduler.getJobGroupNames();
		builder.up();
		// clustered stores check in from start on, in standby too
		if (metaData.isJobStoreClustered() && metaData.isStarted()
				&& this.jobStore instanceof PersistentJobStore persistentStore) {
			checkIn(builder, persistentStore);
		}
	}

	private void checkIn(Health.Builder builder, PersistentJobStore store) {
		Instant lastCheckin = store.getLastCheckin();
		Duration age = Duration.between(lastCheckin, this.clock.instant());
		JobStoreSettings settings = store.getSettings();
		Duration limit = settings.clusterCheckinInterval().plus(settings.clusterCheckinMisfireThreshold());
		builder.withDetail("lastCheckin", lastCheckin).withDetail("checkinAge", age);
		if (age.compareTo(limit) > 0) {
			builder.down().withDetail("reason", "no cluster check-in for " + age + " (limit " + limit + ")");
		}
	}

}
