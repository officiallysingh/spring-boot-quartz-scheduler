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

package org.quartz.spring.boot.autoconfigure;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.quartz.Calendar;
import org.quartz.JobDetail;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerListener;
import org.quartz.Trigger;
import org.quartz.TriggerListener;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.plugins.history.LoggingJobHistoryPlugin;
import org.quartz.plugins.history.LoggingTriggerHistoryPlugin;
import org.quartz.plugins.interrupt.JobInterruptMonitorPlugin;
import org.quartz.simpl.SystemPropertyInstanceIdGenerator;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.JobStore;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spring.LocalTaskExecutorThreadPool;
import org.quartz.spring.SchedulerFactoryBean;
import org.quartz.spring.SpringBeanJobFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for Quartz Scheduler.
 * <p>
 * Creates a {@link SchedulerFactoryBean} from {@link QuartzProperties} and registers every
 * {@link JobDetail}, {@link Trigger}, {@link Calendar} (by bean name), {@link JobListener},
 * {@link TriggerListener}, {@link SchedulerListener} and {@link SchedulerPlugin} (by bean
 * name) bean with it. A unique {@link InstanceIdGenerator} or {@link Clock} bean is used if
 * present. The job store follows {@code spring.quartz.job-store.type}: {@code mongodb} uses
 * the application's {@code MongoClient}, {@code redis} its {@code RedisConnectionFactory}.
 * When the type is unset, the store whose connection bean is present is used; startup fails
 * with a {@link JobStoreUnavailableException} when there is none or both are.
 * Jobs annotated with {@link org.quartz.ExecuteInTransaction} run in Spring-managed
 * transactions; {@code spring.quartz.transaction.enabled} makes every job do so. Backs off
 * when the application defines its own {@link Scheduler} or {@link SchedulerFactoryBean}.
 *
 * @author Vedran Pavic
 * @author Stephane Nicoll
 * @since 4.0.0
 */
@AutoConfiguration(afterName = { "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
		"org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
		"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration" })
@ConditionalOnClass({ Scheduler.class, SchedulerFactoryBean.class })
@EnableConfigurationProperties(QuartzProperties.class)
@Import({ JobStoreConfigurations.MongoJobStoreConfiguration.class,
		JobStoreConfigurations.RedisJobStoreConfiguration.class })
public final class QuartzAutoConfiguration {

	static final String JOB_HISTORY_PLUGIN = "jobHistory";

	static final String TRIGGER_HISTORY_PLUGIN = "triggerHistory";

	static final String JOB_INTERRUPT_MONITOR_PLUGIN = "jobInterruptMonitor";

	@Bean
	@ConditionalOnMissingBean({ Scheduler.class, SchedulerFactoryBean.class })
	SchedulerFactoryBean quartzScheduler(QuartzProperties properties,
			ObjectProvider<SchedulerFactoryBeanCustomizer> customizers, ObjectProvider<JobDetail> jobDetails,
			Map<String, Calendar> calendars, ObjectProvider<Trigger> triggers,
			ObjectProvider<JobListener> jobListeners, ObjectProvider<TriggerListener> triggerListeners,
			ObjectProvider<SchedulerListener> schedulerListeners, Map<String, SchedulerPlugin> plugins,
			ObjectProvider<InstanceIdGenerator> instanceIdGenerator, ObjectProvider<Clock> clock,
			ObjectProvider<JobStoreFactory> jobStoreFactories, ApplicationContext applicationContext) {
		SchedulerFactoryBean schedulerFactoryBean = new SchedulerFactoryBean();
		schedulerFactoryBean.setJobStore(createJobStore(properties, jobStoreFactories));
		SpringBeanJobFactory jobFactory = new SpringBeanJobFactory();
		jobFactory.setApplicationContext(applicationContext);
		schedulerFactoryBean.setJobFactory(jobFactory);
		QuartzSettings settings = new QuartzSettings(properties.scheduler().toSettings(),
				properties.threadPool().toSettings());
		schedulerFactoryBean.setSettings(settings);
		schedulerFactoryBean.setAutoStartup(properties.autoStartup());
		schedulerFactoryBean.setStartupDelay(properties.startupDelay());
		schedulerFactoryBean.setWaitForJobsToCompleteOnShutdown(properties.waitForJobsToCompleteOnShutdown());
		schedulerFactoryBean.setOverwriteExistingJobs(properties.overwriteExistingJobs());
		schedulerFactoryBean.setTransactionalJobs(properties.transaction().enabled());
		schedulerFactoryBean.setDefaultJobTransactionPropagation(properties.transaction().defaultPropagation());
		if (properties.threadPool().virtualThreads()) {
			schedulerFactoryBean.setThreadPool(new LocalTaskExecutorThreadPool(virtualThreadExecutor(settings),
					properties.threadPool().threadCount()));
		}
		if (properties.scheduler().isSystemPropertyInstanceId()) {
			schedulerFactoryBean.setInstanceIdGenerator(new SystemPropertyInstanceIdGenerator());
		}
		else {
			instanceIdGenerator.ifUnique(schedulerFactoryBean::setInstanceIdGenerator);
		}
		clock.ifUnique(schedulerFactoryBean::setClock);
		schedulerFactoryBean.setPlugins(plugins(properties.plugins(), plugins));
		schedulerFactoryBean.setJobDetails(jobDetails.orderedStream().toArray(JobDetail[]::new));
		schedulerFactoryBean.setCalendars(calendars);
		schedulerFactoryBean.setTriggers(triggers.orderedStream().toArray(Trigger[]::new));
		schedulerFactoryBean.setGlobalJobListeners(jobListeners.orderedStream().toArray(JobListener[]::new));
		schedulerFactoryBean
			.setGlobalTriggerListeners(triggerListeners.orderedStream().toArray(TriggerListener[]::new));
		schedulerFactoryBean
			.setSchedulerListeners(schedulerListeners.orderedStream().toArray(SchedulerListener[]::new));
		customizers.orderedStream().forEach((customizer) -> customizer.customize(schedulerFactoryBean));
		return schedulerFactoryBean;
	}

