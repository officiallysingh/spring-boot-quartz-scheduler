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
package org.quartz.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.SchedulerPlugin;

/**
 * {@link StdSchedulerFactory} checks that need no job store; the rest is in
 * {@link StdSchedulerFactoryIT}.
 */
class StdSchedulerFactoryTest {

    @Test
    void aJobStoreIsRequired() {
        assertThatThrownBy(StdSchedulerFactory::getDefaultScheduler)
                .isInstanceOf(SchedulerConfigException.class)
                .hasMessageContaining("No JobStore configured");
        assertThatThrownBy(() -> new StdSchedulerFactory().getScheduler())
                .isInstanceOf(SchedulerConfigException.class)
                .hasMessageContaining("Builder.jobStore(JobStore)");
    }

    @Test
    void duplicatePluginNamesAreRejected() {
        SchedulerPlugin plugin = new SchedulerPlugin() {
            public void initialize(String name, Scheduler scheduler, ClassLoadHelper loadHelper) {
            }

            public void start() {
            }

            public void shutdown() {
            }
        };

        assertThatThrownBy(() -> StdSchedulerFactory.builder().plugin("p", plugin).plugin("p", plugin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate plugin name");
    }
}
