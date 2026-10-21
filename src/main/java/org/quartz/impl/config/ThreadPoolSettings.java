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
 * Settings of the worker thread pool (the former {@code org.quartz.threadPool.*} keys).
 * They apply to the {@link org.quartz.simpl.SimpleThreadPool} the scheduler factory creates
 * when no {@link org.quartz.spi.ThreadPool} instance is supplied.
 *
 * @param threadCount the number of worker threads; at least 1
 * @param threadPriority the worker thread priority, {@link Thread#MIN_PRIORITY} to {@link Thread#MAX_PRIORITY}
 * @param threadNamePrefix the worker thread name prefix; {@code null} means {@code <scheduler name>_Worker}
 * @param daemon run the worker threads as daemon threads
 * @param threadsInheritContextClassLoaderOfInitializingThread workers use the context class
 *        loader of the thread that initializes the pool
 * @param threadsInheritGroupOfInitializingThread workers join the thread group of the thread
 *        that initializes the pool
 */
public record ThreadPoolSettings(
        int threadCount,
        int threadPriority,
        @Nullable String threadNamePrefix,
        boolean daemon,
        boolean threadsInheritContextClassLoaderOfInitializingThread,
        boolean threadsInheritGroupOfInitializingThread) {

    public static final int DEFAULT_THREAD_COUNT = 10;

    public ThreadPoolSettings {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be at least 1, was " + threadCount);
        }
        if (threadPriority < Thread.MIN_PRIORITY || threadPriority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException("threadPriority must be between " + Thread.MIN_PRIORITY
                    + " and " + Thread.MAX_PRIORITY + ", was " + threadPriority);
        }
        if (threadNamePrefix != null && threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
    }

    public static ThreadPoolSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .threadCount(threadCount)
                .threadPriority(threadPriority)
                .threadNamePrefix(threadNamePrefix)
                .daemon(daemon)
                .threadsInheritContextClassLoaderOfInitializingThread(threadsInheritContextClassLoaderOfInitializingThread)
                .threadsInheritGroupOfInitializingThread(threadsInheritGroupOfInitializingThread);
    }

    public static final class Builder {
        private int threadCount = DEFAULT_THREAD_COUNT;
        private int threadPriority = Thread.NORM_PRIORITY;
        private @Nullable String threadNamePrefix;
        private boolean daemon;
        private boolean threadsInheritContextClassLoaderOfInitializingThread = true;
        private boolean threadsInheritGroupOfInitializingThread = true;

        private Builder() {
        }

        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        public Builder threadPriority(int threadPriority) {
            this.threadPriority = threadPriority;
            return this;
        }

        public Builder threadNamePrefix(@Nullable String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        public Builder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        public Builder threadsInheritContextClassLoaderOfInitializingThread(boolean inherit) {
            this.threadsInheritContextClassLoaderOfInitializingThread = inherit;
            return this;
        }

        public Builder threadsInheritGroupOfInitializingThread(boolean inherit) {
            this.threadsInheritGroupOfInitializingThread = inherit;
            return this;
        }

        public ThreadPoolSettings build() {
            return new ThreadPoolSettings(threadCount, threadPriority, threadNamePrefix, daemon,
                    threadsInheritContextClassLoaderOfInitializingThread,
                    threadsInheritGroupOfInitializingThread);
        }
    }
}
