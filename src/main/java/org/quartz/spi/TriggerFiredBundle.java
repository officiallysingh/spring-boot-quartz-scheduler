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

package org.quartz.spi;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobDetail;

/**
 * A simple class (structure) used for returning execution-time data from the JobStore to the <code>
 * QuartzSchedulerThread</code>.
 *
 * @see org.quartz.core.QuartzSchedulerThread
 * @author James House
 */
public class TriggerFiredBundle implements java.io.Serializable {

  private static final long serialVersionUID = -6414106108306999265L;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private final JobDetail job;

  private final OperableTrigger trigger;

  private final @Nullable Calendar cal;

  private final boolean jobIsRecovering;

  private final Instant fireTime;

  private final Instant scheduledFireTime;

  private final @Nullable Instant prevFireTime;

  private final @Nullable Instant nextFireTime;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a bundle.
   *
   * @param job the job to execute
   * @param trigger the trigger that fired, already updated for its next firing
   * @param cal the trigger's calendar, or {@code null}
   * @param jobIsRecovering whether this execution recovers one that a failed instance had started
   * @param fireTime when the trigger actually fired
   * @param scheduledFireTime when the trigger was scheduled to fire
   * @param prevFireTime the trigger's previous fire time, or {@code null}
   * @param nextFireTime the trigger's next fire time, or {@code null} if it will not fire again
   */
  public TriggerFiredBundle(
      JobDetail job,
      OperableTrigger trigger,
      @Nullable Calendar cal,
      boolean jobIsRecovering,
      Instant fireTime,
      Instant scheduledFireTime,
      @Nullable Instant prevFireTime,
      @Nullable Instant nextFireTime) {
    this.job = job;
    this.trigger = trigger;
    this.cal = cal;
    this.jobIsRecovering = jobIsRecovering;
    this.fireTime = fireTime;
    this.scheduledFireTime = scheduledFireTime;
    this.prevFireTime = prevFireTime;
    this.nextFireTime = nextFireTime;
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * The job to execute.
   *
   * @return the job
   */
  public JobDetail getJobDetail() {
    return job;
  }

  /**
   * The trigger that fired.
   *
   * @return the trigger
   */
  public OperableTrigger getTrigger() {
    return trigger;
  }

  /**
   * The trigger's calendar.
   *
   * @return the calendar, or {@code null} if the trigger has none
   */
  public @Nullable Calendar getCalendar() {
    return cal;
  }

  /**
   * Whether this execution recovers one that a failed instance had started.
   *
   * @return {@code true} when recovering
   */
  public boolean isRecovering() {
    return jobIsRecovering;
  }

  /**
   * When the trigger actually fired.
   *
   * @return the fire time
   */
  public Instant getFireTime() {
    return fireTime;
  }

  /**
   * The trigger's next fire time.
   *
   * @return the next fire time, or {@code null} if it will not fire again
   */
  public @Nullable Instant getNextFireTime() {
    return nextFireTime;
  }

  /**
   * The trigger's previous fire time.
   *
   * @return the previous fire time, or {@code null}
   */
  public @Nullable Instant getPrevFireTime() {
    return prevFireTime;
  }

  /**
   * When the trigger was scheduled to fire (earlier than {@link #getFireTime()} if it fired late).
   *
   * @return the scheduled fire time
   */
  public Instant getScheduledFireTime() {
    return scheduledFireTime;
  }
}
