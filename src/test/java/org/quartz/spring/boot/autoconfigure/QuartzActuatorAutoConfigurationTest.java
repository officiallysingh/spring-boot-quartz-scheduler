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
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.spring.boot.actuate.endpoint.QuartzEndpoint;
import org.quartz.spring.boot.actuate.health.QuartzHealthIndicator;
import org.quartz.spring.boot.actuate.metrics.QuartzMetrics;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Conditions of the health and metrics auto-configurations (the endpoint's are in
 * {@link QuartzEndpointAutoConfigurationTest}).
 */
class QuartzActuatorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuartzEndpointAutoConfiguration.class,
                    QuartzHealthContributorAutoConfiguration.class, QuartzMetricsAutoConfiguration.class))
            .withBean(Scheduler.class, () -> mock(Scheduler.class));

    @Test
    void healthIndicatorIsRegisteredAsQuartz() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(QuartzHealthIndicator.class)
            .hasBean("quartzHealthIndicator"));
    }

    @Test
    void healthIndicatorCanBeDisabled() {
        contextRunner.withPropertyValues("management.health.quartz.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(QuartzHealthIndicator.class));
    }

    @Test
    void healthIndicatorNeedsAScheduler() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuartzHealthContributorAutoConfiguration.class))
            .run(context -> assertThat(context).doesNotHaveBean(QuartzHealthIndicator.class));
    }

    @Test
    void metricsNeedAMeterRegistry() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(QuartzMetrics.class));
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .run(context -> assertThat(context).hasSingleBean(QuartzMetrics.class).hasBean("quartzMetrics"));
    }

    @Test
    void metricsCanBeDisabled() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("management.metrics.quartz.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(QuartzMetrics.class));
    }

    @Test
    void everythingBacksOffWithoutActuatorAndMicrometer() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("management.endpoints.web.exposure.include=quartz")
            .withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate",
                    "org.springframework.boot.health", "io.micrometer"))
            .run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(QuartzEndpoint.class)
                .doesNotHaveBean(HealthIndicator.class)
                .doesNotHaveBean(QuartzMetrics.class));
    }

}
