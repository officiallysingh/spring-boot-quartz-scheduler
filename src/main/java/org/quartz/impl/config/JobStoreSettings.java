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
import java.util.Objects;

/**
 * Store-independent job store settings (the former {@code org.quartz.jobStore.*} keys, minus the
 * JDBC-only ones). Persistent stores take these in their constructors; the scheduler factory
 * applies them to the store it creates itself when no {@link org.quartz.spi.JobStore} instance is
 * supplied.
 *
 * @param misfireThreshold how late a trigger may fire before it counts as misfired
 * @param clustered whether several scheduler instances share the store
 * @param clusterCheckinInterval how often a clustered instance checks in with the others
 * @param clusterCheckinMisfireThreshold grace period after a missed check-in before an instance is
 *     considered failed and its work is recovered
 * @param maxMisfiresToHandleAtATime the maximum number of misfired triggers handled per pass
 * @param acquireTriggersWithinLock acquire triggers while holding the store's trigger lock
 * @param acquireTriggersWithinLockRetry pause between attempts to take a store lock held by another
 *     instance
 * @param retryInterval delay before retrying after the store could not be reached
 * @param useProperties restrict {@link org.quartz.JobDataMap} values to strings
 * @param doubleCheckLockMisfireHandler check for misfires before taking the lock to handle them
 * @param makeThreadsDaemons run the store's maintenance threads as daemon threads
 * @param threadsInheritInitializersClassLoadContext the store's maintenance threads use the context
 *     class loader of the initializing thread
 */
