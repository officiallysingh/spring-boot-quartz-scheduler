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
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a recovery execution replaces: the execution that was interrupted when its scheduler
 * instance failed (see {@link JobDetail#requestsRecovery()}). A typed view of the {@code
 * FAILED_JOB_ORIGINAL_*} entries the store puts into a recovery trigger's job data; see {@link
 * JobExecutionContext#getRecoveryInfo()}.
 *
 * @param originalTrigger the trigger that fired the interrupted execution
 * @param originalFireInstanceId the interrupted execution's {@link
 *     JobExecutionContext#getFireInstanceId()}; {@code null} for recovery triggers created by
 *     versions before 1.0.0-rc2
 * @param originalSchedulerInstanceId the instance that ran it (the one that failed); {@code null}
 *     for recovery triggers created by versions before 1.0.0-rc2
 * @param firedTime when it was fired (its {@link JobExecutionContext#getFireTime()})
 * @param scheduledFireTime the fire time it was scheduled for
 */
public record RecoveryInfo(
    TriggerKey originalTrigger,
    @Nullable String originalFireInstanceId,
    @Nullable String originalSchedulerInstanceId,
    Instant firedTime,
    Instant scheduledFireTime) {

  /**
   * Reads the recovery entries of a recovery trigger's job data.
   *
   * @param data a (merged) job data map
   * @return the recovery information, or empty if the map does not hold a recovery trigger's
   *     entries
   */
  public static Optional<RecoveryInfo> of(JobDataMap data) {
    String name = string(data, Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME);
    String fired = string(data, Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS);
    String scheduled =
        string(data, Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS);
    if (name == null || fired == null || scheduled == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new RecoveryInfo(
              new TriggerKey(name, string(data, Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP)),
              string(data, Scheduler.FAILED_JOB_ORIGINAL_FIRE_INSTANCE_ID),
              string(data, Scheduler.FAILED_JOB_ORIGINAL_SCHEDULER_INSTANCE_ID),
              Instant.ofEpochMilli(Long.parseLong(fired)),
              Instant.ofEpochMilli(Long.parseLong(scheduled))));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private static @Nullable String string(JobDataMap data, String key) {
    Object value = data.get(key);
    return (value != null) ? value.toString() : null;
  }
}
