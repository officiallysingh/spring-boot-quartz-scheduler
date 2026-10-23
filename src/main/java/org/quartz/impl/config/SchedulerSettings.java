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
package org.quartz.impl.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Scheduler-level settings (the former {@code org.quartz.scheduler.*} and {@code
 * org.quartz.context.key.*} keys).
 *
 * @param name the scheduler name, shared by all nodes of a cluster
 * @param instanceId the instance id used when {@code instanceIdAuto} is {@code false}
 * @param instanceIdAuto generate the instance id with the factory's {@link
 *     org.quartz.spi.InstanceIdGenerator} when the job store is clustered (the former {@code AUTO}
 *     / {@code SYS_PROP} values)
 * @param threadName the scheduler thread name; {@code null} means {@code
 *     <name>_QuartzSchedulerThread}
 * @param makeSchedulerThreadDaemon run the scheduler thread as a daemon thread
 * @param threadsInheritContextClassLoaderOfInitializer the scheduler thread (and the
 *     factory-created thread pool) use the context class loader of the creating thread
 * @param idleWaitTime how long the scheduler thread waits for new triggers when idle; at least one
 *     second
 * @param batchTriggerAcquisitionFireAheadTimeWindow how far ahead of their fire time triggers may
 *     be acquired
 * @param batchTriggerAcquisitionMaxCount the maximum number of triggers acquired at once
 * @param interruptJobsOnShutdown interrupt running interruptable jobs on {@code shutdown(false)}
 * @param interruptJobsOnShutdownWithWait interrupt running interruptable jobs on {@code
 *     shutdown(true)}
 * @param context initial entries of the {@link org.quartz.SchedulerContext}
 */
