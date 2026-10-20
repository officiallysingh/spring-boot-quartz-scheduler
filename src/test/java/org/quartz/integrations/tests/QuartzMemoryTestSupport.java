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
package org.quartz.integrations.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.quartz.Scheduler;
import org.quartz.SchedulerFactory;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.support.TestSchedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * A base class to support in-memory scheduler integration testing. Each test will have a fresh
 * scheduler created and started, and it will auto shutdown upon each test run.
 *
 * @author Zemian Deng
 */
public class QuartzMemoryTestSupport {
    protected static final Logger LOG = LoggerFactory.getLogger(QuartzMemoryTestSupport.class);
    protected Scheduler scheduler;

    @BeforeEach
    public void initSchedulerBeforeTest() throws Exception {
        SchedulerFactory sf = createSchedulerFactory();
        scheduler = sf.getScheduler();
        afterSchedulerInit();
    }

    protected void afterSchedulerInit() throws Exception {
        LOG.info("Scheduler starting.");
        scheduler.start();
        LOG.info("Scheduler started.");
    }

    protected SchedulerFactory createSchedulerFactory() {
        return StdSchedulerFactory.builder()
                .scheduler(SchedulerSettings.builder().name("TestScheduler").instanceIdAuto(true).build())
                .threadPool(TestSchedulers.threadPool(12))
                .jobStore(TestSchedulers.inMemoryStore().toBuilder().misfireThreshold(Duration.ofSeconds(10)).build())
                .build();
    }

    @AfterEach
    public void initSchedulerAfterTest() throws Exception {
        LOG.info("Scheduler shutting down.");
        scheduler.shutdown(true);
        LOG.info("Scheduler shutdown complete.");
    }
}
