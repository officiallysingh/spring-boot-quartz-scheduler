/*
 * Copyright 2002-present the original author or authors.
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

package org.quartz.spring;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.spi.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;

/**
 * Quartz {@link ThreadPool} adapter that delegates to a Spring-managed {@link Executor} instance,
 * specified on {@link SchedulerFactoryBean}.
 *
 * <p>The adapter counts the jobs it has handed to the executor that have not finished yet and never
 * hands over more than the executor's capacity, so the scheduler only acquires triggers it can run
 * right away: none wait in the executor's queue (where, in a cluster, no other node could run them)
 * or are rejected. The capacity is given explicitly or derived from the executor in {@link
 * #initialize()}:
 *
 * <ul>
 *   <li>{@link ThreadPoolExecutor} and {@link ThreadPoolTaskExecutor}: the maximum pool size if the
 *       queue holds no tasks (a {@code SynchronousQueue}), otherwise the core pool size, since the
 *       pool only grows beyond it once the queue is full;
 *   <li>{@link SimpleAsyncTaskExecutor} (for example on virtual threads): its concurrency limit, if
 *       it has one.
 * </ul>
 *
 * An executor that sets no limit (an unlimited {@code SimpleAsyncTaskExecutor}, a cached thread
 * pool, a virtual-thread-per-task executor, or any other executor) is limited to the {@linkplain
 * #setDefaultCapacity default capacity}: {@code spring.quartz.thread-pool.thread-count} when the
 * adapter comes from {@link SchedulerFactoryBean#setTaskExecutor}, otherwise {@value
 * ThreadPoolSettings#DEFAULT_THREAD_COUNT}. Unbounded concurrency would start every due job at
 * once, overwhelming the systems the jobs use, and in a cluster let one node take all due triggers.
 * An explicit capacity is used as given.
 *
 * <p>{@link #shutdown(boolean) shutdown(true)} waits for the jobs it has handed over to complete.
 * The executor itself is not shut down: it belongs to the application.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see SchedulerFactoryBean#setTaskExecutor
 */
public class LocalTaskExecutorThreadPool implements ThreadPool {

  /** Logger available to subclasses. */
  protected final Logger logger = LoggerFactory.getLogger(getClass());

  private static final int UNKNOWN = -1;

  private final Executor taskExecutor;

  private final ReentrantLock lock = new ReentrantLock();

  private final Condition jobEnded = this.lock.newCondition();

  private int capacity;

  private int defaultCapacity = ThreadPoolSettings.DEFAULT_THREAD_COUNT;

  private boolean capacityIsDefault;

  private int running;

  private boolean shutdown;

  /**
   * Create a thread pool that runs jobs on the given {@link Executor}, with the capacity derived
   * from the executor.
   *
   * @param taskExecutor the Spring-managed executor, for example a {@code ThreadPoolTaskExecutor}
   *     or a virtual-thread {@code SimpleAsyncTaskExecutor}
   */
  public LocalTaskExecutorThreadPool(Executor taskExecutor) {
    this(taskExecutor, UNKNOWN);
  }

  /**
   * Create a thread pool that runs at most {@code capacity} jobs at a time on the given {@link
   * Executor}.
   *
   * @param taskExecutor the Spring-managed executor
   * @param capacity the number of jobs the executor can run at the same time; {@code -1} to derive
   *     it from the executor
   */
  public LocalTaskExecutorThreadPool(Executor taskExecutor, int capacity) {
    Assert.notNull(taskExecutor, "TaskExecutor must not be null");
    Assert.isTrue(
        capacity > 0 || capacity == UNKNOWN, "Capacity must be positive, or -1 to derive it");
    this.taskExecutor = taskExecutor;
    this.capacity = capacity;
  }

  @Override
  public void setInstanceId(String schedInstId) {}

  @Override
  public void setInstanceName(String schedName) {}

  /**
   * The capacity used when the capacity is derived and the executor sets no limit; default {@value
   * ThreadPoolSettings#DEFAULT_THREAD_COUNT}.
   *
   * @param defaultCapacity the number of jobs to run at the same time; at least 1
   */
  public void setDefaultCapacity(int defaultCapacity) {
    Assert.isTrue(defaultCapacity > 0, "Default capacity must be positive");
    this.defaultCapacity = defaultCapacity;
  }

