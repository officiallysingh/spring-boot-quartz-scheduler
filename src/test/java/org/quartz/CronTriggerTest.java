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
package org.quartz;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.text.ParseException;
import org.junit.jupiter.api.Test;
import org.quartz.impl.triggers.CronTriggerImpl;

/** Unit test for CronTrigger. */
public class CronTriggerTest extends SerializationTestSupport {

  /** The object to serialize and compare with its deserialized copy. */
  @Override
  protected Object getTargetObject() throws Exception {
    JobDataMap jobDataMap = new JobDataMap();
    jobDataMap.put("A", "B");

    CronTriggerImpl t = new CronTriggerImpl();
    t.setKey(new TriggerKey("test", "testGroup"));
    t.setCronExpression("0 0 12 * * ?");
    t.setCalendarName("MyCalendar");
    t.setDescription("CronTriggerDesc");
    t.setJobDataMap(jobDataMap);

    return t;
  }

  /** Verify that the target object and the object we just deserialized match. */
  @Override
  protected void verifyMatch(Object target, Object deserialized) {
    CronTriggerImpl targetCronTrigger = (CronTriggerImpl) target;
    CronTriggerImpl deserializedCronTrigger = (CronTriggerImpl) deserialized;

    assertNotNull(deserializedCronTrigger);
    assertEquals(targetCronTrigger.getKey(), deserializedCronTrigger.getKey());
    assertEquals(targetCronTrigger.getJobKey(), deserializedCronTrigger.getJobKey());
    //        assertEquals(targetCronTrigger.getStartTime(),
    // deserializedCronTrigger.getStartTime());
    assertEquals(targetCronTrigger.getEndTime(), deserializedCronTrigger.getEndTime());
    assertEquals(targetCronTrigger.getCalendarName(), deserializedCronTrigger.getCalendarName());
    assertEquals(targetCronTrigger.getDescription(), deserializedCronTrigger.getDescription());
    assertEquals(targetCronTrigger.getJobDataMap(), deserializedCronTrigger.getJobDataMap());
    assertEquals(
        targetCronTrigger.getCronExpression(), deserializedCronTrigger.getCronExpression());
  }

  @Test
  void testClone() throws ParseException {
    CronTriggerImpl trigger = new CronTriggerImpl();
    trigger.setKey(new TriggerKey("test", "testGroup"));
    trigger.setCronExpression("0 0 12 * * ?");
    CronTrigger trigger2 = (CronTrigger) trigger.clone();

    assertEquals(trigger, trigger2, "Cloning failed");

    // equals() doesn't test the cron expression
    assertEquals(
        "0 0 12 * * ?", trigger2.getCronExpression(), "Cloning failed for the cron expression");
  }

  @Test
  void testCloneOfTriggerWithoutExpression() throws ParseException {
    CronTriggerImpl trigger = new CronTriggerImpl();
    trigger.setKey(new TriggerKey("test", "testGroup"));
    CronTrigger trigger2 = (CronTrigger) trigger.clone();

    assertEquals(trigger, trigger2, "Cloning failed");
  }

  @Test
  void testMisfireInstructionValidity() throws ParseException {
    CronTriggerImpl trigger = new CronTriggerImpl();

    try {
      trigger.setMisfireInstruction(Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY);
      trigger.setMisfireInstruction(Trigger.MISFIRE_INSTRUCTION_SMART_POLICY);
      trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
      trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);
    } catch (Exception e) {
      fail("Unexpected exception while setting misfire instruction.");
    }

    try {
      trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING + 1);

      fail("Expected exception while setting invalid misfire instruction but did not get it.");
    } catch (Exception e) {
    }
  }

  @Test
  void testMisfireInstructionInDerivedBuilder() throws ParseException {
    for (int policy :
        asList(
            Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY,
            Trigger.MISFIRE_INSTRUCTION_SMART_POLICY,
            CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,
            CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW)) {
      CronTriggerImpl trigger = new CronTriggerImpl();
      trigger.setCronExpression("0 0 12 * * ?");
      trigger.setMisfireInstruction(policy);
      assertThat(trigger.getMisfireInstruction()).isEqualTo(policy);

      CronTrigger copy = trigger.getTriggerBuilder().build();
      assertThat(copy.getMisfireInstruction()).isEqualTo(policy);
    }
  }

  @Test
  void testUndefinedMisfireInstructionInDerivedBuilder() throws ParseException {
    CronTriggerImpl trigger =
        new CronTriggerImpl() {
          @Override
          public int getMisfireInstruction() {
            return 12345;
          }
        };
    trigger.setCronExpression("0 0 12 * * ?");
    try {
      trigger.setMisfireInstruction(12345);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage())
          .isEqualTo("The misfire instruction code is invalid for this type of trigger.");
    }

    CronTrigger copy = trigger.getTriggerBuilder().build();
    assertThat(copy.getMisfireInstruction()).isEqualTo(Trigger.MISFIRE_INSTRUCTION_SMART_POLICY);
  }
}
