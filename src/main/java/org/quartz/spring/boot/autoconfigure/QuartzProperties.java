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
import org.jspecify.annotations.Nullable;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.transaction.annotation.Propagation;

/**
 * Configuration properties for the Quartz Scheduler integration. Every duration is a {@link
 * Duration}; the nested groups map onto the typed {@link SchedulerSettings}, {@link
 * ThreadPoolSettings} and {@link JobStoreSettings}. Settings of those types without a property here
 * keep their defaults, chosen for a Spring Boot application. Scheduler plugins are {@link
 * org.quartz.spi.SchedulerPlugin} beans.
 *
 * @param autoStartup Whether to automatically start the scheduler after initialization.
 * @param startupDelay Delay after which the scheduler is started once initialization completes.
 *     Setting this property makes sense if no jobs should be run before the entire application has
 *     started up.
 * @param waitForJobsToCompleteOnShutdown Whether to wait for running jobs to complete on shutdown.
 * @param overwriteExistingJobs Whether configured jobs should overwrite existing job definitions.
 * @param scheduler Scheduler settings.
 * @param threadPool Worker thread pool settings.
 * @param jobStore Job store settings.
 * @param transaction Transactions around job executions.
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
    @DefaultValue Transaction transaction) {

  /** Value of {@code instance-id} that generates the id when the store is clustered. */
  public static final String AUTO_INSTANCE_ID = "AUTO";

  /**
   * Value of {@code instance-id} that reads the id from the {@code org.quartz.scheduler.instanceId}
   * system property when the store is clustered.
   */
  public static final String SYSTEM_PROPERTY_INSTANCE_ID = "SYS_PROP";

  /**
   * Scheduler settings.
   *
   * @param name Name of the scheduler, shared by all nodes of a cluster.
   * @param instanceId Instance id of this node; "AUTO" generates one and "SYS_PROP" reads the
   *     "org.quartz.scheduler.instanceId" system property (both only when the job store is
   *     clustered).
   * @param threadName Name of the scheduler thread; defaults to the scheduler name followed by
   *     "_QuartzSchedulerThread".
   * @param idleWaitTime How long the scheduler thread waits for new triggers when idle; at least
   *     1s.
   * @param batchTriggerAcquisitionFireAheadTimeWindow How far ahead of their fire time triggers may
   *     be acquired.
   * @param batchTriggerAcquisitionMaxCount Maximum number of triggers acquired at once.
   * @param interruptJobsOnShutdown Whether to interrupt running interruptable jobs on shutdown
   *     without waiting.
   * @param interruptJobsOnShutdownWithWait Whether to interrupt running interruptable jobs on
   *     shutdown with waiting.
   * @param context Initial entries of the scheduler context.
   */
  public record Scheduler(
      @DefaultValue("quartzScheduler") String name,
      @DefaultValue(SchedulerSettings.DEFAULT_INSTANCE_ID) String instanceId,
      @Nullable String threadName,
      @DefaultValue("30s") Duration idleWaitTime,
      @DefaultValue("0s") Duration batchTriggerAcquisitionFireAheadTimeWindow,
      @DefaultValue("1") int batchTriggerAcquisitionMaxCount,
      boolean interruptJobsOnShutdown,
      boolean interruptJobsOnShutdownWithWait,
      @Nullable Map<String, String> context) {

    /** Whether the instance id is read from a system property ({@code SYS_PROP}). */
    public boolean isSystemPropertyInstanceId() {
      return SYSTEM_PROPERTY_INSTANCE_ID.equals(this.instanceId);
    }

    /**
     * The typed settings these properties describe; settings without a property keep their
     * defaults.
     *
     * @return the scheduler settings
     */
    public SchedulerSettings toSettings() {
      boolean auto = AUTO_INSTANCE_ID.equals(this.instanceId) || isSystemPropertyInstanceId();
      return SchedulerSettings.builder()
          .name(this.name)
          .instanceId(auto ? SchedulerSettings.DEFAULT_INSTANCE_ID : this.instanceId)
          .instanceIdAuto(auto)
          .threadName(this.threadName)
          .idleWaitTime(this.idleWaitTime)
          .batchTriggerAcquisitionFireAheadTimeWindow(
              this.batchTriggerAcquisitionFireAheadTimeWindow)
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
   * <p>Platform worker threads run at normal priority, are not daemon threads, join the thread
   * group of, and use the context class loader of, the thread that initializes the pool.
   *
   * @param threadCount Number of worker threads, or, with virtual threads, the maximum number of
   *     concurrently running jobs.
   * @param threadNamePrefix Prefix of the worker thread names; defaults to the scheduler name
   *     followed by "_Worker".
   * @param virtualThreads Whether to run each job on a new virtual thread, at most thread-count at
   *     a time, instead of a pool of platform threads; independent of
   *     "spring.threads.virtual.enabled".
   */
  public record ThreadPool(
      @DefaultValue("10") int threadCount,
      @Nullable String threadNamePrefix,
      boolean virtualThreads) {

    /**
     * The typed settings these properties describe; settings without a property keep their
     * defaults.
     *
     * @return the thread pool settings
     */
    public ThreadPoolSettings toSettings() {
      return ThreadPoolSettings.builder()
          .threadCount(this.threadCount)
          .threadNamePrefix(this.threadNamePrefix)
          .virtualThreads(this.virtualThreads)
          .build();
    }
  }

  /**
   * Job store settings.
   *
   * @param type Job store type; when unset, the store whose connection bean is present.
   * @param misfireThreshold How late a trigger may fire before it counts as misfired.
   * @param clustered Whether several scheduler instances share the store.
   * @param clusterCheckinInterval How often a clustered instance checks in with the others.
   * @param clusterCheckinMisfireThreshold Grace period after a missed check-in before an instance
   *     is considered failed and its work is recovered.
   * @param maxMisfiresToHandleAtATime Maximum number of misfired triggers handled per pass.
   * @param retryInterval Delay before retrying after the store could not be reached.
   * @param mongodb MongoDB job store settings.
   * @param redis Redis job store settings.
   */
  public record JobStore(
      @Nullable JobStoreType type,
      @DefaultValue("60s") Duration misfireThreshold,
      boolean clustered,
      @DefaultValue("7500ms") Duration clusterCheckinInterval,
      @DefaultValue("7500ms") Duration clusterCheckinMisfireThreshold,
      @DefaultValue("20") int maxMisfiresToHandleAtATime,
      @DefaultValue("15s") Duration retryInterval,
      @DefaultValue Mongodb mongodb,
      @DefaultValue Redis redis) {

    /**
     * The typed settings these properties describe; settings without a property keep their
     * defaults.
     *
     * @return the job store settings
     */
    public JobStoreSettings toSettings() {
      return JobStoreSettings.builder()
          .misfireThreshold(this.misfireThreshold)
          .clustered(this.clustered)
          .clusterCheckinInterval(this.clusterCheckinInterval)
          .clusterCheckinMisfireThreshold(this.clusterCheckinMisfireThreshold)
          .maxMisfiresToHandleAtATime(this.maxMisfiresToHandleAtATime)
          .retryInterval(this.retryInterval)
          .build();
    }

    /**
     * MongoDB job store settings. The store uses the application's MongoDB database
     * ("spring.mongodb.database", or the database of the connection string).
     *
     * @param collectionPrefix Prefix of the collections the store uses.
     * @param lockTimeout How long a lock is held before it is considered abandoned.
     */
    public record Mongodb(
        @DefaultValue("qrtz_") String collectionPrefix,
        @DefaultValue("10m") Duration lockTimeout) {}

    /**
     * Redis job store settings.
     *
     * @param keyPrefix Prefix of the keys the store uses.
     * @param lockTimeout How long a lock is held before it is considered abandoned.
     */
    public record Redis(
        @DefaultValue("quartz:") String keyPrefix, @DefaultValue("30s") Duration lockTimeout) {}
  }

  /**
   * Transactions around job executions. Jobs annotated with {@code @ExecuteInTransaction} always
   * run in a transaction.
   *
   * @param enabled Whether every job runs in a transaction of the unique or primary transaction
   *     manager, not only annotated jobs.
   * @param defaultPropagation Propagation of the transactions of jobs that are not annotated, when
   *     enabled.
   */
  public record Transaction(
      boolean enabled, @DefaultValue("REQUIRED") Propagation defaultPropagation) {}
}
