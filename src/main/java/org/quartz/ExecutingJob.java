/*
 * Copyright 2026 the spring-boot-quartz-scheduler authors.
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
package org.quartz;

import java.time.Instant;

/**
 * A job execution in progress on some scheduler instance of the cluster, as recorded by the job
 * store; see {@link Scheduler#getClusterExecutingJobs()}.
 *
 * @param fireInstanceId the execution's {@link JobExecutionContext#getFireInstanceId()}, also the
 *     argument of {@link Scheduler#interruptAnywhere(String)}
 * @param schedulerInstanceId the scheduler instance running it
 * @param jobKey the job
 * @param triggerKey the trigger that fired it
 * @param firedTime when it was fired (its {@link JobExecutionContext#getFireTime()})
 * @param scheduledFireTime the fire time the trigger was scheduled for
 * @param requestsRecovery whether the job is run again if its instance fails ({@link
 *     JobDetail#requestsRecovery()})
 * @param interruptRequested whether {@link Scheduler#interruptAnywhere(String)} has asked for it to
 *     be interrupted
 */
public record ExecutingJob(
    String fireInstanceId,
    String schedulerInstanceId,
    JobKey jobKey,
    TriggerKey triggerKey,
    Instant firedTime,
    Instant scheduledFireTime,
    boolean requestsRecovery,
    boolean interruptRequested) {}
