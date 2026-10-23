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

package org.quartz.core;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.impl.JobExecutionContextImpl;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JobRunShell instances are responsible for providing the 'safe' environment for <code>Job</code> s
 * to run in, and for performing all of the work of executing the <code>Job</code>, catching ANY
 * thrown exceptions, updating the <code>Trigger</code> with the <code>Job</code>'s completion code,
 * etc.
 *
 * <p>A <code>JobRunShell</code> instance is created by a <code>JobRunShellFactory</code> on behalf
 * of the <code>QuartzSchedulerThread</code> which then runs the shell in a thread from the
 * configured <code>ThreadPool</code> when the scheduler determines that a <code>Job</code> has been
 * triggered.
 *
 * <p>While a job runs, including its listeners, the SLF4J {@link MDC} holds {@value #MDC_JOB_KEY},
 * {@value #MDC_TRIGGER_KEY} and {@value #MDC_FIRE_INSTANCE_ID}, so every log line of the execution
 * names it (add them to the log pattern, for example {@code %X{quartz.jobKey}}). They are removed
 * when the execution ends.
 *
 * @see JobRunShellFactory
 * @see org.quartz.core.QuartzSchedulerThread
 * @see org.quartz.Job
 * @see org.quartz.Trigger
 * @author James House
 */
public class JobRunShell extends SchedulerListenerSupport implements Runnable {

  /** MDC key of the executing job's key, as {@code group.name}. */
  public static final String MDC_JOB_KEY = "quartz.jobKey";

  /** MDC key of the key of the trigger that fired the execution, as {@code group.name}. */
  public static final String MDC_TRIGGER_KEY = "quartz.triggerKey";

  /** MDC key of the fire instance id, unique to this firing of the trigger. */
  public static final String MDC_FIRE_INSTANCE_ID = "quartz.fireInstanceId";

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  // set by initialize before run, and dropped by passivate once the shell is done
  /** The context of the execution; created by {@link #initialize}. */
  @SuppressWarnings("NullAway.Init")
  protected JobExecutionContextImpl jec;

  /** The scheduler running the job; set by {@link #initialize}. */
  @SuppressWarnings("NullAway.Init")
  protected QuartzScheduler qs;

  /** The fired trigger and its job. */
  protected TriggerFiredBundle firedTriggerBundle;

  /** The scheduler handed to the job in its context. */
  protected Scheduler scheduler;

  /** Set when the scheduler shuts down while the job runs. */
  protected volatile boolean shutdownRequested = false;

  private final Logger log = LoggerFactory.getLogger(getClass());

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Constructors.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /**
   * Create a JobRunShell instance with the given settings.
   *
   * @param scheduler The <code>Scheduler</code> instance that should be made available within the
   *     <code>JobExecutionContext</code>.
   */
  public JobRunShell(Scheduler scheduler, TriggerFiredBundle bundle) {
    this.scheduler = scheduler;
    this.firedTriggerBundle = bundle;
  }

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Interface.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  @Override
  public void schedulerShuttingdown() {
    requestShutdown();
  }

  @Override
  protected Logger getLog() {
    return log;
  }

  /**
   * Create the job instance (through the scheduler's job factory) and its execution context.
   *
   * @param sched the scheduler running the job
   * @throws SchedulerException if the job cannot be instantiated; the scheduler listeners are told
   */
  public void initialize(QuartzScheduler sched) throws SchedulerException {
    this.qs = sched;

    Job job;
    JobDetail jobDetail = firedTriggerBundle.getJobDetail();

    try {
      job = sched.getJobFactory().newJob(firedTriggerBundle, scheduler);
    } catch (SchedulerException se) {
      sched.notifySchedulerListenersError(
          "An error occurred instantiating job to be executed. job= '" + jobDetail.getKey() + "'",
          se);
      throw se;
    } catch (Throwable ncdfe) { // such as NoClassDefFoundError
      SchedulerException se =
          new SchedulerException(
              "Problem instantiating class '" + jobDetail.getJobClass().getName() + "' - ", ncdfe);
      sched.notifySchedulerListenersError(
          "An error occurred instantiating job to be executed. job= '" + jobDetail.getKey() + "'",
          se);
      throw se;
    }

    this.jec = new JobExecutionContextImpl(scheduler, firedTriggerBundle, job);
  }

  /** Note that the scheduler is shutting down; the running job is not interrupted. */
  public void requestShutdown() {
    shutdownRequested = true;
  }

  public void run() {
    qs.addInternalSchedulerListener(this);
    MDC.put(MDC_JOB_KEY, jec.getJobDetail().getKey().toString());
    MDC.put(MDC_TRIGGER_KEY, jec.getTrigger().getKey().toString());
    MDC.put(MDC_FIRE_INSTANCE_ID, jec.getFireInstanceId());

    try {
      OperableTrigger trigger = (OperableTrigger) jec.getTrigger();
      JobDetail jobDetail = jec.getJobDetail();

      do {

        JobExecutionException jobExEx = null;
        Job job = jec.getJobInstance();

        try {
          begin();
        } catch (SchedulerException se) {
          qs.notifySchedulerListenersError(
              "Error executing Job (" + jec.getJobDetail().getKey() + ": couldn't begin execution.",
              se);
          break;
        }

        // notify job & trigger listeners...
        try {
          if (!notifyListenersBeginning(jec)) {
            break;
          }
        } catch (VetoedException ve) {
          try {
            CompletedExecutionInstruction instCode = trigger.executionComplete(jec, null);
            qs.notifyJobStoreJobVetoed(trigger, jobDetail, instCode);

            // QTZ-205
            // Even if trigger got vetoed, we still needs to check to see if it's the trigger's
            // finalized run or not.
            if (jec.getTrigger().getNextFireTime() == null) {
              qs.notifySchedulerListenersFinalized(jec.getTrigger());
            }

            complete(true);
          } catch (SchedulerException se) {
            qs.notifySchedulerListenersError(
                "Error during veto of Job ("
                    + jec.getJobDetail().getKey()
                    + ": couldn't finalize execution.",
                se);
          }
          break;
        }

        long startTime = System.currentTimeMillis();
        long endTime;

        // execute the job
        try {
          log.debug("Calling execute on job {}", jobDetail.getKey());
          executeJob(job, jec);
          endTime = System.currentTimeMillis();
        } catch (JobExecutionException jee) {
          endTime = System.currentTimeMillis();
          jobExEx = jee;
          getLog().info("Job {} threw a JobExecutionException: ", jobDetail.getKey(), jobExEx);
        } catch (Throwable e) {
          endTime = System.currentTimeMillis();
          getLog().error("Job {} threw an unhandled Exception: ", jobDetail.getKey(), e);
          SchedulerException se = new JobExecutionProcessException(jec, e);
          qs.notifySchedulerListenersError(
              "Job " + jec.getJobDetail().getKey() + " threw an exception.", se);
          jobExEx = new JobExecutionException(se, false);
        }

        jec.setJobRunTime(Duration.ofMillis(endTime - startTime));

        // notify all job listeners
        if (!notifyJobListenersComplete(jec, jobExEx)) {
          break;
        }

        CompletedExecutionInstruction instCode = CompletedExecutionInstruction.NOOP;

        // update the trigger
        try {
          instCode = trigger.executionComplete(jec, jobExEx);
        } catch (Exception e) {
          // If this happens, there's a bug in the trigger...
          SchedulerException se =
              new SchedulerException("Trigger threw an unhandled exception.", e);
          qs.notifySchedulerListenersError(
              "Please report this error to the Quartz developers.", se);
        }

        // notify all trigger listeners
        if (!notifyTriggerListenersComplete(jec, instCode)) {
          break;
        }

        // update job/trigger or re-execute job
        if (instCode == CompletedExecutionInstruction.RE_EXECUTE_JOB) {
          jec.incrementRefireCount();
          try {
            complete(false);
          } catch (SchedulerException se) {
            qs.notifySchedulerListenersError(
                "Error executing Job ("
                    + jec.getJobDetail().getKey()
                    + ": couldn't finalize execution.",
                se);
          }
          continue;
        }

        try {
          complete(true);
        } catch (SchedulerException se) {
          qs.notifySchedulerListenersError(
              "Error executing Job ("
                  + jec.getJobDetail().getKey()
                  + ": couldn't finalize execution.",
              se);
          continue;
        }

        qs.notifyJobStoreJobComplete(trigger, jobDetail, instCode);
        break;
      } while (true);

    } finally {
      // pool threads are reused: leave nothing behind for the next job
      MDC.remove(MDC_JOB_KEY);
      MDC.remove(MDC_TRIGGER_KEY);
      MDC.remove(MDC_FIRE_INSTANCE_ID);
      qs.removeInternalSchedulerListener(this);
    }
  }

  /**
   * Runs the job itself; called once per execution attempt, after the listeners were notified and
   * only when the execution was not vetoed. Subclasses may wrap the execution (for example in a
   * transaction); whatever this method throws is reported as the job's outcome.
   */
  protected void executeJob(Job job, JobExecutionContextImpl context) throws JobExecutionException {
    job.execute(context);
  }

  /**
   * Called before each execution attempt, before the listeners are told; subclasses may start work
   * here.
   *
   * @throws SchedulerException to abort the execution
   */
  protected void begin() throws SchedulerException {}

  /**
   * Called after each execution attempt.
   *
   * @param successfulExecution whether the attempt completed (a re-execution request counts as not
   *     successful)
   * @throws SchedulerException if completing fails; the scheduler listeners are told
   */
  protected void complete(boolean successfulExecution) throws SchedulerException {}

  // drops the references for the garbage collector; the shell is not used again
  /** Drop the references held for the execution, once it is over. */
  @SuppressWarnings("NullAway")
  public void passivate() {
    jec = null;
    qs = null;
  }

  private boolean notifyListenersBeginning(JobExecutionContext jobExCtx) throws VetoedException {

    boolean vetoed;

    // notify all trigger listeners
    try {
      vetoed = qs.notifyTriggerListenersFired(jobExCtx);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify TriggerListener(s) while firing trigger "
              + "(Trigger and Job will NOT be fired!). trigger= "
              + jobExCtx.getTrigger().getKey()
              + " job= "
              + jobExCtx.getJobDetail().getKey(),
          se);

      return false;
    }

    if (vetoed) {
      try {
        qs.notifyJobListenersWasVetoed(jobExCtx);
      } catch (SchedulerException se) {
        qs.notifySchedulerListenersError(
            "Unable to notify JobListener(s) of vetoed execution "
                + "while firing trigger (Trigger and Job will NOT be "
                + "fired!). trigger= "
                + jobExCtx.getTrigger().getKey()
                + " job= "
                + jobExCtx.getJobDetail().getKey(),
            se);
      }
      throw new VetoedException();
    }

    // notify all job listeners
    try {
      qs.notifyJobListenersToBeExecuted(jobExCtx);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify JobListener(s) of Job to be executed: "
              + "(Job will NOT be executed!). trigger= "
              + jobExCtx.getTrigger().getKey()
              + " job= "
              + jobExCtx.getJobDetail().getKey(),
          se);

      return false;
    }

    return true;
  }

  private boolean notifyJobListenersComplete(
      JobExecutionContext jobExCtx, @Nullable JobExecutionException jobExEx) {
    try {
      qs.notifyJobListenersWasExecuted(jobExCtx, jobExEx);
    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify JobListener(s) of Job that was executed: "
              + "(error will be ignored). trigger= "
              + jobExCtx.getTrigger().getKey()
              + " job= "
              + jobExCtx.getJobDetail().getKey(),
          se);

      return false;
    }

    return true;
  }

  private boolean notifyTriggerListenersComplete(
      JobExecutionContext jobExCtx, CompletedExecutionInstruction instCode) {
    try {
      qs.notifyTriggerListenersComplete(jobExCtx, instCode);

    } catch (SchedulerException se) {
      qs.notifySchedulerListenersError(
          "Unable to notify TriggerListener(s) of Job that was executed: "
              + "(error will be ignored). trigger= "
              + jobExCtx.getTrigger().getKey()
              + " job= "
              + jobExCtx.getJobDetail().getKey(),
          se);

      return false;
    }
    if (jobExCtx.getTrigger().getNextFireTime() == null) {
      qs.notifySchedulerListenersFinalized(jobExCtx.getTrigger());
    }

    return true;
  }

  static class VetoedException extends Exception {

    private static final long serialVersionUID = 1539955697495918463L;

    public VetoedException() {}
  }
}
