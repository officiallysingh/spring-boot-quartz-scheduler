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

import java.util.Set;

/**
 * Thrown when a scheduler on a clustered job store would get an instance id that cannot tell it
 * apart from the other instances of the cluster. Instances with the same id would take each other
 * for a restarted run of themselves: on its first check-in an instance recovers the triggers and
 * running jobs recorded under its own id, so a second instance would release what the first one has
 * acquired and run its recoverable jobs again.
 */
public class ClusteredInstanceIdException extends SchedulerConfigException {

  private static final long serialVersionUID = 1L;

  /**
   * Instance ids every instance would share: the default of a scheduler that is not clustered, and
   * {@code SYS_PROP}, which is not a keyword and would be used as it is written.
   */
  private static final Set<String> SHARED_IDS = Set.of("NON_CLUSTERED", "SYS_PROP");

  private final String instanceId;

  /**
   * Create the exception.
   *
   * @param instanceId the instance id that is not unique
   */
  public ClusteredInstanceIdException(String instanceId) {
    super(
        "The job store is clustered, but the scheduler's instance id is '"
            + instanceId
            + "', which every instance of the cluster would have");
    this.instanceId = instanceId;
  }

  /**
   * The instance id that is not unique.
   *
   * @return the instance id
   */
  public String getInstanceId() {
    return this.instanceId;
  }

  /**
   * Whether every instance of a cluster would have this instance id.
   *
   * @param instanceId the instance id of a scheduler
   * @return {@code true} if it cannot identify one instance of a cluster
   */
  public static boolean isSharedByEveryInstance(String instanceId) {
    return SHARED_IDS.contains(instanceId);
  }
}
