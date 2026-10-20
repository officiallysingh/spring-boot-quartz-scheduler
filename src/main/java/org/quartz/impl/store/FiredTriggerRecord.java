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
package org.quartz.impl.store;

import java.time.Instant;

import org.quartz.JobKey;
import org.quartz.TriggerKey;

/**
 * A fired-trigger entry: a trigger acquired or executing on a scheduler instance.
 * Kept until the execution completes, so that a failed instance's work can be
 * recovered by another one.
 *
 * @param fireInstanceId unique id of this firing
 * @param instanceId the scheduler instance that acquired the trigger
 * @param triggerKey the trigger
 * @param jobKey the trigger's job
 * @param state {@code ACQUIRED} or {@code EXECUTING}
 * @param firedTime when the entry was written
 * @param scheduledTime the fire time the trigger was scheduled for
 * @param priority the trigger's priority
 * @param nonConcurrent whether the job disallows concurrent execution (only known once executing)
 * @param requestsRecovery whether the job requests recovery (only known once executing)
 */
public record FiredTriggerRecord(
        String fireInstanceId,
        String instanceId,
        TriggerKey triggerKey,
        JobKey jobKey,
        String state,
        Instant firedTime,
        Instant scheduledTime,
        int priority,
        boolean nonConcurrent,
        boolean requestsRecovery) {
}
