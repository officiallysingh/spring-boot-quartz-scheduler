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

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.spring.SchedulerFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link QuartzAutoConfiguration} checks that never create a job store: back-off and the
 * fail-fast paths. Everything else is in {@link QuartzAutoConfigurationIT}.
 */
class QuartzAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class));

    @Test
    void noJobStoreTypeAndNoConnectionBeanFailsFast() {
        contextRunner.run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().isInstanceOf(JobStoreUnavailableException.class)
            .hasMessageContaining("No Quartz job store configured")
            .hasMessageContaining("spring.quartz.job-store.type"));
    }

    @Test
    void persistentStoreTypesRequireTheirConnectionBean() {
        contextRunner.withPropertyValues("spring.quartz.job-store.type=redis")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(JobStoreUnavailableException.class)
                .hasMessageContaining("no RedisConnectionFactory bean"));
        contextRunner.withPropertyValues("spring.quartz.job-store.type=mongodb")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().isInstanceOf(JobStoreUnavailableException.class)
                .hasMessageContaining("no MongoClient bean"));
    }

    @Test
    void theMemoryStoreTypeIsGone() {
        contextRunner.withPropertyValues("spring.quartz.job-store.type=memory")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("memory"));
    }

    @Test
    void backsOffWhenTheApplicationDefinesAScheduler() {
        contextRunner.withBean("myScheduler", Scheduler.class, () -> mock(Scheduler.class)).run(context -> {
            assertThat(context).hasSingleBean(Scheduler.class).doesNotHaveBean(SchedulerFactoryBean.class);
            assertThat(context).hasBean("myScheduler");
        });
    }

}
