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
package org.quartz.simpl;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.quartz.SchedulerConfigException;
import org.quartz.spi.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ThreadPool} that runs each job on a new virtual thread, with at most {@code
 * maxConcurrency} jobs running at the same time.
 *
 * <p>There is no pool of threads to keep: a virtual thread is cheap to create and is not reused.
 * The limit is what the pool reports to the scheduler, so the scheduler acquires no more triggers
 * than it can run now. Jobs that mostly wait (on a database, a mail server, an HTTP API) can
 * therefore run with a much higher limit than a pool of platform threads would allow; the limit
 * should rather follow what those systems accept (for example the size of the database connection
 * pool).
 *
 * <p>Thread priority, daemon status and thread groups do not apply to virtual threads. The threads
 * are named {@code <prefix>-<n>}, the prefix defaulting to {@code <scheduler name>_Worker}.
 *
 * @see SimpleThreadPool
 */
public class VirtualThreadPool implements ThreadPool {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private final int maxConcurrency;

  private @Nullable String threadNamePrefix;

  private boolean inheritLoader = true;

  private String schedulerInstanceName = "QuartzScheduler";

  private final ReentrantLock lock = new ReentrantLock();

  private final Condition jobEnded = lock.newCondition();

  private int running;

  private boolean shutdown;

  // created by initialize
  @SuppressWarnings("NullAway.Init")
  private ThreadFactory threadFactory;

  private @Nullable ClassLoader contextClassLoader;

  /**
   * Create a pool running at most {@code maxConcurrency} jobs at a time.
   *
   * @param maxConcurrency the maximum number of concurrently running jobs; at least 1
   */
  public VirtualThreadPool(int maxConcurrency) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException(
          "maxConcurrency must be at least 1, was " + maxConcurrency);
    }
    this.maxConcurrency = maxConcurrency;
  }

  /** The maximum number of concurrently running jobs. */
  public int getMaxConcurrency() {
    return maxConcurrency;
  }

  public @Nullable String getThreadNamePrefix() {
    return threadNamePrefix;
  }

  /** The prefix of the thread names; {@code null} for {@code <scheduler name>_Worker}. */
  public void setThreadNamePrefix(@Nullable String threadNamePrefix) {
    this.threadNamePrefix = threadNamePrefix;
  }

  public boolean isThreadsInheritContextClassLoaderOfInitializingThread() {
    return inheritLoader;
  }

  /**
   * Whether the job threads use the context class loader of the thread that initializes the pool
   * (default), rather than that of the scheduler thread that starts them.
   */
  public void setThreadsInheritContextClassLoaderOfInitializingThread(boolean inheritLoader) {
    this.inheritLoader = inheritLoader;
  }

  @Override
  public void setInstanceId(String schedInstId) {}

  @Override
  public void setInstanceName(String schedName) {
    this.schedulerInstanceName = schedName;
  }

  @Override
  public void initialize() throws SchedulerConfigException {
    String prefix =
        (threadNamePrefix != null) ? threadNamePrefix : schedulerInstanceName + "_Worker";
    threadFactory = Thread.ofVirtual().name(prefix + "-", 1).factory();
    if (inheritLoader) {
      contextClassLoader = Thread.currentThread().getContextClassLoader();
      log.info(
          "Job execution threads will use class loader of thread: {}",
          Thread.currentThread().getName());
    }
  }

  /** The maximum number of concurrently running jobs. */
  @Override
  public int getPoolSize() {
    return maxConcurrency;
  }

  @Override
  public String describe() {
    return "virtual threads, at most " + maxConcurrency + " concurrent jobs";
  }

  /**
   * Run the job on a new virtual thread. The scheduler only hands over a job after {@link
   * #blockForAvailableThreads()} reported room for it; should the limit be reached all the same,
   * this waits until a job ends, as {@link SimpleThreadPool} does, rather than refusing the job.
   * After {@link #shutdown(boolean)}, jobs still handed over run without waiting.
   */
  @Override
  public boolean runInThread(Runnable runnable) {
    lock.lock();
    try {
      while (running >= maxConcurrency && !shutdown) {
        jobEnded.awaitUninterruptibly();
      }
      running++;
    } finally {
      lock.unlock();
    }
    Thread thread =
        threadFactory.newThread(
            () -> {
              try {
                runnable.run();
              } finally {
                jobEnded();
              }
            });
    if (contextClassLoader != null) {
      thread.setContextClassLoader(contextClassLoader);
    }
    try {
      thread.start();
    } catch (RuntimeException | OutOfMemoryError ex) {
      // the job did not start: free its slot and let the scheduler treat it as not run
      jobEnded();
      log.error("Could not start a virtual thread for the job", ex);
      return false;
    }
    return true;
  }

  private void jobEnded() {
    lock.lock();
    try {
      running--;
      jobEnded.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Wait until fewer than {@code maxConcurrency} jobs run, and return how many more can start;
   * {@code 0} if the wait was interrupted (the scheduler thread is interrupted when it halts) or
   * the pool shut down while it was full.
   */
  @Override
  public int blockForAvailableThreads() {
    lock.lock();
    try {
      while (running >= maxConcurrency && !shutdown) {
        jobEnded.await();
      }
      return Math.max(maxConcurrency - running, 0);
    } catch (InterruptedException ex) {
      // let the halting scheduler thread check its state; the flag is not kept, or a stray
      // interrupt would make every later call return at once
      return 0;
    } finally {
      lock.unlock();
    }
  }

  /** The number of jobs running now. */
  public int getRunningCount() {
    lock.lock();
    try {
      return running;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stop waiting for capacity and, if {@code waitForJobsToComplete}, wait until the running jobs
   * have ended.
   */
  @Override
  public void shutdown(boolean waitForJobsToComplete) {
    lock.lock();
    try {
      shutdown = true;
      jobEnded.signalAll();
      if (!waitForJobsToComplete) {
        return;
      }
      while (running > 0) {
        log.debug("Waiting for {} job(s) to complete", running);
        jobEnded.await();
      }
      log.debug("No executing jobs remaining, all threads stopped.");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      lock.unlock();
    }
  }
}
