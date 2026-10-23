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

package org.quartz.impl.triggers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.quartz.Calendar;
import org.quartz.CronTrigger;
import org.quartz.SimpleTrigger;
import org.quartz.impl.calendar.BaseCalendar;

class ClockMisfireTest {

  @Test
  void simpleTriggerFireNowUsesSuppliedSchedulingTime() {
    Instant schedulingTime = Instant.parse("2030-01-01T10:30:15Z");
    SimpleTriggerImpl trigger = new SimpleTriggerImpl();
    trigger.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW);

    trigger.updateAfterMisfire(null, schedulingTime);

    assertEquals(schedulingTime, trigger.getNextFireTime());
  }

  @Test
  void cronTriggerCalculatesNextFireTimeFromSuppliedSchedulingTime() throws Exception {
    CronTriggerImpl trigger = new CronTriggerImpl();
    trigger.setCronExpression("0 0 * * * ?");
    trigger.setTimeZone(ZoneId.of("UTC"));
    trigger.setStartTime(Instant.parse("2029-01-01T00:00:00Z"));
    trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    trigger.computeFirstFireTime(null);

    trigger.updateAfterMisfire(null, Instant.parse("2030-01-01T10:30:15Z"));

    assertEquals(Instant.parse("2030-01-01T11:00:00Z"), trigger.getNextFireTime());
  }

  @Test
  void calendarUpdateUsesSuppliedSchedulingTime() {
    Instant midnight = Instant.parse("2030-01-01T00:00:00Z");
    Instant oneAm = Instant.parse("2030-01-01T01:00:00Z");
    SimpleTriggerImpl trigger = new SimpleTriggerImpl();
    trigger.setStartTime(midnight);
    trigger.setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY);
    trigger.setRepeatInterval(Duration.ofMillis(3_600_000L));
    trigger.setPreviousFireTime(midnight);
    trigger.setNextFireTime(oneAm);

    Calendar calendar =
        new BaseCalendar() {
          private static final long serialVersionUID = 1L;

          @Override
          public boolean isTimeIncluded(Instant timeStamp) {
            return !timeStamp.equals(oneAm);
          }
        };
    trigger.updateWithNewCalendar(
        calendar, Duration.ofMillis(0L), Instant.parse("2030-01-01T10:00:00Z"));

    assertEquals(Instant.parse("2030-01-01T03:00:00Z"), trigger.getNextFireTime());
  }
}
