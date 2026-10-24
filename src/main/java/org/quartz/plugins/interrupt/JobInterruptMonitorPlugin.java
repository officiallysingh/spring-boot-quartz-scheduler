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
 */
package org.quartz.plugins.interrupt;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.spi.SchedulerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This plugin catches the event of job running for a long time (more than the configured max time)
 * and tells the scheduler to "try" interrupting it if enabled.
 *
 * <p>Only jobs that opt in are watched: their job data must set {@value #AUTO_INTERRUPTIBLE} to
 * {@code true}. A job may override the {@linkplain #setDefaultMaxRunTime default maximum run time}
 * with {@value #MAX_RUN_TIME} in its job data, in milliseconds. Interrupting calls {@link
 * org.quartz.InterruptableJob#interrupt()}, so the job has to implement it and stop.
 *
 * <p>Define it as a bean; the bean name is the plugin name:
 *
 * <pre>{@code
 * @Bean
 * JobInterruptMonitorPlugin jobInterruptMonitor() {
 *     JobInterruptMonitorPlugin plugin = new JobInterruptMonitorPlugin();
 *     plugin.setDefaultMaxRunTime(Duration.ofMinutes(10));
 *     return plugin;
 * }
 * }</pre>
 *
 * @see org.quartz.Scheduler#interrupt(JobKey)
 * @author Rama Chavali
 */
public class JobInterruptMonitorPlugin extends TriggerListenerSupport implements SchedulerPlugin {

  private static final String JOB_INTERRUPT_MONITOR_KEY = "JOB_INTERRUPT_MONITOR_KEY";

  private Duration defaultMaxRunTime = Duration.ofMinutes(5);

  @SuppressWarnings("NullAway.Init")
  private String name;

  @SuppressWarnings("NullAway.Init")
  private ScheduledExecutorService executor;

  @SuppressWarnings("rawtypes")
  private final ConcurrentMap<String, ScheduledFuture> futures = new ConcurrentHashMap<>();

  @SuppressWarnings("NullAway.Init")
  private Scheduler scheduler;

  private final Logger log = LoggerFactory.getLogger(getClass());

  // Constants

  /** Job data key: {@code true} to have the job interrupted when it runs too long. */
  public static final String AUTO_INTERRUPTIBLE = "AutoInterruptable";

  /** Job data key: the job's maximum run time in milliseconds, overriding the default. */
  public static final String MAX_RUN_TIME = "MaxRunTime";

  /** Create the plugin with a default maximum run time of 5 minutes. */
  public JobInterruptMonitorPlugin() {}

  @Override
  public void start() {}

  @Override
  public void shutdown() {
    this.executor.shutdown();
  }

  protected Logger getLog() {
    return log;
  }

  /**
   * Schedule the interruption of a job after a delay.
   *
   * @param jobkey the job to interrupt
   * @param delay how long the job may run
   * @return the scheduled interruption, cancelled when the job completes in time
   */
  @SuppressWarnings("rawtypes")
  public ScheduledFuture scheduleJobInterruptMonitor(JobKey jobkey, Duration delay) {
    return this.executor.schedule(
        new InterruptMonitor(jobkey, scheduler), delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  // Bean Property Methods

  /**
   * How long a job may run unless its job data sets {@value #MAX_RUN_TIME}.
   *
   * @return the default maximum run time
   */
  public Duration getDefaultMaxRunTime() {
    return this.defaultMaxRunTime;
  }

  /**
   * Set how long a job may run unless its job data sets {@value #MAX_RUN_TIME}; default 5 minutes.
   *
   * @param defaultMaxRunTime the default maximum run time
   */
  public void setDefaultMaxRunTime(Duration defaultMaxRunTime) {
    this.defaultMaxRunTime = defaultMaxRunTime;
  }

  // Trigger Listener Methods
  public String getName() {
    return name;
  }

  public void triggerFired(Trigger trigger, JobExecutionContext context) {
    // Call the scheduleJobInterruptMonitor and capture the ScheduledFuture in context
    try {
      // Schedule Monitor only if the job wants AutoInterruptable functionality
      if (context.getMergedJobDataMap().getBoolean(AUTO_INTERRUPTIBLE)) {
        // the plugin puts itself into the scheduler context when it starts
        JobInterruptMonitorPlugin monitorPlugin =
            (JobInterruptMonitorPlugin)
                Objects.requireNonNull(
                    context.getScheduler().getContext().get(JOB_INTERRUPT_MONITOR_KEY));
        // Get the MaxRuntime (milliseconds) from Job Data if NOT available use defaultMaxRunTime
        // from Plugin Configuration
        Duration jobDataDelay = defaultMaxRunTime;

        if (context.getMergedJobDataMap().get(MAX_RUN_TIME) != null) {
          jobDataDelay = Duration.ofMillis(context.getMergedJobDataMap().getLong(MAX_RUN_TIME));
        }
        ScheduledFuture<?> future =
            monitorPlugin.scheduleJobInterruptMonitor(
                context.getJobDetail().getKey(), jobDataDelay);
        futures.put(context.getFireInstanceId(), future);
        getLog()
            .debug(
                "Job's Interrupt Monitor has been scheduled to interrupt with the delay :{}",
                jobDataDelay);
      }
    } catch (SchedulerException e) {
      getLog().info("Error scheduling interrupt monitor {}", e.getMessage(), e);
    }
  }

  @SuppressWarnings("rawtypes")
  public void triggerComplete(
      Trigger trigger,
      JobExecutionContext context,
      CompletedExecutionInstruction triggerInstructionCode) {
    // cancel the Future if job is complete
    ScheduledFuture future = futures.remove(context.getFireInstanceId());
    if (future != null) {
      future.cancel(true);
    }
  }

  @Override
  public void initialize(String name, Scheduler scheduler) throws SchedulerException {

    getLog().info("Registering Job Interrupt Monitor Plugin");
    this.name = name;
    ScheduledThreadPoolExecutor threadPoolExecutor = new ScheduledThreadPoolExecutor(1);
    threadPoolExecutor.setRemoveOnCancelPolicy(true);
    this.executor = threadPoolExecutor;
    scheduler.getContext().put(JOB_INTERRUPT_MONITOR_KEY, this);
    this.scheduler = scheduler;
    // Set the trigger Listener as this class to the ListenerManager here
    this.scheduler.getListenerManager().addTriggerListener(this);
  }

  static class InterruptMonitor implements Runnable {

    private final JobKey jobKey;
    private final Scheduler scheduler;

    private final Logger log = LoggerFactory.getLogger(getClass());

    InterruptMonitor(JobKey jobKey, Scheduler scheduler) {
      this.jobKey = jobKey;
      this.scheduler = scheduler;
    }

    protected Logger getLog() {
      return log;
    }

    @Override
    public void run() {
      try {

        // Interrupt the job here - using Scheduler API that gets propagated to Job's interrupt
        getLog()
            .info(
                "Interrupting Job as it ran more than the configured max time. Job Details [{}:{}]",
                jobKey.getName(),
                jobKey.getGroup());
        scheduler.interrupt(jobKey);
      } catch (SchedulerException x) {
        getLog().info("Error interrupting Job: {}", x.getMessage(), x);
      }
    }
  }
}
