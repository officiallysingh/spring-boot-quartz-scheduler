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
package org.quartz.impl.config;

import java.util.Objects;

/**
 * The complete typed configuration of a scheduler, replacing {@code quartz.properties}.
 */
public record QuartzSettings(
        SchedulerSettings scheduler,
        ThreadPoolSettings threadPool) {

    public QuartzSettings {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(threadPool, "threadPool");
    }

    public static QuartzSettings defaults() {
        return new QuartzSettings(SchedulerSettings.defaults(), ThreadPoolSettings.defaults());
    }
}