  @Override
  public void initialize() {
    if (this.capacity == UNKNOWN) {
      int derived = capacityOf(this.taskExecutor);
      if (derived == Integer.MAX_VALUE) {
        this.capacity = this.defaultCapacity;
        this.capacityIsDefault = true;
        logger.info(
            "{} sets no limit on concurrent tasks: running at most {} jobs at a time",
            this.taskExecutor.getClass().getName(),
            this.capacity);
      } else if (derived == UNKNOWN) {
        this.capacity = this.defaultCapacity;
        this.capacityIsDefault = true;
        // a bounded executor behind a wrapper (Executors.newSingleThreadExecutor, a decorator)
        // looks unlimited: jobs beyond its real limit would wait in its queue, already fired
        logger.warn(
            "The concurrency limit of {} cannot be read: running at most {} jobs at a time. If"
                + " the executor runs fewer at once, pass its capacity explicitly"
                + " (new LocalTaskExecutorThreadPool(executor, capacity)).",
            this.taskExecutor.getClass().getName(),
            this.capacity);
      } else {
        this.capacity = derived;
      }
    }
  }

  private static int capacityOf(Executor executor) {
    if (executor instanceof ThreadPoolTaskExecutor threadPoolTaskExecutor) {
      return capacityOf(threadPoolTaskExecutor.getThreadPoolExecutor());
    }
    if (executor instanceof ThreadPoolTaskScheduler threadPoolTaskScheduler) {
      return capacityOf(threadPoolTaskScheduler.getScheduledThreadPoolExecutor());
    }
    if (executor instanceof ConcurrentTaskExecutor concurrentTaskExecutor) {
      return capacityOf(concurrentTaskExecutor.getConcurrentExecutor());
    }
    if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
      int size =
          (threadPoolExecutor.getQueue().remainingCapacity() == 0)
              ? threadPoolExecutor.getMaximumPoolSize()
              : threadPoolExecutor.getCorePoolSize();
      return (size > 0) ? size : UNKNOWN;
    }
    if (executor instanceof SimpleAsyncTaskExecutor simpleAsyncTaskExecutor
        && simpleAsyncTaskExecutor.getConcurrencyLimit() > 0) {
      return simpleAsyncTaskExecutor.getConcurrencyLimit();
    }
    return UNKNOWN;
  }

  /**
   * Stop handing over jobs and, if {@code waitForJobsToComplete}, wait until the jobs already
   * handed over have ended.
   */
  @Override
  public void shutdown(boolean waitForJobsToComplete) {
    this.lock.lock();
    try {
      this.shutdown = true;
      this.jobEnded.signalAll();
      if (!waitForJobsToComplete) {
        return;
      }
      while (this.running > 0) {
        logger.debug("Waiting for {} job(s) to complete", this.running);
        this.jobEnded.await();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      this.lock.unlock();
    }
  }

  /** The maximum number of concurrently running jobs; {@code -1} before {@link #initialize()}. */
  @Override
  public int getPoolSize() {
    return this.capacity;
  }

  @Override
  public String describe() {
    return "on "
        + this.taskExecutor.getClass().getName()
        + ", at most "
        + this.capacity
        + " concurrent jobs"
        + (this.capacityIsDefault ? " (the executor sets no limit)" : "");
  }

  @Override
  public boolean runInThread(Runnable runnable) {
    this.lock.lock();
    try {
      this.running++;
    } finally {
      this.lock.unlock();
    }
    try {
      this.taskExecutor.execute(
          () -> {
            try {
              runnable.run();
            } finally {
              jobEnded();
            }
          });
      return true;
    } catch (RuntimeException ex) {
      // whatever execute throws, the job did not start: free its slot
      jobEnded();
      if (ex instanceof RejectedExecutionException) {
        logger.error("Task has been rejected by TaskExecutor", ex);
      } else {
        logger.error("TaskExecutor failed to accept the task", ex);
      }
      return false;
    }
  }

  private void jobEnded() {
    this.lock.lock();
    try {
      this.running--;
      this.jobEnded.signalAll();
    } finally {
      this.lock.unlock();
    }
  }

  /**
   * Wait until the executor has room for a job, and return how many it has room for ({@code 0} if
   * the wait was interrupted or the pool shut down while it was full).
   */
  @Override
  public int blockForAvailableThreads() {
    this.lock.lock();
    try {
      while (this.running >= this.capacity && !this.shutdown) {
        this.jobEnded.await();
      }
      return Math.max(this.capacity - this.running, 0);
    } catch (InterruptedException ex) {
      // the scheduler thread is interrupted when it halts: let it check, but do not keep
      // the flag, or a stray interrupt would make every later call return at once
      return 0;
    } finally {
      this.lock.unlock();
    }
  }
}
