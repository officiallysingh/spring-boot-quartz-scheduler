/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
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
 *
 */

package org.quartz.simpl;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.quartz.SchedulerConfigException;
import org.quartz.spi.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is class is a simple implementation of a thread pool, based on the <code>
 * {@link org.quartz.spi.ThreadPool}</code> interface.
 *
 * <p><CODE>Runnable</CODE> objects are sent to the pool with the <code>
 * {@link #runInThread(Runnable)}</code> method, which blocks until a <code>Thread</code> becomes
 * available.
 *
 * <p>The pool has a fixed number of <code>Thread</code>s, and does not grow or shrink based on
 * demand.
 *
 * @author James House
 * @author Juergen Donnerstag
 */
public class SimpleThreadPool implements ThreadPool {

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private final int count;

  private boolean isShutdown = false;
  private boolean handoffPending = false;

  private final Object nextRunnableLock = new Object();

  @SuppressWarnings("NullAway.Init")
  private List<WorkerThread> workers;

  private final LinkedList<WorkerThread> availWorkers = new LinkedList<>();
  private final LinkedList<WorkerThread> busyWorkers = new LinkedList<>();

  private final String threadNamePrefix;

  private final Logger log = LoggerFactory.getLogger(getClass());

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a pool of {@code threadCount} non-daemon platform threads of normal priority.
   *
   * @param threadCount the number of worker threads in the pool, must be &gt; 0.
   * @param threadNamePrefix the prefix of the worker thread names, which are {@code <prefix>-<n>}
   */
  public SimpleThreadPool(int threadCount, String threadNamePrefix) {
    this.count = threadCount;
    this.threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  public Logger getLog() {
    return log;
  }

  public int getPoolSize() {
    return getThreadCount();
  }

  /** Get the number of worker threads in the pool. */
  public int getThreadCount() {
    return count;
  }

  /**
   * The prefix of the worker thread names.
   *
   * @return the prefix
   */
  public String getThreadNamePrefix() {
    return threadNamePrefix;
  }

  public void initialize() throws SchedulerConfigException {

    if (workers != null && !workers.isEmpty()) // already initialized...
    return;

    if (count <= 0) {
      throw new SchedulerConfigException("Thread count must be > 0");
    }

    // create the worker threads and start them
    for (WorkerThread wt : createWorkerThreads(count)) {
      wt.start();
      availWorkers.add(wt);
    }
  }

  protected List<WorkerThread> createWorkerThreads(int createCount) {
    workers = new LinkedList<>();
    for (int i = 1; i <= createCount; ++i) {
      WorkerThread wt = new WorkerThread(threadNamePrefix + "-" + i);
      workers.add(wt);
    }

    return workers;
  }

  /**
   * Terminate the worker threads of this pool.
   *
   * <p>Jobs currently in progress will complete.
   */
  public void shutdown() {
    shutdown(true);
  }

  /**
   * Terminate the worker threads of this pool.
   *
   * <p>Jobs currently in progress will complete.
   */
  public void shutdown(boolean waitForJobsToComplete) {

    synchronized (nextRunnableLock) {
      getLog().debug("Shutting down threadpool...");

      isShutdown = true;

      if (workers == null) // case where the pool wasn't even initialize()ed
      return;

      // signal each worker thread to shut down
      for (WorkerThread wt : workers) {
        wt.shutdown();
        availWorkers.remove(wt);
      }

      // Give waiting (wait(1000)) worker threads a chance to shut down.
      // Active worker threads will shut down after finishing their
      // current job.
      nextRunnableLock.notifyAll();

      if (waitForJobsToComplete) {

        boolean interrupted = false;
        try {
          // wait for hand-off in runInThread to complete...
          while (handoffPending) {
            try {
              nextRunnableLock.wait(100);
            } catch (InterruptedException e) {
              interrupted = true;
            }
          }

          // Wait until all worker threads are shut down
          while (!busyWorkers.isEmpty()) {
            WorkerThread wt = (WorkerThread) busyWorkers.getFirst();
            try {
              getLog().debug("Waiting for thread {} to shut down", wt.getName());

              // note: with waiting infinite time the
              // application may appear to 'hang'.
              nextRunnableLock.wait(2000);
            } catch (InterruptedException e) {
              interrupted = true;
            }
          }

          Iterator<WorkerThread> workerThreads = workers.iterator();
          while (workerThreads.hasNext()) {
            WorkerThread wt = (WorkerThread) workerThreads.next();
            try {
              wt.join();
              workerThreads.remove();
            } catch (InterruptedException e) {
              interrupted = true;
            }
          }
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }

        getLog().debug("No executing jobs remaining, all threads stopped.");
      }
      getLog().debug("Shutdown of threadpool complete.");
    }
  }

  /**
   * Run the given <code>Runnable</code> object in the next available <code>Thread</code>. If while
   * waiting the thread pool is asked to shut down, the Runnable is executed immediately within a
   * new additional thread.
   *
   * @param runnable the <code>Runnable</code> to be added.
   */
  public boolean runInThread(Runnable runnable) {
    if (runnable == null) {
      return false;
    }

    synchronized (nextRunnableLock) {
      handoffPending = true;

      // Wait until a worker thread is available
      while ((availWorkers.isEmpty()) && !isShutdown) {
        try {
          nextRunnableLock.wait(500);
        } catch (InterruptedException ignore) {
        }
      }

      if (!isShutdown) {
        WorkerThread wt = (WorkerThread) availWorkers.removeFirst();
        busyWorkers.add(wt);
        wt.run(runnable);
      } else {
        // If the thread pool is going down, execute the Runnable
        // within a new additional worker thread (no thread from the pool).
        WorkerThread wt = new WorkerThread("WorkerThread-LastJob", runnable);
        busyWorkers.add(wt);
        workers.add(wt);
        wt.start();
      }
      nextRunnableLock.notifyAll();
      handoffPending = false;
    }

    return true;
  }

  public int blockForAvailableThreads() {
    synchronized (nextRunnableLock) {
      while ((availWorkers.isEmpty() || handoffPending) && !isShutdown) {
        try {
          nextRunnableLock.wait(500);
        } catch (InterruptedException ignore) {
        }
      }

      return availWorkers.size();
    }
  }

  protected void makeAvailable(WorkerThread wt) {
    synchronized (nextRunnableLock) {
      if (!isShutdown) {
        availWorkers.add(wt);
      }
      busyWorkers.remove(wt);
      nextRunnableLock.notifyAll();
    }
  }

  protected void clearFromBusyWorkersList(WorkerThread wt) {
    synchronized (nextRunnableLock) {
      busyWorkers.remove(wt);
      nextRunnableLock.notifyAll();
    }
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * WorkerThread Class.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** A Worker loops, waiting to execute tasks. */
  class WorkerThread extends Thread {

    private final Object lock = new Object();

    // A flag that signals the WorkerThread to terminate.
    private final AtomicBoolean run = new AtomicBoolean(true);

    private @Nullable Runnable runnable;

    private boolean runOnce = false;

    /**
     * Create a worker thread and start it. Waiting for the next Runnable, executing it, and waiting
     * for the next Runnable, until the shutdown flag is set.
     */
    WorkerThread(String name) {
      this(name, null);
    }

    /**
     * Create a worker thread, start it, execute the runnable and terminate the thread (one time
     * execution).
     */
    WorkerThread(String name, @Nullable Runnable runnable) {
      super(name);
      this.runnable = runnable;
      if (runnable != null) runOnce = true;
      // a new thread inherits both from its creator
      setPriority(Thread.NORM_PRIORITY);
      setDaemon(false);
    }

    /** Signal the thread that it should terminate. */
    void shutdown() {
      run.set(false);
    }

    public void run(Runnable newRunnable) {
      synchronized (lock) {
        if (runnable != null) {
          throw new IllegalStateException("Already running a Runnable!");
        }

        runnable = newRunnable;
        lock.notifyAll();
      }
    }

    /** Loop, executing targets as they are received. */
    @Override
    public void run() {
      boolean ran = false;

      while (run.get()) {
        try {
          synchronized (lock) {
            while (runnable == null && run.get()) {
              lock.wait(500);
            }

            if (runnable != null) {
              ran = true;
              runnable.run();
            }
          }
        } catch (InterruptedException unblock) {
          // do nothing (loop will terminate if shutdown() was called
          try {
            getLog().error("Worker thread was interrupt()'ed.", unblock);
          } catch (Exception e) {
            // ignore to help with a tomcat glitch
          }
        } catch (Throwable exceptionInRunnable) {
          try {
            getLog().error("Error while executing the Runnable: ", exceptionInRunnable);
          } catch (Exception e) {
            // ignore to help with a tomcat glitch
          }
        } finally {
          synchronized (lock) {
            runnable = null;
          }
          // repair the thread in case the runnable mucked it up...
          if (getPriority() != Thread.NORM_PRIORITY) {
            setPriority(Thread.NORM_PRIORITY);
          }

          if (runOnce) {
            run.set(false);
            clearFromBusyWorkersList(this);
          } else if (ran) {
            ran = false;
            makeAvailable(this);
          }
        }
      }

      // if (log.isDebugEnabled())
      try {
        getLog().debug("WorkerThread is shut down.");
      } catch (Exception e) {
        // ignore to help with a tomcat glitch
      }
    }
  }
}