public record JobStoreSettings(
    Duration misfireThreshold,
    boolean clustered,
    Duration clusterCheckinInterval,
    Duration clusterCheckinMisfireThreshold,
    int maxMisfiresToHandleAtATime,
    boolean acquireTriggersWithinLock,
    Duration acquireTriggersWithinLockRetry,
    Duration retryInterval,
    boolean useProperties,
    boolean doubleCheckLockMisfireHandler,
    boolean makeThreadsDaemons,
    boolean threadsInheritInitializersClassLoadContext) {

  public static final Duration DEFAULT_MISFIRE_THRESHOLD = Duration.ofSeconds(60);

  /**
   * Validates the settings.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public JobStoreSettings {
    requirePositive(misfireThreshold, "misfireThreshold");
    requirePositive(clusterCheckinInterval, "clusterCheckinInterval");
    requireNotNegative(clusterCheckinMisfireThreshold, "clusterCheckinMisfireThreshold");
    if (maxMisfiresToHandleAtATime < 1) {
      throw new IllegalArgumentException(
          "maxMisfiresToHandleAtATime must be at least 1, was " + maxMisfiresToHandleAtATime);
    }
    requireNotNegative(acquireTriggersWithinLockRetry, "acquireTriggersWithinLockRetry");
    requireNotNegative(retryInterval, "retryInterval");
  }

  /**
   * The default settings.
   *
   * @return the defaults
   */
  public static JobStoreSettings defaults() {
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
        .misfireThreshold(misfireThreshold)
        .clustered(clustered)
        .clusterCheckinInterval(clusterCheckinInterval)
        .clusterCheckinMisfireThreshold(clusterCheckinMisfireThreshold)
        .maxMisfiresToHandleAtATime(maxMisfiresToHandleAtATime)
        .acquireTriggersWithinLock(acquireTriggersWithinLock)
        .acquireTriggersWithinLockRetry(acquireTriggersWithinLockRetry)
        .retryInterval(retryInterval)
        .useProperties(useProperties)
        .doubleCheckLockMisfireHandler(doubleCheckLockMisfireHandler)
        .makeThreadsDaemons(makeThreadsDaemons)
        .threadsInheritInitializersClassLoadContext(threadsInheritInitializersClassLoadContext);
  }

  private static void requirePositive(Duration value, String what) {
    Objects.requireNonNull(value, what);
    if (value.toMillis() < 1) {
      throw new IllegalArgumentException(what + " must be at least 1ms, was " + value);
    }
  }

  private static void requireNotNegative(Duration value, String what) {
    Objects.requireNonNull(value, what);
    if (value.isNegative()) {
      throw new IllegalArgumentException(what + " must not be negative, was " + value);
    }
  }

  /** Builds {@link JobStoreSettings}; every value not set keeps its default. */
  public static final class Builder {
    private Duration misfireThreshold = DEFAULT_MISFIRE_THRESHOLD;
    private boolean clustered;
    private Duration clusterCheckinInterval = Duration.ofMillis(7500);
    private Duration clusterCheckinMisfireThreshold = Duration.ofMillis(7500);
    private int maxMisfiresToHandleAtATime = 20;
    private boolean acquireTriggersWithinLock;
    private Duration acquireTriggersWithinLockRetry = Duration.ofMillis(50);
    private Duration retryInterval = Duration.ofSeconds(15);
    private boolean useProperties;
    private boolean doubleCheckLockMisfireHandler = true;
    private boolean makeThreadsDaemons;
    private boolean threadsInheritInitializersClassLoadContext;

    private Builder() {}

    /**
     * Set {@link JobStoreSettings#misfireThreshold() misfireThreshold}.
     *
     * @param misfireThreshold the value
     * @return this builder
     */
    public Builder misfireThreshold(Duration misfireThreshold) {
      this.misfireThreshold = misfireThreshold;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#clustered() clustered}.
     *
     * @param clustered the value
     * @return this builder
     */
    public Builder clustered(boolean clustered) {
      this.clustered = clustered;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#clusterCheckinInterval() clusterCheckinInterval}.
     *
     * @param clusterCheckinInterval the value
     * @return this builder
     */
    public Builder clusterCheckinInterval(Duration clusterCheckinInterval) {
      this.clusterCheckinInterval = clusterCheckinInterval;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#clusterCheckinMisfireThreshold() clusterCheckinMisfireThreshold}.
     *
     * @param clusterCheckinMisfireThreshold the value
     * @return this builder
     */
    public Builder clusterCheckinMisfireThreshold(Duration clusterCheckinMisfireThreshold) {
      this.clusterCheckinMisfireThreshold = clusterCheckinMisfireThreshold;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#maxMisfiresToHandleAtATime() maxMisfiresToHandleAtATime}.
     *
     * @param maxMisfiresToHandleAtATime the value
     * @return this builder
     */
    public Builder maxMisfiresToHandleAtATime(int maxMisfiresToHandleAtATime) {
      this.maxMisfiresToHandleAtATime = maxMisfiresToHandleAtATime;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#acquireTriggersWithinLock() acquireTriggersWithinLock}.
     *
     * @param acquireTriggersWithinLock the value
     * @return this builder
     */
    public Builder acquireTriggersWithinLock(boolean acquireTriggersWithinLock) {
      this.acquireTriggersWithinLock = acquireTriggersWithinLock;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#acquireTriggersWithinLockRetry() acquireTriggersWithinLockRetry}.
     *
     * @param acquireTriggersWithinLockRetry the value
     * @return this builder
     */
    public Builder acquireTriggersWithinLockRetry(Duration acquireTriggersWithinLockRetry) {
      this.acquireTriggersWithinLockRetry = acquireTriggersWithinLockRetry;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#retryInterval() retryInterval}.
     *
     * @param retryInterval the value
     * @return this builder
     */
    public Builder retryInterval(Duration retryInterval) {
      this.retryInterval = retryInterval;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#useProperties() useProperties}.
     *
     * @param useProperties the value
     * @return this builder
     */
    public Builder useProperties(boolean useProperties) {
      this.useProperties = useProperties;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#doubleCheckLockMisfireHandler() doubleCheckLockMisfireHandler}.
     *
     * @param doubleCheckLockMisfireHandler the value
     * @return this builder
     */
    public Builder doubleCheckLockMisfireHandler(boolean doubleCheckLockMisfireHandler) {
      this.doubleCheckLockMisfireHandler = doubleCheckLockMisfireHandler;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#makeThreadsDaemons() makeThreadsDaemons}.
     *
     * @param makeThreadsDaemons the value
     * @return this builder
     */
    public Builder makeThreadsDaemons(boolean makeThreadsDaemons) {
      this.makeThreadsDaemons = makeThreadsDaemons;
      return this;
    }

    /**
     * Set {@link JobStoreSettings#threadsInheritInitializersClassLoadContext()
     * threadsInheritInitializersClassLoadContext}.
     *
     * @param inherit the value
     * @return this builder
     */
    public Builder threadsInheritInitializersClassLoadContext(boolean inherit) {
      this.threadsInheritInitializersClassLoadContext = inherit;
      return this;
    }

    /**
     * The settings.
     *
     * @return the settings
     * @throws IllegalArgumentException if a value is out of range
     */
    public JobStoreSettings build() {
      return new JobStoreSettings(
          misfireThreshold,
          clustered,
          clusterCheckinInterval,
          clusterCheckinMisfireThreshold,
          maxMisfiresToHandleAtATime,
          acquireTriggersWithinLock,
          acquireTriggersWithinLockRetry,
          retryInterval,
          useProperties,
          doubleCheckLockMisfireHandler,
          makeThreadsDaemons,
          threadsInheritInitializersClassLoadContext);
    }
  }
}
