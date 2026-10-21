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
package org.quartz.spring.boot.actuate.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.SchedulerPlugin;

/**
 * Micrometer metrics for a Quartz {@link Scheduler}, as a {@link SchedulerPlugin} that adds a
 * global job and trigger listener. Every meter is tagged with {@code scheduler}:
 * <ul>
 * <li>{@code quartz.jobs.executed} (counter, tag {@code job.group}): finished executions;</li>
 * <li>{@code quartz.jobs.failed} (counter, tag {@code job.group}): executions that threw;</li>
 * <li>{@code quartz.jobs.duration} (timer, tags {@code job.group}, {@code result}
 * {@code success|failure}): execution time;</li>
 * <li>{@code quartz.triggers.misfired} (counter, tag {@code trigger.group}): misfires.</li>
 * </ul>
 * Job and trigger names are not tags, to keep the number of time series bounded.
 */
public class QuartzMetrics implements SchedulerPlugin {

	static final String LISTENER_NAME = "quartzMetrics";

	private final MeterRegistry registry;

	private String schedulerName;

	public QuartzMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void initialize(String name, Scheduler scheduler, ClassLoadHelper loadHelper) throws SchedulerException {
		this.schedulerName = scheduler.getSchedulerName();
		scheduler.getListenerManager().addJobListener(new JobMetrics());
		scheduler.getListenerManager().addTriggerListener(new TriggerMetrics());
	}

	@Override
	public void start() {
	}

	@Override
	public void shutdown() {
	}

	private final class JobMetrics extends JobListenerSupport {

		@Override
		public String getName() {
			return LISTENER_NAME;
		}

		@Override
		public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
			String group = context.getJobDetail().getKey().getGroup();
			boolean failed = jobException != null;
			counter("quartz.jobs.executed", "Finished Quartz job executions", group).increment();
			if (failed) {
				counter("quartz.jobs.failed", "Quartz job executions that failed", group).increment();
			}
			Timer.builder("quartz.jobs.duration")
				.description("Quartz job execution time")
				.tags("scheduler", schedulerName, "job.group", group, "result", failed ? "failure" : "success")
				.register(registry)
				.record(context.getJobRunTime());
		}

		private Counter counter(String name, String description, String group) {
			return Counter.builder(name)
				.description(description)
				.tags("scheduler", schedulerName, "job.group", group)
				.register(registry);
		}

	}

	private final class TriggerMetrics extends TriggerListenerSupport {

		@Override
		public String getName() {
			return LISTENER_NAME;
		}

		@Override
		public void triggerMisfired(Trigger trigger) {
			Counter.builder("quartz.triggers.misfired")
				.description("Quartz trigger misfires")
				.tags("scheduler", schedulerName, "trigger.group", trigger.getKey().getGroup())
				.register(registry)
				.increment();
		}

	}

}
