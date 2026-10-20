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
 */

package org.quartz.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.support.MutableClock;
import org.quartz.support.TestSchedulers;

/**
 * Verifies that Quartz uses the configured {@link Clock} as its time source,
 * rather than System.currentTimeMillis().
 * (Ported from QuartzSchedulerTimeBrokerTest by Thanos Tsiamis https://github.com/ThanosTsiamis.)
 */
public class QuartzSchedulerClockTest {

    static final class FailingClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new IllegalStateException("Test scheduling-time failure");
        }
    }

    /**
     * Simple job that increments a static counter every time it runs.
     */
    public static class CountingJob implements Job {

        private static final AtomicInteger RUN_COUNT = new AtomicInteger(0);

        public static void reset() {
            RUN_COUNT.set(0);
        }

        public static int runCount() {
            return RUN_COUNT.get();
        }

        @Override
        public void execute(JobExecutionContext context) {
            RUN_COUNT.incrementAndGet();
        }
    }

    private static Scheduler createScheduler(Clock clock) throws SchedulerException {
        return TestSchedulers.factory(SchedulerSettings.builder().name("ClockTest"), 1)
                .clock(clock)
                .build()
                .getScheduler();
    }

    @Test
    void clockCannotBeNull() {
        QuartzSchedulerResources resources = new QuartzSchedulerResources();

        assertThatThrownBy(() -> resources.setClock(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Clock cannot be null.");
        assertThatThrownBy(() -> StdSchedulerFactory.builder().clock(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void repeatedClockFailuresNotifyListenersOnlyOnce() throws Exception {
        Scheduler scheduler = createScheduler(new FailingClock());
        AtomicInteger errorCount = new AtomicInteger();
        scheduler.getListenerManager().addSchedulerListener(new SchedulerListenerSupport() {
            @Override
            public void schedulerError(String msg, SchedulerException cause) {
                if ("Unable to obtain the current scheduling time".equals(msg)) {
                    errorCount.incrementAndGet();
                }
            }
        });

        try {
            scheduler.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> errorCount.get() == 1);

            // the scheduler thread retries once per second; consecutive failures must not re-notify
            await().during(Duration.ofMillis(2_200)).atMost(Duration.ofSeconds(4))
                    .until(() -> errorCount.get() == 1);
        } finally {
            scheduler.shutdown(true);
        }
    }

    /**
     * If clock time is frozen before the trigger's startAt(), the job
     * should not fire, even if real time moves forward.
     */
    @Test
    void jobDoesNotFireWhileClockIsFrozen() throws Exception {
        CountingJob.reset();

        Instant start = Instant.now();
        MutableClock clock = new MutableClock(start); // clock == system at start

        Scheduler scheduler = createScheduler(clock);
        try {
            scheduler.start();

            JobDetail job = JobBuilder.newJob(CountingJob.class)
                    .withIdentity("job1", "group1")
                    .build();

            // Fire 2 seconds after "clock start"
            Trigger trigger = newTrigger()
                    .withIdentity("trigger1", "group1")
                    .startAt(Instant.from(start.plusSeconds(2)))
                    .build();

            scheduler.scheduleJob(job, trigger);

            // Wait longer than 2 seconds in REAL time, but do NOT move the clock
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7))
                    .until(() -> CountingJob.runCount() == 0);
        } finally {
            scheduler.shutdown(true);
        }
    }

    /**
     * When clock time is advanced while the scheduler is in standby, the job
     * should fire after the scheduler is resumed, even if real time has not
     * reached the trigger's start time.
     */
    @Test
    void jobFiresWhenClockAdvancesWhileInStandby() throws Exception {
        CountingJob.reset();

        Instant start = Instant.now();
        MutableClock clock = new MutableClock(start);

        Scheduler scheduler = createScheduler(clock);
        try {
            scheduler.start();
            scheduler.standby();

            JobDetail job = JobBuilder.newJob(CountingJob.class)
                    .withIdentity("job2", "group1")
                    .build();

            // Fire 2 seconds after "clock start"
            Trigger trigger = newTrigger()
                    .withIdentity("trigger2", "group1")
                    .startAt(Instant.from(start.plusSeconds(2)))
                    .build();

            scheduler.scheduleJob(job, trigger);

            assertThat(CountingJob.runCount()).as("Job should not have fired yet").isZero();

            // Move clock time past the fire time
            clock.advance(Duration.ofSeconds(5));

            // Resuming wakes the scheduler thread and causes it to re-evaluate
            // triggers using the updated clock time.
            scheduler.start();

            await().atMost(Duration.ofSeconds(5)).until(() -> CountingJob.runCount() == 1);
        } finally {
            scheduler.shutdown(true);
        }

        assertThat(CountingJob.runCount())
                .as("Job should fire when clock time has passed startAt and the scheduler resumes")
                .isEqualTo(1);
    }
}
