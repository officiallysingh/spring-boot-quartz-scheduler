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

import org.quartz.spi.MutableTrigger;

/**
 * The schedule part of a trigger: {@link SimpleScheduleBuilder}, {@link CronScheduleBuilder},
 * {@link CalendarIntervalScheduleBuilder} or {@link DailyTimeIntervalScheduleBuilder}, passed to
 * {@link TriggerBuilder#withSchedule}.
 *
 * @param <T> the type of trigger the schedule produces
 */
public abstract class ScheduleBuilder<T extends Trigger> {

  /**
   * Build the trigger for this schedule; {@link TriggerBuilder} then sets its identity, times and
   * data.
   *
   * @return the trigger
   */
  protected abstract MutableTrigger build();
}
