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
package org.quartz.spring.boot.actuate.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.ListenerManager;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.TriggerListener;

class QuartzMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private JobListener jobListener;

    private TriggerListener triggerListener;

    @BeforeEach
    void initialize() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        ListenerManager listenerManager = mock(ListenerManager.class);
        given(scheduler.getSchedulerName()).willReturn("app");
        given(scheduler.getListenerManager()).willReturn(listenerManager);

        new QuartzMetrics(registry).initialize("quartzMetrics", scheduler, null);

        ArgumentCaptor<JobListener> jobListeners = ArgumentCaptor.forClass(JobListener.class);
        ArgumentCaptor<TriggerListener> triggerListeners = ArgumentCaptor.forClass(TriggerListener.class);
        verify(listenerManager).addJobListener(jobListeners.capture());
        verify(listenerManager).addTriggerListener(triggerListeners.capture());
        jobListener = jobListeners.getValue();
        triggerListener = triggerListeners.getValue();
    }

    @Test
    void listenersAreNamed() {
        assertThat(jobListener.getName()).isEqualTo("quartzMetrics");
        assertThat(triggerListener.getName()).isEqualTo("quartzMetrics");
    }

    @Test
    void successfulAndFailedExecutionsAreCountedAndTimed() {
        jobListener.jobWasExecuted(execution("reports", Duration.ofMillis(200)), null);
        jobListener.jobWasExecuted(execution("reports", Duration.ofMillis(300)), new JobExecutionException("boom"));

        assertThat(registry.get("quartz.jobs.executed").tags("scheduler", "app", "job.group", "reports").counter()
            .count()).isEqualTo(2);
        assertThat(registry.get("quartz.jobs.failed").tags("scheduler", "app", "job.group", "reports").counter()
            .count()).isEqualTo(1);
        assertThat(registry.get("quartz.jobs.duration").tags("result", "success").timer().totalTime(
                java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(200);
        assertThat(registry.get("quartz.jobs.duration").tags("result", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    void misfiresAreCounted() {
        Trigger trigger = mock(Trigger.class);
        given(trigger.getKey()).willReturn(TriggerKey.triggerKey("nightly", "batch"));

        triggerListener.triggerMisfired(trigger);
        triggerListener.triggerMisfired(trigger);

        assertThat(registry.get("quartz.triggers.misfired").tags("scheduler", "app", "trigger.group", "batch")
            .counter().count()).isEqualTo(2);
    }

    private static JobExecutionContext execution(String group, Duration runTime) {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDetail detail = mock(JobDetail.class);
        given(detail.getKey()).willReturn(JobKey.jobKey("job", group));
        given(context.getJobDetail()).willReturn(detail);
        given(context.getJobRunTime()).willReturn(runTime);
        return context;
    }

}
