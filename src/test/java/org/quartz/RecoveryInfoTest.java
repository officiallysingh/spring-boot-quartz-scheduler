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
package org.quartz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link RecoveryInfo#of(JobDataMap)} reads a recovery trigger's job data. */
class RecoveryInfoTest {

  private static JobDataMap recoveryData() {
    return new JobDataMap(
        Map.of(
            Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME, "auction-start",
            Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP, "auctions",
            Scheduler.FAILED_JOB_ORIGINAL_FIRE_INSTANCE_ID, "node-a_42",
            Scheduler.FAILED_JOB_ORIGINAL_SCHEDULER_INSTANCE_ID, "node-a",
            Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS, "2000",
            Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS, "1000"));
  }

  @Test
  void readsEveryEntry() {
    assertThat(RecoveryInfo.of(recoveryData()))
        .contains(
            new RecoveryInfo(
                TriggerKey.triggerKey("auction-start", "auctions"),
                "node-a_42",
                "node-a",
                Instant.ofEpochMilli(2000),
                Instant.ofEpochMilli(1000)));
  }

  @Test
  void isEmptyWithoutAllEntries() {
    for (String key :
        new String[] {
          Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME,
          Scheduler.FAILED_JOB_ORIGINAL_FIRE_INSTANCE_ID,
          Scheduler.FAILED_JOB_ORIGINAL_SCHEDULER_INSTANCE_ID,
          Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS,
          Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS
        }) {
      JobDataMap data = recoveryData();
      data.remove(key);
      assertThat(RecoveryInfo.of(data)).as("without %s", key).isEmpty();
    }
    assertThat(RecoveryInfo.of(new JobDataMap())).isEmpty();
  }

  @Test
  void isEmptyWithAMalformedTime() {
    JobDataMap data = recoveryData();
    data.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS, "yesterday");
    assertThat(RecoveryInfo.of(data)).isEmpty();
  }
}
