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

/**
 * An exception that can be thrown by a <code>{@link org.quartz.Job}</code> to indicate to the
 * Quartz <code>{@link Scheduler}</code> that an error occurred while executing, and whether or not
 * the <code>Job</code> requests to be re-fired immediately (using the same <code>
 * {@link JobExecutionContext}</code>, or whether it wants to be unscheduled.
 *
 * <p>Note that if the flag for 'refire immediately' is set, the flags for unscheduling the Job are
 * ignored.
 *
 * @see Job
 * @see JobExecutionContext
 * @see SchedulerException
 * @author James House
 */
public class JobExecutionException extends SchedulerException {

  private static final long serialVersionUID = 1326342535829043325L;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  private boolean refire = false;

  private boolean unscheduleTrigg = false;

  private boolean unscheduleAllTriggs = false;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a JobExecutionException, with the 're-fire immediately' flag set to <code>false</code>.
   */
  public JobExecutionException() {}

  /** Create a JobExecutionException, with the given cause. */
  public JobExecutionException(Throwable cause) {
    super(cause);
  }

  /** Create a JobExecutionException, with the given message. */
  public JobExecutionException(String msg) {
    super(msg);
  }

  /** Create a JobExecutionException with the 're-fire immediately' flag set to the given value. */
  public JobExecutionException(boolean refireImmediately) {
    refire = refireImmediately;
  }

  /**
   * Create a JobExecutionException with the given underlying exception, and the 're-fire
   * immediately' flag set to the given value.
   */
  public JobExecutionException(Throwable cause, boolean refireImmediately) {
    super(cause);

    refire = refireImmediately;
  }

  /** Create a JobExecutionException with the given message, and underlying exception. */
  public JobExecutionException(String msg, Throwable cause) {
    super(msg, cause);
  }

  /**
   * Create a JobExecutionException with the given message, and underlying exception, and the
   * 're-fire immediately' flag set to the given value.
   */
  public JobExecutionException(String msg, Throwable cause, boolean refireImmediately) {
    super(msg, cause);

    refire = refireImmediately;
  }

  /**
   * Create a JobExecutionException with the given message and the 're-fire immediately' flag set to
   * the given value.
   */
  public JobExecutionException(String msg, boolean refireImmediately) {
    super(msg);

    refire = refireImmediately;
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Ask the scheduler to execute the job again immediately.
   *
   * @param refire whether to re-execute the job right away
   */
  public void setRefireImmediately(boolean refire) {
    this.refire = refire;
  }

  /**
   * Whether the scheduler should execute the job again immediately.
   *
   * @return {@code true} to re-execute right away
   */
  public boolean refireImmediately() {
    return refire;
  }

  /**
   * Ask the scheduler to unschedule the trigger that fired this execution.
   *
   * @param unscheduleTrigg whether to unschedule the firing trigger
   */
  public void setUnscheduleFiringTrigger(boolean unscheduleTrigg) {
    this.unscheduleTrigg = unscheduleTrigg;
  }

  /**
   * Whether the scheduler should unschedule the trigger that fired this execution.
   *
   * @return {@code true} to unschedule the firing trigger
   */
  public boolean unscheduleFiringTrigger() {
    return unscheduleTrigg;
  }

  /**
   * Ask the scheduler to unschedule every trigger of this job.
   *
   * @param unscheduleAllTriggs whether to unschedule all of the job's triggers
   */
  public void setUnscheduleAllTriggers(boolean unscheduleAllTriggs) {
    this.unscheduleAllTriggs = unscheduleAllTriggs;
  }

  /**
   * Whether the scheduler should unschedule every trigger of this job.
   *
   * @return {@code true} to unschedule all of the job's triggers
   */
  public boolean unscheduleAllTriggers() {
    return unscheduleAllTriggs;
  }
}
