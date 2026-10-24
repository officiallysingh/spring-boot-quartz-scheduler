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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsTest {

  @Test
  void schedulerDefaultsMatchTheShippedQuartzProperties() {
    SchedulerSettings s = SchedulerSettings.defaults();

    assertThat(s.name()).isEqualTo("DefaultQuartzScheduler");
    assertThat(s.instanceId()).isEqualTo("NON_CLUSTERED");
    assertThat(s.instanceIdAuto()).isFalse();
    assertThat(s.effectiveThreadName()).isEqualTo("DefaultQuartzScheduler_QuartzSchedulerThread");
    assertThat(s.idleWaitTime()).isEqualTo(Duration.ofSeconds(30));
    assertThat(s.batchTriggerAcquisitionFireAheadTimeWindow()).isZero();
    assertThat(s.batchTriggerAcquisitionMaxCount()).isEqualTo(1);
    assertThat(s.makeSchedulerThreadDaemon()).isFalse();
    assertThat(s.interruptJobsOnShutdown()).isFalse();
    assertThat(s.interruptJobsOnShutdownWithWait()).isFalse();
    assertThat(s.context()).isEmpty();
  }

  @Test
  void threadPoolDefaultsMatchTheShippedQuartzProperties() {
    ThreadPoolSettings s = ThreadPoolSettings.defaults();

    assertThat(s.threadCount()).isEqualTo(10);
    assertThat(s.threadPriority()).isEqualTo(Thread.NORM_PRIORITY);
    assertThat(s.threadNamePrefix()).isNull();
    assertThat(s.daemon()).isFalse();
    assertThat(s.threadsInheritGroupOfInitializingThread()).isTrue();
    assertThat(s.virtualThreads()).isFalse();
  }

  @Test
  void jobStoreDefaults() {
    JobStoreSettings s = JobStoreSettings.defaults();

    assertThat(s.misfireThreshold()).isEqualTo(Duration.ofSeconds(60));
    assertThat(s.clustered()).isFalse();
    assertThat(s.clusterCheckinInterval()).isEqualTo(Duration.ofMillis(7500));
    assertThat(s.maxMisfiresToHandleAtATime()).isEqualTo(20);
    assertThat(s.retryInterval()).isEqualTo(Duration.ofSeconds(15));
    assertThat(s.doubleCheckLockMisfireHandler()).isTrue();
  }

  @Test
  void toBuilderRoundTrips() {
    SchedulerSettings scheduler =
        SchedulerSettings.builder()
            .name("n")
            .instanceIdAuto(true)
            .idleWaitTime(Duration.ofSeconds(2))
            .contextEntry("k", "v")
            .build();
    ThreadPoolSettings pool =
        ThreadPoolSettings.builder().threadCount(3).daemon(true).virtualThreads(true).build();
    JobStoreSettings store =
        JobStoreSettings.builder().clustered(true).misfireThreshold(Duration.ofSeconds(5)).build();

    assertThat(scheduler.toBuilder().build()).isEqualTo(scheduler);
    assertThat(pool.toBuilder().build()).isEqualTo(pool);
    assertThat(store.toBuilder().build()).isEqualTo(store);
  }

  @Test
  void contextIsCopiedAndUnmodifiable() {
    Map<String, String> source = new HashMap<>();
    source.put("a", "1");
    SchedulerSettings s = SchedulerSettings.builder().context(source).build();
    source.put("b", "2");

    assertThat(s.context()).containsOnlyKeys("a");
    assertThatThrownBy(() -> s.context().put("c", "3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void schedulerValidation() {
    assertThatThrownBy(() -> SchedulerSettings.builder().name(" ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> SchedulerSettings.builder().instanceId(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("instanceId");
    assertThatThrownBy(
            () -> SchedulerSettings.builder().idleWaitTime(Duration.ofMillis(999)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("idleWaitTime");
    assertThatThrownBy(
            () ->
                SchedulerSettings.builder()
                    .batchTriggerAcquisitionFireAheadTimeWindow(Duration.ofMillis(-1))
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchedulerSettings.builder().batchTriggerAcquisitionMaxCount(0).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void threadPoolValidation() {
    assertThatThrownBy(() -> ThreadPoolSettings.builder().threadCount(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threadCount");
    assertThatThrownBy(() -> ThreadPoolSettings.builder().threadPriority(11).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threadPriority");
    assertThatThrownBy(() -> ThreadPoolSettings.builder().threadNamePrefix("").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jobStoreValidation() {
    assertThatThrownBy(() -> JobStoreSettings.builder().misfireThreshold(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("misfireThreshold");
    assertThatThrownBy(
            () -> JobStoreSettings.builder().retryInterval(Duration.ofSeconds(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryInterval");
    assertThatThrownBy(() -> JobStoreSettings.builder().maxMisfiresToHandleAtATime(0).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new QuartzSettings(null, ThreadPoolSettings.defaults()))
        .isInstanceOf(NullPointerException.class);
  }
}
