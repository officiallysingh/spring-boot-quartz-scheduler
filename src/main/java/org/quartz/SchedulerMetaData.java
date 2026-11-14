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

package org.quartz;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Describes the settings and capabilities of a given <code>{@link Scheduler}</code> instance.
 *
 * @author James House
 */
public class SchedulerMetaData {

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private final String schedName;

  private final String schedInst;

  private final Class<?> schedClass;

  private final boolean started;

  private final boolean isInStandbyMode;

  private final boolean shutdown;

  private final @Nullable Instant startTime;

  private final int numJobsExec;

  private final Class<?> jsClass;

  private final boolean jsClustered;

  private final Class<?> tpClass;

  private final int tpSize;

  private final String tpDescription;

  private final String version;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create meta data.
   *
   * @param schedName the scheduler's name
   * @param schedInst the scheduler's instance id
   * @param schedClass the scheduler's class
   * @param started whether the scheduler has been started
   * @param isInStandbyMode whether the scheduler is in standby mode
   * @param shutdown whether the scheduler has been shut down
   * @param startTime when the scheduler started, or {@code null} if it has not
   * @param numJobsExec the number of jobs executed since the scheduler started
   * @param jsClass the job store's class
   * @param jsClustered whether the job store is clustered
   * @param tpClass the thread pool's class
   * @param tpSize the thread pool's size or concurrency limit
   * @param tpDescription how the thread pool runs jobs ({@link
   *     org.quartz.spi.ThreadPool#describe()})
   * @param version the Quartz version
   */
  public SchedulerMetaData(
      String schedName,
      String schedInst,
      Class<?> schedClass,
      boolean started,
      boolean isInStandbyMode,
      boolean shutdown,
      @Nullable Instant startTime,
      int numJobsExec,
      Class<?> jsClass,
      boolean jsClustered,
      Class<?> tpClass,
      int tpSize,
      String tpDescription,
      String version) {
    this.schedName = schedName;
    this.schedInst = schedInst;
    this.schedClass = schedClass;
    this.started = started;
    this.isInStandbyMode = isInStandbyMode;
    this.shutdown = shutdown;
    this.startTime = startTime;
    this.numJobsExec = numJobsExec;
    this.jsClass = jsClass;
    this.jsClustered = jsClustered;
    this.tpClass = tpClass;
    this.tpSize = tpSize;
    this.tpDescription = tpDescription;
    this.version = version;
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** Returns the name of the <code>Scheduler</code>. */
  public String getSchedulerName() {
    return schedName;
  }

  /** Returns the instance Id of the <code>Scheduler</code>. */
  public String getSchedulerInstanceId() {
    return schedInst;
  }

  /** Returns the class-name of the <code>Scheduler</code> instance. */
  public Class<?> getSchedulerClass() {
    return schedClass;
  }

  /**
   * Returns the instant at which the Scheduler started running.
   *
   * @return null if the scheduler has not been started.
   */
  public @Nullable Instant getRunningSince() {
    return startTime;
  }

  /** Returns the number of jobs executed since the <code>Scheduler</code> started.. */
  public int getNumberOfJobsExecuted() {
    return numJobsExec;
  }

  /**
   * Returns whether the scheduler has been started.
   *
   * <p>Note: <code>isStarted()</code> may return <code>true</code> even if <code>isInStandbyMode()
   * </code> returns <code>true</code>.
   */
  public boolean isStarted() {
    return started;
  }

  /** Reports whether the <code>Scheduler</code> is in standby mode. */
  public boolean isInStandbyMode() {
    return isInStandbyMode;
  }

  /** Reports whether the <code>Scheduler</code> has been shutdown. */
  public boolean isShutdown() {
    return shutdown;
  }

  /**
   * Returns the class-name of the <code>JobStore</code> instance that is being used by the <code>
   * Scheduler</code>.
   */
  public Class<?> getJobStoreClass() {
    return jsClass;
  }

  /** Returns whether or not the <code>Scheduler</code>'s<code>JobStore</code> is clustered. */
  public boolean isJobStoreClustered() {
    return jsClustered;
  }

  /**
   * Returns the class-name of the <code>ThreadPool</code> instance that is being used by the <code>
   * Scheduler</code>.
   */
  public Class<?> getThreadPoolClass() {
    return tpClass;
  }

  /**
   * Returns the number of threads currently in the <code>Scheduler</code>'s <code>ThreadPool</code>
   * .
   */
  public int getThreadPoolSize() {
    return tpSize;
  }

  /**
   * How the thread pool runs jobs, as in the summary: for example {@code "with 10 threads"} or
   * {@code "virtual threads, at most 50 concurrent jobs"}.
   */
  public String getThreadPoolDescription() {
    return tpDescription;
  }

  /** Returns the version of Quartz that is running. */
  public String getVersion() {
    return version;
  }

  /** Return a simple string representation of this object. */
  @Override
  public String toString() {
    try {
      return getSummary();
    } catch (SchedulerException se) {
      return "SchedulerMetaData: undeterminable.";
    }
  }

  /**
   * Returns a formatted (human readable) String describing all the <code>Scheduler</code>'s
   * meta-data values.
   *
   * <p>The format of the String looks something like this:
   *
   * <pre>
   * Quartz Scheduler (v1.0.0) 'app' with instanceId 'node-1'
   *   Scheduler class: 'org.quartz.impl.StdScheduler'.
   *   Running since: 2026-01-01T00:00:00Z
   *   Not currently in standby mode.
   *   Number of jobs executed: 123
   *   Using thread pool 'org.quartz.simpl.SimpleThreadPool' - with 10 threads.
   *   Using job-store 'org.quartz.impl.mongodb.MongoJobStore' - which is clustered.
   * </pre>
   */
  public String getSummary() throws SchedulerException {
    StringBuilder str = new StringBuilder("Quartz Scheduler (v");
    str.append(getVersion());
    str.append(") '");

    str.append(getSchedulerName());
    str.append("' with instanceId '");
    str.append(getSchedulerInstanceId());
    str.append("'\n");

    str.append("  Scheduler class: '");
    str.append(getSchedulerClass().getName());
    str.append("'.\n");

    if (!isShutdown()) {
      if (getRunningSince() != null) {
        str.append("  Running since: ");
        str.append(getRunningSince());
      } else {
        str.append("  NOT STARTED.");
      }
      str.append("\n");

      if (isInStandbyMode()) {
        str.append("  Currently in standby mode.");
      } else {
        str.append("  Not currently in standby mode.");
      }
    } else {
      str.append("  Scheduler has been SHUTDOWN.");
    }
    str.append("\n");

    str.append("  Number of jobs executed: ");
    str.append(getNumberOfJobsExecuted());
    str.append("\n");

    str.append("  Using thread pool '");
    str.append(getThreadPoolClass().getName());
    str.append("' - ");
    str.append(getThreadPoolDescription());
    str.append(".");
    str.append("\n");

    str.append("  Using job-store '");
    str.append(getJobStoreClass().getName());
    str.append("' - which ");
    if (isJobStoreClustered()) {
      str.append("is clustered.");
    } else {
      str.append("is not clustered.");
    }
    str.append("\n");

    return str.toString();
  }
}
