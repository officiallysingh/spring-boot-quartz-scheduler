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
package org.quartz.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.annotation.Propagation;

class QuartzPropertiesTest {

    private static QuartzProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("spring.quartz", QuartzProperties.class);
    }

    @Test
    void defaultsMatchTheTypedSettingsDefaults() {
        QuartzProperties properties = bind(Map.of());

        assertThat(properties.scheduler().toSettings())
                .isEqualTo(SchedulerSettings.builder().name("quartzScheduler").build());
        assertThat(properties.threadPool().toSettings()).isEqualTo(ThreadPoolSettings.defaults());
        assertThat(properties.jobStore().toSettings()).isEqualTo(JobStoreSettings.defaults());
        assertThat(properties.autoStartup()).isTrue();
        assertThat(properties.startupDelay()).isZero();
        assertThat(properties.waitForJobsToCompleteOnShutdown()).isFalse();
        assertThat(properties.overwriteExistingJobs()).isFalse();
        assertThat(properties.threadPool().virtualThreads()).isFalse();
        assertThat(properties.jobStore().type()).isNull();
        assertThat(properties.jobStore().mongodb().collectionPrefix()).isEqualTo("qrtz_");
        assertThat(properties.jobStore().mongodb().lockTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.jobStore().redis().keyPrefix()).isEqualTo("quartz:");
        assertThat(properties.jobStore().redis().lockTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.plugins().jobHistory().enabled()).isFalse();
        assertThat(properties.plugins().triggerHistory().enabled()).isFalse();
        assertThat(properties.plugins().jobInterruptMonitor().enabled()).isFalse();
        assertThat(properties.plugins().jobInterruptMonitor().defaultMaxRunTime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.transaction().enabled()).isFalse();
        assertThat(properties.transaction().defaultPropagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void bindsTheTargetYamlShape() {
        QuartzProperties properties = bind(Map.ofEntries(
                Map.entry("spring.quartz.auto-startup", "false"),
                Map.entry("spring.quartz.startup-delay", "5s"),
                Map.entry("spring.quartz.wait-for-jobs-to-complete-on-shutdown", "true"),
                Map.entry("spring.quartz.overwrite-existing-jobs", "true"),
                Map.entry("spring.quartz.scheduler.name", "app-scheduler"),
                Map.entry("spring.quartz.scheduler.instance-id", "AUTO"),
                Map.entry("spring.quartz.scheduler.idle-wait-time", "45s"),
                Map.entry("spring.quartz.scheduler.batch-trigger-acquisition-max-count", "4"),
                Map.entry("spring.quartz.scheduler.batch-trigger-acquisition-fire-ahead-time-window", "250ms"),
                Map.entry("spring.quartz.scheduler.context.region", "eu"),
                Map.entry("spring.quartz.thread-pool.thread-count", "3"),
                Map.entry("spring.quartz.thread-pool.virtual-threads", "true"),
                Map.entry("spring.quartz.job-store.type", "mongodb"),
                Map.entry("spring.quartz.job-store.misfire-threshold", "90s"),
                Map.entry("spring.quartz.job-store.clustered", "true"),
                Map.entry("spring.quartz.job-store.cluster-checkin-interval", "15s"),
                Map.entry("spring.quartz.job-store.cluster-checkin-misfire-threshold", "20s"),
                Map.entry("spring.quartz.job-store.mongodb.collection-prefix", "jobs_"),
                Map.entry("spring.quartz.job-store.redis.lock-timeout", "1m"),
                Map.entry("spring.quartz.plugins.job-history.enabled", "true"),
                Map.entry("spring.quartz.plugins.job-interrupt-monitor.default-max-run-time", "2h"),
                Map.entry("spring.quartz.transaction.enabled", "true"),
                Map.entry("spring.quartz.transaction.default-propagation", "requires-new")));

        assertThat(properties.autoStartup()).isFalse();
        assertThat(properties.startupDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.waitForJobsToCompleteOnShutdown()).isTrue();
        assertThat(properties.overwriteExistingJobs()).isTrue();

        SchedulerSettings scheduler = properties.scheduler().toSettings();
        assertThat(scheduler.name()).isEqualTo("app-scheduler");
        assertThat(scheduler.instanceIdAuto()).isTrue();
        assertThat(scheduler.instanceId()).isEqualTo(SchedulerSettings.DEFAULT_INSTANCE_ID);
        assertThat(scheduler.idleWaitTime()).isEqualTo(Duration.ofSeconds(45));
        assertThat(scheduler.batchTriggerAcquisitionMaxCount()).isEqualTo(4);
        assertThat(scheduler.batchTriggerAcquisitionFireAheadTimeWindow()).isEqualTo(Duration.ofMillis(250));
        assertThat(scheduler.context()).containsEntry("region", "eu");

        assertThat(properties.threadPool().toSettings().threadCount()).isEqualTo(3);
        assertThat(properties.threadPool().virtualThreads()).isTrue();

        JobStoreSettings store = properties.jobStore().toSettings();
        assertThat(properties.jobStore().type()).isEqualTo(JobStoreType.MONGODB);
        assertThat(store.misfireThreshold()).isEqualTo(Duration.ofSeconds(90));
        assertThat(store.clustered()).isTrue();
        assertThat(store.clusterCheckinInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(store.clusterCheckinMisfireThreshold()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.jobStore().mongodb().collectionPrefix()).isEqualTo("jobs_");
        assertThat(properties.jobStore().redis().lockTimeout()).isEqualTo(Duration.ofMinutes(1));

        assertThat(properties.plugins().jobHistory().enabled()).isTrue();
        assertThat(properties.plugins().jobInterruptMonitor().defaultMaxRunTime()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.transaction().enabled()).isTrue();
        assertThat(properties.transaction().defaultPropagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void instanceIdMapping() {
        SchedulerSettings literal = bind(Map.of("spring.quartz.scheduler.instance-id", "node-7")).scheduler().toSettings();
        assertThat(literal.instanceId()).isEqualTo("node-7");
        assertThat(literal.instanceIdAuto()).isFalse();

        QuartzProperties.Scheduler sysProp = bind(Map.of("spring.quartz.scheduler.instance-id", "SYS_PROP")).scheduler();
        assertThat(sysProp.isSystemPropertyInstanceId()).isTrue();
        assertThat(sysProp.toSettings().instanceIdAuto()).isTrue();
    }
}
