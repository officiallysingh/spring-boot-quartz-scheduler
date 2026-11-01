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

/** The order of {@link Scheduler#getTriggers(TriggerQuery)}; ties are broken by trigger key. */
public enum TriggerSort {
  /** By trigger group, then name. */
  KEY,
  /** By next fire time ("what fires next"); triggers without one come first. */
  NEXT_FIRE_TIME,
  /** By previous fire time; triggers that never fired come first. */
  PREVIOUS_FIRE_TIME,
  /** By priority. */
  PRIORITY
}
