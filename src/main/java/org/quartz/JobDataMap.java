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

import java.io.Serializable;
import java.util.Map;
import org.quartz.utils.StringKeyDirtyFlagMap;

/**
 * Holds state information for <code>Job</code> instances.
 *
 * <p><code>JobDataMap</code> instances are stored once when the <code>Job</code> is added to a
 * scheduler. They are also re-persisted after every execution of jobs annotated with
 * {@code @PersistJobDataAfterExecution}.
 *
 * <p><code>JobDataMap</code> instances can also be stored with a <code>Trigger</code>. This can be
 * useful in the case where you have a Job that is stored in the scheduler for regular/repeated use
 * by multiple Triggers, yet with each independent triggering, you want to supply the Job with
 * different data inputs.
 *
 * <p>The <code>JobExecutionContext</code> passed to a Job at execution time also contains a
 * convenience <code>JobDataMap</code> that is the result of merging the contents of the trigger's
 * JobDataMap (if any) over the Job's JobDataMap (if any).
 *
 * <p>Update since 2.2.4 - We keep an dirty flag for this map so that whenever you
 * modify(add/delete) any of the entries, it will set to "true". However if you create new instance
 * using an existing map with {@link #JobDataMap(Map)}, then the dirty flag will NOT be set to
 * "true" until you modify the instance.
 *
 * @see Job
 * @see PersistJobDataAfterExecution
 * @see Trigger
 * @see JobExecutionContext
 * @author James House
 */
public class JobDataMap extends StringKeyDirtyFlagMap implements Serializable {

  private static final long serialVersionUID = -6939901990106713909L;

  /*
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   *
   * Data members.
   *
   * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
   */

  /** Create an empty <code>JobDataMap</code>. */
  public JobDataMap() {
    super(15);
  }

  /** Create a <code>JobDataMap</code> with the given data. */
  public JobDataMap(Map<?, ?> map) {
    this();
    @SuppressWarnings(
        "unchecked") // casting to keep API compatible and avoid compiler errors/warnings.
    Map<String, Object> mapTyped = (Map<String, Object>) map;
    putAll(mapTyped);

    // When constructing a new data map from another existing map, we should NOT mark dirty flag as
    // true
    // Use case: loading JobDataMap from DB
    clearDirtyFlag();
  }
}
