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

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A scheduler instance known to the job store, with its liveness; see {@link
 * Scheduler#getSchedulerInstances()}.
 *
 * @param instanceId the instance's id ({@link Scheduler#getSchedulerInstanceId()})
 * @param lastCheckin when it last checked in with the cluster; {@code null} when the store is not
 *     clustered (instances do not check in)
 * @param checkinInterval how often it checks in; {@code null} when the store is not clustered
 * @param alive whether it counts as running: by the rule the store uses to decide when to recover
 *     an instance's work, its last check-in is no older than its check-in interval plus the store's
 *     {@code cluster-checkin-misfire-threshold}. An instance that is not alive has failed, or soon
 *     will be recovered, and the executions it was running will not complete normally.
 * @param current whether this is the instance the method was called on
 */
public record SchedulerInstance(
    String instanceId,
    @Nullable Instant lastCheckin,
    @Nullable Duration checkinInterval,
    boolean alive,
    boolean current) {}