	/**
	 * The store for {@code spring.quartz.job-store.type}, or, when it is unset, the only store
	 * whose connection bean is present.
	 * @throws JobStoreUnavailableException if there is no such store
	 */
	private static JobStore createJobStore(QuartzProperties properties, ObjectProvider<JobStoreFactory> factories) {
		JobStoreType type = properties.jobStore().type();
		List<JobStoreFactory> available = factories.orderedStream().toList();
		List<JobStoreFactory> candidates = (type != null)
				? available.stream().filter((candidate) -> candidate.type() == type).toList() : available;
		if (candidates.size() != 1) {
			throw new JobStoreUnavailableException(type,
					available.stream().map(JobStoreFactory::type).sorted().toList());
		}
		return candidates.get(0).create(properties);
	}

	/**
	 * A virtual thread per job. The {@link LocalTaskExecutorThreadPool} it runs in limits
	 * it to {@code thread-count} jobs at a time: the scheduler acquires no triggers while
	 * that many run.
	 */
	private static SimpleAsyncTaskExecutor virtualThreadExecutor(QuartzSettings settings) {
		String prefix = settings.threadPool().threadNamePrefix();
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(
				((prefix != null) ? prefix : settings.scheduler().name() + "_Worker") + "-");
		executor.setVirtualThreads(true);
		return executor;
	}

	private static Map<String, SchedulerPlugin> plugins(QuartzProperties.Plugins properties,
			Map<String, SchedulerPlugin> pluginBeans) {
		Map<String, SchedulerPlugin> plugins = new LinkedHashMap<>();
		if (properties.jobHistory().enabled()) {
			plugins.put(JOB_HISTORY_PLUGIN, jobHistoryPlugin(properties.jobHistory()));
		}
		if (properties.triggerHistory().enabled()) {
			plugins.put(TRIGGER_HISTORY_PLUGIN, triggerHistoryPlugin(properties.triggerHistory()));
		}
		if (properties.jobInterruptMonitor().enabled()) {
			JobInterruptMonitorPlugin plugin = new JobInterruptMonitorPlugin();
			plugin.setDefaultMaxRunTime(properties.jobInterruptMonitor().defaultMaxRunTime());
			plugins.put(JOB_INTERRUPT_MONITOR_PLUGIN, plugin);
		}
		pluginBeans.forEach((name, plugin) -> {
			if (plugins.putIfAbsent(name, plugin) != null) {
				throw new IllegalStateException("SchedulerPlugin bean '" + name
						+ "' clashes with the built-in plugin of the same name enabled in 'spring.quartz.plugins'");
			}
		});
		return plugins;
	}

	private static LoggingJobHistoryPlugin jobHistoryPlugin(QuartzProperties.Plugins.JobHistory properties) {
		LoggingJobHistoryPlugin plugin = new LoggingJobHistoryPlugin();
		if (properties.jobToBeFiredMessage() != null) {
			plugin.setJobToBeFiredMessage(properties.jobToBeFiredMessage());
		}
		if (properties.jobSuccessMessage() != null) {
			plugin.setJobSuccessMessage(properties.jobSuccessMessage());
		}
		if (properties.jobFailedMessage() != null) {
			plugin.setJobFailedMessage(properties.jobFailedMessage());
		}
		if (properties.jobWasVetoedMessage() != null) {
			plugin.setJobWasVetoedMessage(properties.jobWasVetoedMessage());
		}
		return plugin;
	}

	private static LoggingTriggerHistoryPlugin triggerHistoryPlugin(
			QuartzProperties.Plugins.TriggerHistory properties) {
		LoggingTriggerHistoryPlugin plugin = new LoggingTriggerHistoryPlugin();
		if (properties.triggerFiredMessage() != null) {
			plugin.setTriggerFiredMessage(properties.triggerFiredMessage());
		}
		if (properties.triggerMisfiredMessage() != null) {
			plugin.setTriggerMisfiredMessage(properties.triggerMisfiredMessage());
		}
		if (properties.triggerCompleteMessage() != null) {
			plugin.setTriggerCompleteMessage(properties.triggerCompleteMessage());
		}
		return plugin;
	}

}
