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
package org.quartz.spring.boot.actuate.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.SchedulerMetaData;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class QuartzHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final Scheduler scheduler = mock(Scheduler.class);

    private final PersistentJobStore store = mock(PersistentJobStore.class);

    private final QuartzHealthIndicator indicator = new QuartzHealthIndicator(scheduler, store,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private void metaData(boolean started, boolean standby, boolean shutdown, boolean clustered) throws Exception {
        given(scheduler.getMetaData()).willReturn(new SchedulerMetaData("app", "node-1", Object.class, false, started,
                standby, shutdown, NOW, 0, PersistentJobStore.class, true, clustered, SimpleThreadPool.class, 4, "x"));
    }

    @Test
    void startedSchedulerIsUp() throws Exception {
        metaData(true, false, false, false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("scheduler", "app").containsEntry("instanceId", "node-1")
            .containsEntry("state", "started").containsEntry("clustered", false)
            .containsEntry("jobStore", PersistentJobStore.class.getName());
    }

    @Test
    void standbySchedulerIsUp() throws Exception {
        metaData(false, true, false, false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "standby");
    }

    @Test
    void shutDownSchedulerIsDown() throws Exception {
        metaData(true, false, true, false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("state", "shutdown");
    }

    @Test
    void unreachableStoreIsDown() throws Exception {
        metaData(true, false, false, false);
        given(scheduler.getJobGroupNames()).willThrow(new JobPersistenceException("store unreachable"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error",
                JobPersistenceException.class.getName() + ": store unreachable");
    }

    @Test
    void recentClusterCheckinIsUp() throws Exception {
        metaData(true, false, false, true);
        clusterSettings(NOW.minusSeconds(10));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastCheckin", NOW.minusSeconds(10))
            .containsEntry("checkinAge", Duration.ofSeconds(10));
    }

    @Test
    void staleClusterCheckinIsDown() throws Exception {
        metaData(true, false, false, true);
        clusterSettings(NOW.minusSeconds(36)); // limit: 15 s interval + 20 s threshold

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("checkinAge", Duration.ofSeconds(36))
            .containsKey("reason");
    }

    @Test
    void checkinIsNotJudgedBeforeTheSchedulerStarts() throws Exception {
        metaData(false, true, false, true);
        clusterSettings(NOW.minusSeconds(3600));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).doesNotContainKey("lastCheckin");
    }

    private void clusterSettings(Instant lastCheckin) {
        given(store.getLastCheckin()).willReturn(lastCheckin);
        given(store.getSettings()).willReturn(JobStoreSettings.builder().clustered(true)
            .clusterCheckinInterval(Duration.ofSeconds(15)).clusterCheckinMisfireThreshold(Duration.ofSeconds(20))
            .build());
    }

}
