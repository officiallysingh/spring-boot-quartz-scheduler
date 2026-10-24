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

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.quartz.impl.calendar.AnnualCalendar;

/** Unit test for AnnualCalendar serialization. */
public class AnnualCalendarTest extends SerializationTestSupport {

  private static final ZoneId EST_TIME_ZONE = ZoneId.of("America/New_York");

  /** The object to serialize and compare with its deserialized copy. */
  @Override
  protected Object getTargetObject() {
    AnnualCalendar c = new AnnualCalendar();

    c.setDescription("description");

    ZonedDateTime cal = ZonedDateTime.of(2005, 1, 20, 10, 5, 15, 0, EST_TIME_ZONE);

    c.setDayExcluded(cal, true);

    return c;
  }

  /** Verify that the target object and the object we just deserialized match. */
  @Override
  protected void verifyMatch(Object target, Object deserialized) {
    AnnualCalendar targetCalendar = (AnnualCalendar) target;
    AnnualCalendar deserializedCalendar = (AnnualCalendar) deserialized;

    assertNotNull(deserializedCalendar);
    assertEquals(targetCalendar.getDescription(), deserializedCalendar.getDescription());
    assertEquals(targetCalendar.getDaysExcluded(), deserializedCalendar.getDaysExcluded());
    assertNull(deserializedCalendar.getTimeZone());
  }

  /**
   * Tests if method <code>setDaysExcluded</code> protects the property daysExcluded against
   * nulling. See: QUARTZ-590
   */
  @Test
  void testDaysExcluded() {
    AnnualCalendar annualCalendar = new AnnualCalendar();

    annualCalendar.setDaysExcluded(null);

    assertNotNull(
        annualCalendar.getDaysExcluded(),
        "Annual calendar daysExcluded property should have been set to empty ArrayList, not null.");
  }

  /**
   * Tests the parameter <code>exclude</code> in a method <code>setDaysExcluded</code> of class
   * <code>org.quartz.impl.calendar.AnnualCalendar</code>
   */
  @Test
  void testExclude() {
    AnnualCalendar annualCalendar = new AnnualCalendar();
    ZonedDateTime day = ZonedDateTime.now();

    day = day.withMonth(10).withDayOfMonth(15);
    annualCalendar.setDayExcluded(day, false);

    assertFalse(
        annualCalendar.isDayExcluded(day),
        "The day 15 October is not expected to be excluded but it is");

    day = day.withMonth(10).withDayOfMonth(15);
    annualCalendar.setDayExcluded(day, true);

    day = day.withMonth(11).withDayOfMonth(12);
    annualCalendar.setDayExcluded(day, true);

    day = day.withMonth(9).withDayOfMonth(1);
    annualCalendar.setDayExcluded(day, true);

    assertTrue(
        annualCalendar.isDayExcluded(day),
        "The day 15 October is expected to be excluded but it is not");

    day = day.withMonth(10).withDayOfMonth(15);
    annualCalendar.setDayExcluded(day, false);

    assertFalse(
        annualCalendar.isDayExcluded(day),
        "The day 15 October is not expected to be excluded but it is");
  }

  /** QUARTZ-679 Test if the annualCalendar works over years */
  @Test
  void testDaysExcludedOverTime() {
    AnnualCalendar annualCalendar = new AnnualCalendar();
    ZonedDateTime day = ZonedDateTime.now().withYear(2005).withMonth(6).withDayOfMonth(23);

    annualCalendar.setDayExcluded(day, true);

    day = day.withYear(2008).withMonth(2).withDayOfMonth(1);
    annualCalendar.setDayExcluded(day, true);

    assertTrue(
        annualCalendar.isDayExcluded(day),
        "The day 1 February is expected to be excluded but it is not");
  }

  /** Part 2 of the tests of QUARTZ-679 */
  @Test
  void testRemoveInTheFuture() {
    AnnualCalendar annualCalendar = new AnnualCalendar();
    ZonedDateTime day = ZonedDateTime.now().withYear(2005).withMonth(6).withDayOfMonth(23);

    annualCalendar.setDayExcluded(day, true);

    // Trying to remove the 23th of June
    day = day.withYear(2008).withMonth(6).withDayOfMonth(23);
    annualCalendar.setDayExcluded(day, false);

    assertFalse(
        annualCalendar.isDayExcluded(day),
        "The day 23 June is not expected to be excluded but it is");
  }
}
