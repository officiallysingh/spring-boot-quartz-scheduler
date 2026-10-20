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

import java.time.Duration;
import java.util.Map;

import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.transaction.annotation.Propagation;

/**
 * Configuration properties for the Quartz Scheduler integration. Every duration is a
 * {@link Duration}; the nested groups map onto the typed {@link SchedulerSettings},
 * {@link ThreadPoolSettings} and {@link JobStoreSettings}.
 *
 * @param autoStartup whether to automatically start the scheduler after initialization
 * @param startupDelay delay after which the scheduler is started once initialization
 * completes. Setting this property makes sense if no jobs should be run before the entire
 * application has started up
 * @param waitForJobsToCompleteOnShutdown whether to wait for running jobs to complete on
 * shutdown
 * @param overwriteExistingJobs whether configured jobs should overwrite existing job
 * definitions
 * @param scheduler scheduler settings
 * @param threadPool worker thread pool settings
 * @param jobStore job store settings
 * @param plugins built-in scheduler plugins
 * @param transaction transactions around job executions
 * @author Vedran Pavic
 * @author Stephane Nicoll
 * @since 4.0.0
 */
@ConfigurationProperties("spring.quartz")
public record QuartzProperties(
		@DefaultValue("true") boolean autoStartup,
		@DefaultValue("0s") Duration startupDelay,
		boolean waitForJobsToCompleteOnShutdown,
		boolean overwriteExistingJobs,
		@DefaultValue Scheduler scheduler,
		@DefaultValue ThreadPool threadPool,
		@DefaultValue JobStore jobStore,
		@DefaultValue Plugins plugins,
		@DefaultValue Transaction transaction) {

	/**
	 * Value of {@code instance-id} that generates the id when the store is clustered.
	 */
	public static final String AUTO_INSTANCE_ID = "AUTO";

	/**
	 * Value of {@code instance-id} that reads the id from the
	 * {@code org.quartz.scheduler.instanceId} system property when the store is clustered.
	 */
	public static final String SYSTEM_PROPERTY_INSTANCE_ID = "SYS_PROP";

	/**
	 * Scheduler settings.
	 *
	 * @param name name of the scheduler, shared by all nodes of a cluster
	 * @param instanceId instance id of this node; "AUTO" generates one and "SYS_PROP" reads
	 * the "org.quartz.scheduler.instanceId" system property (both only when the job store
	 * is clustered)
	 * @param threadName name of the scheduler thread; defaults to the scheduler name
	 * followed by "_QuartzSchedulerThread"
	 * @param makeSchedulerThreadDaemon whether the scheduler thread is a daemon thread
	 * @param threadsInheritContextClassLoaderOfInitializer whether the scheduler thread uses
	 * the context class loader of the thread that creates the scheduler
	 * @param idleWaitTime how long the scheduler thread waits for new triggers when idle; at
	 * least 1s
	 * @param batchTriggerAcquisitionFireAheadTimeWindow how far ahead of their fire time
	 * triggers may be acquired
	 * @param batchTriggerAcquisitionMaxCount maximum number of triggers acquired at once
	 * @param interruptJobsOnShutdown whether to interrupt running interruptable jobs on
	 * shutdown without waiting
	 * @param interruptJobsOnShutdownWithWait whether to interrupt running interruptable jobs
	 * on shutdown with waiting
	 * @param context initial entries of the scheduler context
	 */
	public record Scheduler(
			@DefaultValue("quartzScheduler") String name,
			@DefaultValue(SchedulerSettings.DEFAULT_INSTANCE_ID) String instanceId,
			String threadName,
			boolean makeSchedulerThreadDaemon,
			boolean threadsInheritContextClassLoaderOfInitializer,
			@DefaultValue("30s") Duration idleWaitTime,
			@DefaultValue("0s") Duration batchTriggerAcquisitionFireAheadTimeWindow,
			@DefaultValue("1") int batchTriggerAcquisitionMaxCount,
			boolean interruptJobsOnShutdown,
			boolean interruptJobsOnShutdownWithWait,
			Map<String, String> context) {

		/**
		 * Whether the instance id is read from a system property ({@code SYS_PROP}).
		 */
		public boolean isSystemPropertyInstanceId() {
			return SYSTEM_PROPERTY_INSTANCE_ID.equals(this.instanceId);
		}

		public SchedulerSettings toSettings() {
			boolean auto = AUTO_INSTANCE_ID.equals(this.instanceId) || isSystemPropertyInstanceId();
			return SchedulerSettings.builder()
				.name(this.name)
				.instanceId(auto ? SchedulerSettings.DEFAULT_INSTANCE_ID : this.instanceId)
				.instanceIdAuto(auto)
				.threadName(this.threadName)
				.makeSchedulerThreadDaemon(this.makeSchedulerThreadDaemon)
				.threadsInheritContextClassLoaderOfInitializer(this.threadsInheritContextClassLoaderOfInitializer)
				.idleWaitTime(this.idleWaitTime)
				.batchTriggerAcquisitionFireAheadTimeWindow(this.batchTriggerAcquisitionFireAheadTimeWindow)
				.batchTriggerAcquisitionMaxCount(this.batchTriggerAcquisitionMaxCount)
				.interruptJobsOnShutdown(this.interruptJobsOnShutdown)
				.interruptJobsOnShutdownWithWait(this.interruptJobsOnShutdownWithWait)
				.context(this.context)
				.build();
		}

	}

	/**
	 * Worker thread pool settings.
	 *
	 * @param threadCount number of worker threads, or the maximum number of concurrently
	 * running jobs with virtual threads
	 * @param threadPriority priority of the worker threads (1 to 9)
	 * @param threadNamePrefix prefix of the worker thread names; defaults to the
	 * scheduler name followed by "_Worker"
	 * @param daemon whether the worker threads are daemon threads
	 * @param threadsInheritContextClassLoaderOfInitializingThread whether the worker threads
	 * use the context class loader of the thread that initializes the pool
	 * @param threadsInheritGroupOfInitializingThread whether the worker threads join the
	 * thread group of the thread that initializes the pool
	 * @param virtualThreads whether to run jobs on virtual threads instead of a pool of
	 * platform threads
	 */
	public record ThreadPool(
			@DefaultValue("10") int threadCount,
			@DefaultValue("5") int threadPriority,
			String threadNamePrefix,
			boolean daemon,
			@DefaultValue("true") boolean threadsInheritContextClassLoaderOfInitializingThread,
			@DefaultValue("true") boolean threadsInheritGroupOfInitializingThread,
			boolean virtualThreads) {

		public ThreadPoolSettings toSettings() {
			return ThreadPoolSettings.builder()
				.threadCount(this.threadCount)
				.threadPriority(this.threadPriority)
				.threadNamePrefix(this.threadNamePrefix)
				.daemon(this.daemon)
				.threadsInheritContextClassLoaderOfInitializingThread(
						this.threadsInheritContextClassLoaderOfInitializingThread)
				.threadsInheritGroupOfInitializingThread(this.threadsInheritGroupOfInitializingThread)
				.build();
		}

	}

	/**
	 * Job store settings.
	 *
	 * @param type job store type; required
	 * @param misfireThreshold how late a trigger may fire before it counts as misfired
	 * @param clustered whether several scheduler instances share the store
	 * @param clusterCheckinInterval how often a clustered instance checks in with the
	 * others
	 * @param clusterCheckinMisfireThreshold grace period after a missed check-in before an
	 * instance is considered failed and its work is recovered
	 * @param maxMisfiresToHandleAtATime maximum number of misfired triggers handled per pass
	 * @param acquireTriggersWithinLock whether to acquire triggers while holding the store's
	 * trigger lock
	 * @param acquireTriggersWithinLockRetry pause between attempts to take a store lock held
	 * by another instance
	 * @param retryInterval delay before retrying after the store could not be reached
	 * @param useProperties whether job data map values are restricted to strings
	 * @param doubleCheckLockMisfireHandler whether to check for misfires before taking the
	 * lock to handle them
	 * @param makeThreadsDaemons whether the store's maintenance threads are daemon threads
	 * @param threadsInheritInitializersClassLoadContext whether the store's maintenance
	 * threads use the context class loader of the initializing thread
	 * @param mongodb MongoDB job store settings
	 * @param redis Redis job store settings
	 */
	public record JobStore(
			JobStoreType type,
			@DefaultValue("60s") Duration misfireThreshold,
			boolean clustered,
			@DefaultValue("7500ms") Duration clusterCheckinInterval,
			@DefaultValue("7500ms") Duration clusterCheckinMisfireThreshold,
			@DefaultValue("20") int maxMisfiresToHandleAtATime,
			boolean acquireTriggersWithinLock,
			@DefaultValue("50ms") Duration acquireTriggersWithinLockRetry,
			@DefaultValue("15s") Duration retryInterval,
			boolean useProperties,
			@DefaultValue("true") boolean doubleCheckLockMisfireHandler,
			boolean makeThreadsDaemons,
			boolean threadsInheritInitializersClassLoadContext,
			@DefaultValue Mongodb mongodb,
			@DefaultValue Redis redis) {

		public JobStoreSettings toSettings() {
			return JobStoreSettings.builder()
				.misfireThreshold(this.misfireThreshold)
				.clustered(this.clustered)
				.clusterCheckinInterval(this.clusterCheckinInterval)
				.clusterCheckinMisfireThreshold(this.clusterCheckinMisfireThreshold)
				.maxMisfiresToHandleAtATime(this.maxMisfiresToHandleAtATime)
				.acquireTriggersWithinLock(this.acquireTriggersWithinLock)
				.acquireTriggersWithinLockRetry(this.acquireTriggersWithinLockRetry)
				.retryInterval(this.retryInterval)
				.useProperties(this.useProperties)
				.doubleCheckLockMisfireHandler(this.doubleCheckLockMisfireHandler)
				.makeThreadsDaemons(this.makeThreadsDaemons)
				.threadsInheritInitializersClassLoadContext(this.threadsInheritInitializersClassLoadContext)
				.build();
		}

		/**
		 * MongoDB job store settings.
		 *
		 * @param database database the store uses; defaults to the application's MongoDB
		 * database
		 * @param collectionPrefix prefix of the collections the store uses
		 * @param lockTimeout how long a lock is held before it is considered abandoned
		 */
		public record Mongodb(String database, @DefaultValue("qrtz_") String collectionPrefix,
				@DefaultValue("10m") Duration lockTimeout) {
		}

		/**
		 * Redis job store settings.
		 *
		 * @param keyPrefix prefix of the keys the store uses
		 * @param lockTimeout how long a lock is held before it is considered abandoned
		 */
		public record Redis(@DefaultValue("quartz:") String keyPrefix, @DefaultValue("30s") Duration lockTimeout) {
		}

	}

	/**
	 * Built-in scheduler plugins.
	 *
	 * @param jobHistory logs job executions
	 * @param triggerHistory logs trigger firings
	 * @param jobInterruptMonitor interrupts jobs that run longer than allowed
	 */
	public record Plugins(@DefaultValue JobHistory jobHistory, @DefaultValue TriggerHistory triggerHistory,
			@DefaultValue JobInterruptMonitor jobInterruptMonitor) {

		/**
		 * Job history logging plugin. Messages use {@link java.text.MessageFormat} syntax;
		 * unset messages keep the plugin's defaults.
		 *
		 * @param enabled whether to log job executions
		 * @param jobToBeFiredMessage message logged before a job runs
		 * @param jobSuccessMessage message logged after a job completed
		 * @param jobFailedMessage message logged after a job failed
		 * @param jobWasVetoedMessage message logged when a job execution was vetoed
		 */
		public record JobHistory(boolean enabled, String jobToBeFiredMessage, String jobSuccessMessage,
				String jobFailedMessage, String jobWasVetoedMessage) {
		}

		/**
		 * Trigger history logging plugin. Messages use {@link java.text.MessageFormat}
		 * syntax; unset messages keep the plugin's defaults.
		 *
		 * @param enabled whether to log trigger firings
		 * @param triggerFiredMessage message logged when a trigger fires
		 * @param triggerMisfiredMessage message logged when a trigger misfires
		 * @param triggerCompleteMessage message logged when a triggered job completed
		 */
		public record TriggerHistory(boolean enabled, String triggerFiredMessage, String triggerMisfiredMessage,
				String triggerCompleteMessage) {
		}

		/**
		 * Job interrupt monitor plugin. A job can override the maximum run time with a
		 * "MaxRunTime" job data entry in milliseconds.
		 *
		 * @param enabled whether to interrupt jobs that run longer than allowed
		 * @param defaultMaxRunTime maximum run time of a job
		 */
		public record JobInterruptMonitor(boolean enabled, @DefaultValue("5m") Duration defaultMaxRunTime) {
		}

	}

	/**
	 * Transactions around job executions. Jobs annotated with
	 * {@code @ExecuteInTransaction} always run in a transaction.
	 *
	 * @param enabled whether every job runs in a transaction of the unique or primary
	 * transaction manager, not only annotated jobs
	 * @param defaultPropagation propagation of the transactions of jobs that are not
	 * annotated, when enabled
	 */
	public record Transaction(boolean enabled, @DefaultValue("REQUIRED") Propagation defaultPropagation) {
	}

}
