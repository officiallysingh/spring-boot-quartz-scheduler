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

import java.time.ZoneId;

/**
 * The public interface for inspecting settings specific to a CronTrigger, . which is used to fire a
 * <code>{@link org.quartz.Job}</code> at given moments in time, defined with Unix 'cron-like'
 * schedule definitions.
 *
 * <p>For those unfamiliar with "cron", this means being able to create a firing schedule such as:
 * "At 8:00am every Monday through Friday" or "At 1:30am every last Friday of the month".
 *
 * <p>The format of a "Cron-Expression" string is documented on the {@link
 * org.quartz.CronExpression} class.
 *
 * <p>Here are some full examples:
 *
 * <table>
 * <caption>Examples of cron expressions and their meanings.</caption>
 * <tr>
 * <th>Expression</th>
 * <th>&nbsp;</th>
 * <th>Meaning</th>
 * </tr>
 * <tr>
 * <td><code>"0 0 12 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 12pm (noon) every day</code></td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * *"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am every day</code></td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am every day</code></td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 * * ? *"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am every day</code></td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 * * ? 2005"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am every day during the year 2005</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 * 14 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire every minute starting at 2pm and ending at 2:59pm, every day</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 0/5 14 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire every 5 minutes starting at 2pm and ending at 2:55pm, every day</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 0/5 14,18 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire every 5 minutes starting at 2pm and ending at 2:55pm, AND fire every 5 minutes starting at 6pm and ending at 6:55pm, every day</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 0-5 14 * * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire every minute starting at 2pm and ending at 2:05pm, every day</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 10,44 14 ? 3 WED"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 2:10pm and at 2:44pm every Wednesday in the month of March.</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * MON-FRI"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am every Monday, Tuesday, Wednesday, Thursday and Friday</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 15 * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on the 15th day of every month</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 L * ?"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on the last day of every month</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * 6L"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on the last Friday of every month</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * 6L"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on the last Friday of every month</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * 6L 2002-2005"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on every last Friday of every month during the years 2002, 2003, 2004 and 2005</code>
 * </td>
 * </tr>
 * <tr>
 * <td><code>"0 15 10 ? * 6#3"</code></td>
 * <td>&nbsp;</td>
 * <td><code>Fire at 10:15am on the third Friday of every month</code>
 * </td>
 * </tr>
 * </table>
 *
 * <p>Pay attention to the effects of '?' and '*' in the day-of-week and day-of-month fields!
 *
 * <p><b>NOTES:</b>
 *
 * <ul>
 *   <li>Support for specifying both a day-of-week and a day-of-month value is not complete (you'll
 *       need to use the '?' character in on of these fields).
 *   <li>Be careful when setting fire times between mid-night and 1:00 AM - "daylight savings" can
 *       cause a skip or a repeat depending on whether the time moves back or jumps forward.
 * </ul>
 *
 * @see CronScheduleBuilder
 * @see TriggerBuilder
 * @author jhouse
 * @author Contributions from Mads Henderson
 */
public interface CronTrigger extends Trigger {

  /**
   * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire situation, the <code>
   * {@link CronTrigger}</code> wants to be fired now by <code>Scheduler</code>.
   */
  int MISFIRE_INSTRUCTION_FIRE_ONCE_NOW = 1;

  /**
   * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire situation, the <code>
   * {@link CronTrigger}</code> wants to have it's next-fire-time updated to the next time in the
   * schedule after the current time (taking into account any associated <code>{@link Calendar}
   * </code>, but it does not want to be fired now.
   */
  int MISFIRE_INSTRUCTION_DO_NOTHING = 2;

  /**
   * The cron expression the trigger fires on.
   *
   * @return the expression, for example {@code "0 0/15 8-17 ? * MON-FRI"}
   */
  String getCronExpression();

  /**
   * Returns the time zone for which the <code>cronExpression</code> of this <code>CronTrigger
   * </code> will be resolved.
   *
   * <p>If none was set ({@link CronScheduleBuilder#inTimeZone(ZoneId)}), the scheduler's default
   * time zone applies from the moment the trigger is scheduled: the <code>spring.quartz.time-zone
   * </code> property, UTC by default (see {@link Scheduler#getTimeZone()}); the zone is then stored
   * with the trigger. Before that, the expression resolves in UTC. The JVM default zone is never
   * used. A local time that does not exist on a day (a daylight-saving gap) fires once the gap is
   * over, and one that exists twice (an overlap) fires at the later, standard-time occurrence; see
   * {@link org.quartz.utils.LenientDateTime}.
   */
  ZoneId getTimeZone();

  /**
   * A human-readable summary of the parsed cron expression, for diagnostics.
   *
   * @return the summary
   * @see CronExpression#getExpressionSummary()
   */
  String getExpressionSummary();

  TriggerBuilder<CronTrigger> getTriggerBuilder();
}
