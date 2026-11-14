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

import org.jspecify.annotations.Nullable;

/**
 * Settings of the worker thread pool. They apply to the pool the scheduler factory creates when no
 * {@link org.quartz.spi.ThreadPool} instance is supplied: a {@link
 * org.quartz.simpl.SimpleThreadPool} of platform threads, or, with {@code virtualThreads}, a {@link
 * org.quartz.simpl.VirtualThreadPool}.
 *
 * @param threadCount the number of worker threads, or, with virtual threads, the maximum number of
 *     concurrently running jobs; at least 1
 * @param threadNamePrefix the worker thread name prefix; {@code null} means {@code <scheduler
 *     name>_Worker}
 * @param virtualThreads run each job on a new virtual thread instead of a pool of platform threads
 */
public record ThreadPoolSettings(
    int threadCount, @Nullable String threadNamePrefix, boolean virtualThreads) {

  public static final int DEFAULT_THREAD_COUNT = 10;

  /**
   * Validates the settings.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public ThreadPoolSettings {
    if (threadCount < 1) {
      throw new IllegalArgumentException("threadCount must be at least 1, was " + threadCount);
    }
    if (threadNamePrefix != null && threadNamePrefix.isBlank()) {
      throw new IllegalArgumentException("threadNamePrefix must not be blank");
    }
  }

  /**
   * The default settings.
   *
   * @return the defaults
   */
  public static ThreadPoolSettings defaults() {
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
        .threadCount(threadCount)
        .threadNamePrefix(threadNamePrefix)
        .virtualThreads(virtualThreads);
  }

  /** Builds {@link ThreadPoolSettings}; every value not set keeps its default. */
  public static final class Builder {
    private int threadCount = DEFAULT_THREAD_COUNT;
    private @Nullable String threadNamePrefix;
    private boolean virtualThreads;

    private Builder() {}

    /**
     * Set {@link ThreadPoolSettings#threadCount() threadCount}.
     *
     * @param threadCount the value
     * @return this builder
     */
    public Builder threadCount(int threadCount) {
      this.threadCount = threadCount;
      return this;
    }

    /**
     * Set {@link ThreadPoolSettings#threadNamePrefix() threadNamePrefix}.
     *
     * @param threadNamePrefix the value
     * @return this builder
     */
    public Builder threadNamePrefix(@Nullable String threadNamePrefix) {
      this.threadNamePrefix = threadNamePrefix;
      return this;
    }

    /**
     * Set {@link ThreadPoolSettings#virtualThreads() virtualThreads}.
     *
     * @param virtualThreads the value
     * @return this builder
     */
    public Builder virtualThreads(boolean virtualThreads) {
      this.virtualThreads = virtualThreads;
      return this;
    }

    /**
     * The settings.
     *
     * @return the settings
     * @throws IllegalArgumentException if a value is out of range
     */
    public ThreadPoolSettings build() {
      return new ThreadPoolSettings(threadCount, threadNamePrefix, virtualThreads);
    }
  }
}