public record SchedulerSettings(
    String name,
    String instanceId,
    boolean instanceIdAuto,
    @Nullable String threadName,
    boolean makeSchedulerThreadDaemon,
    boolean threadsInheritContextClassLoaderOfInitializer,
    Duration idleWaitTime,
    Duration batchTriggerAcquisitionFireAheadTimeWindow,
    int batchTriggerAcquisitionMaxCount,
    boolean interruptJobsOnShutdown,
    boolean interruptJobsOnShutdownWithWait,
    Map<String, String> context) {

  public static final String DEFAULT_NAME = "DefaultQuartzScheduler";

  public static final String DEFAULT_INSTANCE_ID = "NON_CLUSTERED";

  public static final Duration DEFAULT_IDLE_WAIT_TIME = Duration.ofSeconds(30);

  public static final Duration MIN_IDLE_WAIT_TIME = Duration.ofSeconds(1);

  /**
   * Validates the settings.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public SchedulerSettings {
    requireText(name, "name");
    requireText(instanceId, "instanceId");
    if (threadName != null && threadName.isBlank()) {
      throw new IllegalArgumentException("threadName must not be blank");
    }
    Objects.requireNonNull(idleWaitTime, "idleWaitTime");
    if (idleWaitTime.compareTo(MIN_IDLE_WAIT_TIME) < 0) {
      throw new IllegalArgumentException("idleWaitTime must be at least 1s, was " + idleWaitTime);
    }
    Objects.requireNonNull(
        batchTriggerAcquisitionFireAheadTimeWindow, "batchTriggerAcquisitionFireAheadTimeWindow");
    if (batchTriggerAcquisitionFireAheadTimeWindow.isNegative()) {
      throw new IllegalArgumentException(
          "batchTriggerAcquisitionFireAheadTimeWindow must not be negative");
    }
    if (batchTriggerAcquisitionMaxCount < 1) {
      throw new IllegalArgumentException(
          "batchTriggerAcquisitionMaxCount must be at least 1, was "
              + batchTriggerAcquisitionMaxCount);
    }
    context =
        context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
  }

  /** The effective scheduler thread name. */
  public String effectiveThreadName() {
    return threadName != null ? threadName : name + "_QuartzSchedulerThread";
  }

  /**
   * The default settings.
   *
   * @return the defaults
   */
  public static SchedulerSettings defaults() {
    return builder().build();
  }

  /**
   * A builder starting from the defaults.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder starting from these settings.
   *
   * @return a new builder
   */
  public Builder toBuilder() {
    return new Builder()
        .name(name)
        .instanceId(instanceId)
        .instanceIdAuto(instanceIdAuto)
        .threadName(threadName)
        .makeSchedulerThreadDaemon(makeSchedulerThreadDaemon)
        .threadsInheritContextClassLoaderOfInitializer(
            threadsInheritContextClassLoaderOfInitializer)
        .idleWaitTime(idleWaitTime)
        .batchTriggerAcquisitionFireAheadTimeWindow(batchTriggerAcquisitionFireAheadTimeWindow)
        .batchTriggerAcquisitionMaxCount(batchTriggerAcquisitionMaxCount)
        .interruptJobsOnShutdown(interruptJobsOnShutdown)
        .interruptJobsOnShutdownWithWait(interruptJobsOnShutdownWithWait)
        .context(context);
  }

  static void requireText(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be empty");
    }
  }

  /** Builds {@link SchedulerSettings}; every value not set keeps its default. */
  public static final class Builder {
    private String name = DEFAULT_NAME;
    private String instanceId = DEFAULT_INSTANCE_ID;
    private boolean instanceIdAuto;
    private @Nullable String threadName;
    private boolean makeSchedulerThreadDaemon;
    private boolean threadsInheritContextClassLoaderOfInitializer;
    private Duration idleWaitTime = DEFAULT_IDLE_WAIT_TIME;
    private Duration batchTriggerAcquisitionFireAheadTimeWindow = Duration.ZERO;
    private int batchTriggerAcquisitionMaxCount = 1;
    private boolean interruptJobsOnShutdown;
    private boolean interruptJobsOnShutdownWithWait;
    private final Map<String, String> context = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Set {@link SchedulerSettings#name() name}.
     *
     * @param name the value
     * @return this builder
     */
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#instanceId() instanceId}.
     *
     * @param instanceId the value
     * @return this builder
     */
    public Builder instanceId(String instanceId) {
      this.instanceId = instanceId;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#instanceIdAuto() instanceIdAuto}.
     *
     * @param instanceIdAuto the value
     * @return this builder
     */
    public Builder instanceIdAuto(boolean instanceIdAuto) {
      this.instanceIdAuto = instanceIdAuto;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#threadName() threadName}.
     *
     * @param threadName the value
     * @return this builder
     */
    public Builder threadName(@Nullable String threadName) {
      this.threadName = threadName;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#makeSchedulerThreadDaemon() makeSchedulerThreadDaemon}.
     *
     * @param makeSchedulerThreadDaemon the value
     * @return this builder
     */
    public Builder makeSchedulerThreadDaemon(boolean makeSchedulerThreadDaemon) {
      this.makeSchedulerThreadDaemon = makeSchedulerThreadDaemon;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#threadsInheritContextClassLoaderOfInitializer()
     * threadsInheritContextClassLoaderOfInitializer}.
     *
     * @param inherit the value
     * @return this builder
     */
    public Builder threadsInheritContextClassLoaderOfInitializer(boolean inherit) {
      this.threadsInheritContextClassLoaderOfInitializer = inherit;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#idleWaitTime() idleWaitTime}.
     *
     * @param idleWaitTime the value
     * @return this builder
     */
    public Builder idleWaitTime(Duration idleWaitTime) {
      this.idleWaitTime = idleWaitTime;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#batchTriggerAcquisitionFireAheadTimeWindow()
     * batchTriggerAcquisitionFireAheadTimeWindow}.
     *
     * @param window the value
     * @return this builder
     */
    public Builder batchTriggerAcquisitionFireAheadTimeWindow(Duration window) {
      this.batchTriggerAcquisitionFireAheadTimeWindow = window;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#batchTriggerAcquisitionMaxCount()
     * batchTriggerAcquisitionMaxCount}.
     *
     * @param maxCount the value
     * @return this builder
     */
    public Builder batchTriggerAcquisitionMaxCount(int maxCount) {
      this.batchTriggerAcquisitionMaxCount = maxCount;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#interruptJobsOnShutdown() interruptJobsOnShutdown}.
     *
     * @param interruptJobsOnShutdown the value
     * @return this builder
     */
    public Builder interruptJobsOnShutdown(boolean interruptJobsOnShutdown) {
      this.interruptJobsOnShutdown = interruptJobsOnShutdown;
      return this;
    }

    /**
     * Set {@link SchedulerSettings#interruptJobsOnShutdownWithWait()
     * interruptJobsOnShutdownWithWait}.
     *
     * @param interruptJobsOnShutdownWithWait the value
     * @return this builder
     */
    public Builder interruptJobsOnShutdownWithWait(boolean interruptJobsOnShutdownWithWait) {
      this.interruptJobsOnShutdownWithWait = interruptJobsOnShutdownWithWait;
      return this;
    }

    /** Replaces the scheduler context entries. */
    public Builder context(@Nullable Map<String, String> context) {
      this.context.clear();
      if (context != null) {
        this.context.putAll(context);
      }
      return this;
    }

    /**
     * Add an entry to the initial scheduler context.
     *
     * @param key the entry key
     * @param value the entry value
     * @return this builder
     */
    public Builder contextEntry(String key, String value) {
      this.context.put(key, value);
      return this;
    }

    /**
     * The settings.
     *
     * @return the settings
     * @throws IllegalArgumentException if a value is out of range
     */
    public SchedulerSettings build() {
      return new SchedulerSettings(
          name,
          instanceId,
          instanceIdAuto,
          threadName,
          makeSchedulerThreadDaemon,
          threadsInheritContextClassLoaderOfInitializer,
          idleWaitTime,
          batchTriggerAcquisitionFireAheadTimeWindow,
          batchTriggerAcquisitionMaxCount,
          interruptJobsOnShutdown,
          interruptJobsOnShutdownWithWait,
          context);
    }
  }
}
