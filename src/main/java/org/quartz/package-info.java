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

/**
 * The main package of Quartz, containing the client-side interfaces.
 *
 * <p>Quartz provides a builder-style API for constructing scheduling-related entities. It is best
 * used through static imports of the methods on {@code TriggerBuilder}, {@code JobBuilder}, {@code
 * DateBuilder}, {@code JobKey}, {@code TriggerKey} and the {@code ScheduleBuilder} implementations:
 *
 * <pre>
 * JobDetail job = newJob(MyJob.class)
 *     .withIdentity("myJob")
 *     .build();
 *
 * Trigger trigger = newTrigger()
 *     .withIdentity(triggerKey("myTrigger", "myTriggerGroup"))
 *     .withSchedule(simpleSchedule()
 *         .withIntervalInHours(1)
 *         .repeatForever())
 *     .startAt(futureDate(10, MINUTES))
 *     .build();
 *
 * scheduler.scheduleJob(job, trigger);
 * </pre>
 */
@NullMarked
package org.quartz;

import org.jspecify.annotations.NullMarked;
