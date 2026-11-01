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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.SchedulerInstance;
import org.quartz.SchedulerMetaData;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.simpl.SimpleThreadPool;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class QuartzHealthIndicatorTest {

  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  private final Scheduler scheduler = mock(Scheduler.class);

  private final QuartzHealthIndicator indicator =
      new QuartzHealthIndicator(scheduler, Clock.fixed(NOW, ZoneOffset.UTC));

  private void metaData(boolean started, boolean standby, boolean shutdown, boolean clustered)
      throws Exception {
    given(scheduler.getMetaData())
        .willReturn(
            new SchedulerMetaData(
                "app",
                "node-1",
                Object.class,
                false,
                started,
                standby,
                shutdown,
                NOW,
                0,
                PersistentJobStore.class,
                true,
                clustered,
                SimpleThreadPool.class,
                4,
                "x"));
  }

  @Test
  void startedSchedulerIsUp() throws Exception {
    metaData(true, false, false, false);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("scheduler", "app")
        .containsEntry("instanceId", "node-1")
        .containsEntry("state", "started")
        .containsEntry("clustered", false)
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
    assertThat(health.getDetails())
        .containsEntry("error", JobPersistenceException.class.getName() + ": store unreachable");
  }

  @Test
  void aliveClusterInstanceIsUp() throws Exception {
    metaData(true, false, false, true);
    instances(current(NOW.minusSeconds(10), true), other(true), other(false));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("lastCheckin", NOW.minusSeconds(10))
        .containsEntry("checkinAge", Duration.ofSeconds(10))
        .containsEntry("instances", 3)
        .containsEntry("aliveInstances", 2L);
  }

  @Test
  void clusterInstanceThatIsNoLongerAliveIsDown() throws Exception {
    metaData(true, false, false, true);
    instances(current(NOW.minusSeconds(36), false), other(true));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("checkinAge", Duration.ofSeconds(36))
        .containsKey("reason");
  }

  @Test
  void clusterInstanceRecoveredByAnotherIsDown() throws Exception {
    metaData(true, false, false, true);
    instances(other(true));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("instances", 1).containsKey("reason");
  }

  @Test
  void checkinIsNotJudgedBeforeTheSchedulerStarts() throws Exception {
    metaData(false, true, false, true);
    instances(current(NOW.minusSeconds(3600), false));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("lastCheckin");
    then(scheduler).should(never()).getSchedulerInstances();
  }

  private void instances(SchedulerInstance... instances) throws Exception {
    given(scheduler.getSchedulerInstances()).willReturn(List.of(instances));
  }

  private static SchedulerInstance current(Instant lastCheckin, boolean alive) {
    return new SchedulerInstance("node-1", lastCheckin, Duration.ofSeconds(15), alive, true);
  }

  private static SchedulerInstance other(boolean alive) {
    return new SchedulerInstance(
        "node-" + (alive ? "up" : "down"), NOW, Duration.ofSeconds(15), alive, false);
  }
}
