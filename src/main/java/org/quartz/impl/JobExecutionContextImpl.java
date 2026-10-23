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

package org.quartz.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;

/**
 * The {@link JobExecutionContext} the scheduler builds for each job execution, from the fired
 * trigger's {@link TriggerFiredBundle}: the job data of the job and the trigger merged, the
 * trigger's times, and a map of values the job and listeners can share during the execution.
 */
public class JobExecutionContextImpl implements java.io.Serializable, JobExecutionContext {

  private static final long serialVersionUID = -8139417614523942021L;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private final transient Scheduler scheduler;

  private final Trigger trigger;

  private final JobDetail jobDetail;

  private final JobDataMap jobDataMap;

  private final transient Job job;

  private final @Nullable Calendar calendar;

  private boolean recovering;

  private int numRefires = 0;

  private final Instant fireTime;

  private final Instant scheduledFireTime;

  private final @Nullable Instant prevFireTime;

  private final @Nullable Instant nextFireTime;

  private Duration jobRunTime = Duration.ofMillis(-1);

  private @Nullable Object result;

  private final HashMap<Object, Object> data = new HashMap<>();

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** Create a JobExecutionContext with the given context data. */
  public JobExecutionContextImpl(Scheduler scheduler, TriggerFiredBundle firedBundle, Job job) {
    this.scheduler = scheduler;
    this.trigger = firedBundle.getTrigger();
    this.calendar = firedBundle.getCalendar();
    this.jobDetail = firedBundle.getJobDetail();
    this.job = job;
    this.recovering = firedBundle.isRecovering();
    this.fireTime = firedBundle.getFireTime();
    this.scheduledFireTime = firedBundle.getScheduledFireTime();
    this.prevFireTime = firedBundle.getPrevFireTime();
    this.nextFireTime = firedBundle.getNextFireTime();

    this.jobDataMap = new JobDataMap();
    this.jobDataMap.putAll(jobDetail.getJobDataMap());
    this.jobDataMap.putAll(trigger.getJobDataMap());
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** {@inheritDoc} */
  public Scheduler getScheduler() {
    return scheduler;
  }

  /** {@inheritDoc} */
  public Trigger getTrigger() {
    return trigger;
  }

  /** {@inheritDoc} */
  public @Nullable Calendar getCalendar() {
    return calendar;
  }

  /** {@inheritDoc} */
  public boolean isRecovering() {
    return recovering;
  }

  public TriggerKey getRecoveringTriggerKey() {
    if (isRecovering()) {
      // the store puts the original trigger into the data of a recovering job
      return new TriggerKey(
          Objects.requireNonNull(jobDataMap.getString(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME)),
          jobDataMap.getString(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP));
    } else {
      throw new IllegalStateException("Not a recovering job");
    }
  }

  /** Count one more re-execution of the job for this firing. */
  public void incrementRefireCount() {
    numRefires++;
  }

  /** {@inheritDoc} */
  public int getRefireCount() {
    return numRefires;
  }

  /** {@inheritDoc} */
  public JobDataMap getMergedJobDataMap() {
    return jobDataMap;
  }

  /** {@inheritDoc} */
  public JobDetail getJobDetail() {
    return jobDetail;
  }

  /** {@inheritDoc} */
  public Job getJobInstance() {
    return job;
  }

  /** {@inheritDoc} */
  public Instant getFireTime() {
    return fireTime;
  }

  /** {@inheritDoc} */
  public Instant getScheduledFireTime() {
    return scheduledFireTime;
  }

  /** {@inheritDoc} */
  public @Nullable Instant getPreviousFireTime() {
    return prevFireTime;
  }

  /** {@inheritDoc} */
  public @Nullable Instant getNextFireTime() {
    return nextFireTime;
  }

  @Override
  public String toString() {
    return "JobExecutionContext:"
        + " trigger: '"
        + getTrigger().getKey()
        + " job: "
        + getJobDetail().getKey()
        + " fireTime: '"
        + getFireTime()
        + " scheduledFireTime: "
        + getScheduledFireTime()
        + " previousFireTime: '"
        + getPreviousFireTime()
        + " nextFireTime: "
        + getNextFireTime()
        + " isRecovering: "
        + isRecovering()
        + " refireCount: "
        + getRefireCount();
  }

  /** {@inheritDoc} */
  public @Nullable Object getResult() {
    return result;
  }

  /** {@inheritDoc} */
  public void setResult(@Nullable Object result) {
    this.result = result;
  }

  /** {@inheritDoc} */
  public Duration getJobRunTime() {
    return jobRunTime;
  }

  /**
   * Record how long the job ran; set once its execution has ended.
   *
   * @param jobRunTime the run time
   */
  public void setJobRunTime(Duration jobRunTime) {
    this.jobRunTime = jobRunTime;
  }

  /** {@inheritDoc} */
  public void put(Object key, Object value) {
    data.put(key, value);
  }

  /** {@inheritDoc} */
  public @Nullable Object get(Object key) {
    return data.get(key);
  }

  /** {@inheritDoc} */
  // set on every trigger the store fires
  @SuppressWarnings("NullAway")
  public String getFireInstanceId() {
    return ((OperableTrigger) trigger).getFireInstanceId();
  }
}
