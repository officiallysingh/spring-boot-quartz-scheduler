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
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Calendar;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerListener;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.simpl.RAMJobStore;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spring.SchedulerFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

class QuartzAutoConfigurationTest {

    static final List<String> RESULTS = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
            .withPropertyValues("spring.quartz.job-store.type=memory");

    @BeforeEach
    void clearResults() {
        RESULTS.clear();
    }

    @Test
    void schedulerIsCreatedWithDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Scheduler.class).hasSingleBean(SchedulerFactoryBean.class)
                    .hasSingleBean(QuartzProperties.class);
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.getSchedulerName()).isEqualTo("quartzScheduler");
            assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("NON_CLUSTERED");
            assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(10);
            assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(RAMJobStore.class);
            assertThat(scheduler.isStarted()).isTrue();
        });
    }

    @Test
    void propertiesAreApplied() {
        contextRunner.withPropertyValues("spring.quartz.scheduler.name=app-scheduler",
                "spring.quartz.scheduler.instance-id=node-1", "spring.quartz.scheduler.idle-wait-time=45s",
                "spring.quartz.scheduler.context.region=eu", "spring.quartz.thread-pool.thread-count=3",
                "spring.quartz.job-store.misfire-threshold=90s", "spring.quartz.auto-startup=false")
            .run(context -> {
                Scheduler scheduler = context.getBean(Scheduler.class);
                assertThat(scheduler.getSchedulerName()).isEqualTo("app-scheduler");
                assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("node-1");
                assertThat(scheduler.getContext().get("region")).isEqualTo("eu");
                assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(3);
                assertThat(scheduler.isStarted()).isFalse();
                QuartzProperties properties = context.getBean(QuartzProperties.class);
                assertThat(properties.scheduler().idleWaitTime()).isEqualTo(Duration.ofSeconds(45));
                assertThat(properties.jobStore().misfireThreshold()).isEqualTo(Duration.ofSeconds(90));
            });
    }

    @Test
    void startupDelayIsAppliedAsDuration() {
        contextRunner.withPropertyValues("spring.quartz.startup-delay=1s").run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.isStarted()).isFalse();
            await().atMost(Duration.ofSeconds(10)).until(scheduler::isStarted);
        });
    }

    @Test
    void missingJobStoreTypeFailsWithAClearMessage() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("spring.quartz.job-store.type"));
    }

    @Test
    void persistentStoreTypesRequireTheirConnectionBean() {
        contextRunner.withPropertyValues("spring.quartz.job-store.type=redis")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("no RedisConnectionFactory bean"));
        contextRunner.withPropertyValues("spring.quartz.job-store.type=mongodb")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("no MongoClient bean"));
    }

    @Test
    void invalidSettingsFailFast() {
        contextRunner.withPropertyValues("spring.quartz.scheduler.idle-wait-time=10ms")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("idleWaitTime"));
    }

    @Test
    void customizersAreAppliedInOrder() {
        contextRunner.withUserConfiguration(CustomizerConfiguration.class).run(context -> {
            assertThat(context.getBean(Scheduler.class).getSchedulerName()).isEqualTo("second");
            assertThat(RESULTS).containsExactly("first", "second");
        });
    }

    @Test
    void quartzBeansAreRegistered() {
        // not started, so the one-shot trigger is still there to look up
        contextRunner.withUserConfiguration(QuartzBeansConfiguration.class)
            .withPropertyValues("spring.quartz.auto-startup=false")
            .run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.getJobDetail(JobKey.jobKey("greetingJob"))).isNotNull();
            assertThat(scheduler.getTrigger(TriggerKey.triggerKey("greetingTrigger"))).isNotNull();
            assertThat(scheduler.getCalendar("weekdays")).isInstanceOf(WeeklyCalendar.class);
            assertThat(scheduler.getListenerManager().getJobListener("jobListener")).isNotNull();
            assertThat(scheduler.getListenerManager().getTriggerListener("triggerListener")).isNotNull();
            assertThat(scheduler.getListenerManager().getSchedulerListeners())
                .contains(context.getBean(SchedulerListener.class));
            assertThat(RESULTS).contains("plugin myPlugin initialized");
        });
    }

    @Test
    void jobsAreAutowiredBySpringBeanJobFactory() {
        contextRunner.withUserConfiguration(QuartzBeansConfiguration.class).run(context ->
            await().atMost(Duration.ofSeconds(10)).until(() -> RESULTS.stream().anyMatch(r -> r.startsWith("hello quartz"))));
    }

    @Test
    void virtualThreadsRunTheJobs() {
        contextRunner.withUserConfiguration(QuartzBeansConfiguration.class)
            .withPropertyValues("spring.quartz.thread-pool.virtual-threads=true")
            .run(context -> {
                await().atMost(Duration.ofSeconds(10)).until(() -> RESULTS.stream().anyMatch(r -> r.startsWith("hello quartz")));
                assertThat(RESULTS).contains("hello quartz on virtual thread");
            });
    }

    @Test
    void lifecycleFollowsTheContext() {
        contextRunner.run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.isStarted()).isTrue();
            context.stop();
            assertThat(scheduler.isInStandbyMode()).isTrue();
            context.start();
            assertThat(scheduler.isInStandbyMode()).isFalse();
            context.close();
            assertThat(scheduler.isShutdown()).isTrue();
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesAScheduler() {
        contextRunner.withBean("myScheduler", Scheduler.class, () -> mock(Scheduler.class)).run(context -> {
            assertThat(context).hasSingleBean(Scheduler.class).doesNotHaveBean(SchedulerFactoryBean.class);
            assertThat(context).hasBean("myScheduler");
        });
    }

    @Test
    void backsOffWhenTheApplicationDefinesASchedulerFactoryBean() {
        contextRunner.withUserConfiguration(OwnSchedulerFactoryBeanConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(SchedulerFactoryBean.class);
            assertThat(context.getBean(Scheduler.class).getSchedulerName()).isEqualTo("own");
        });
    }

    @Test
    void builtInPluginsAreOptIn() {
        contextRunner.run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.getListenerManager().getJobListener("jobHistory")).isNull();
            assertThat(scheduler.getListenerManager().getTriggerListener("triggerHistory")).isNull();
            assertThat(scheduler.getListenerManager().getTriggerListener("jobInterruptMonitor")).isNull();
        });
        contextRunner.withPropertyValues("spring.quartz.plugins.job-history.enabled=true",
                "spring.quartz.plugins.trigger-history.enabled=true",
                "spring.quartz.plugins.job-interrupt-monitor.enabled=true")
            .run(context -> {
                Scheduler scheduler = context.getBean(Scheduler.class);
                assertThat(scheduler.getListenerManager().getJobListener("jobHistory")).isNotNull();
                assertThat(scheduler.getListenerManager().getTriggerListener("triggerHistory")).isNotNull();
                assertThat(scheduler.getListenerManager().getTriggerListener("jobInterruptMonitor")).isNotNull();
            });
    }

    @Test
    void pluginBeanClashingWithABuiltInPluginFails() {
        contextRunner.withPropertyValues("spring.quartz.plugins.job-history.enabled=true")
            .withBean("jobHistory", SchedulerPlugin.class, RecordingPlugin::new)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("clashes with the built-in plugin"));
    }

    @Test
    void autoInstanceIdUsesTheInstanceIdGeneratorBean() {
        contextRunner.withPropertyValues("spring.quartz.scheduler.instance-id=AUTO")
            .withUserConfiguration(ClusteredStoreConfiguration.class)
            .withBean(InstanceIdGenerator.class, () -> () -> "generated-id")
            .run(context -> assertThat(context.getBean(Scheduler.class).getSchedulerInstanceId())
                .isEqualTo("generated-id"));
    }

    @Test
    void sysPropInstanceIdReadsTheSystemProperty() {
        System.setProperty("org.quartz.scheduler.instanceId", "from-system-property");
        try {
            contextRunner.withPropertyValues("spring.quartz.scheduler.instance-id=SYS_PROP")
                .withUserConfiguration(ClusteredStoreConfiguration.class)
                .run(context -> assertThat(context.getBean(Scheduler.class).getSchedulerInstanceId())
                    .isEqualTo("from-system-property"));
        }
        finally {
            System.clearProperty("org.quartz.scheduler.instanceId");
        }
    }

    public static class GreetingService {
        String greet() {
            return "hello quartz";
        }
    }

    public static class GreetingJob implements Job {
        @Autowired
        private GreetingService service;

        @Override
        public void execute(JobExecutionContext context) {
            RESULTS.add(service.greet() + (Thread.currentThread().isVirtual() ? " on virtual thread" : ""));
        }
    }

    static class RecordingPlugin implements SchedulerPlugin {
        @Override
        public void initialize(String name, Scheduler scheduler, ClassLoadHelper loadHelper) {
            RESULTS.add("plugin " + name + " initialized");
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }
    }

    static class ClusteredRAMJobStore extends RAMJobStore {
        @Override
        public boolean isClustered() {
            return true;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QuartzBeansConfiguration {

        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }

        @Bean
        JobDetail greetingJob() {
            return JobBuilder.newJob(GreetingJob.class).withIdentity("greetingJob").storeDurably().build();
        }

        @Bean
        Trigger greetingTrigger(JobDetail greetingJob) {
            return TriggerBuilder.newTrigger().withIdentity("greetingTrigger").forJob(greetingJob).startNow().build();
        }

        @Bean
        Calendar weekdays() {
            return new WeeklyCalendar();
        }

        @Bean
        JobListenerSupport jobListener() {
            return new JobListenerSupport() {
                @Override
                public String getName() {
                    return "jobListener";
                }
            };
        }

        @Bean
        TriggerListenerSupport triggerListener() {
            return new TriggerListenerSupport() {
                @Override
                public String getName() {
                    return "triggerListener";
                }
            };
        }

        @Bean
        SchedulerListener schedulerListener() {
            return new SchedulerListenerSupport() {
            };
        }

        @Bean
        SchedulerPlugin myPlugin() {
            return new RecordingPlugin();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfiguration {

        @Bean
        @Order(2)
        SchedulerFactoryBeanCustomizer secondCustomizer() {
            return bean -> {
                RESULTS.add("second");
                bean.setSchedulerName("second");
            };
        }

        @Bean
        @Order(1)
        SchedulerFactoryBeanCustomizer firstCustomizer() {
            return bean -> {
                RESULTS.add("first");
                bean.setSchedulerName("first");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClusteredStoreConfiguration {

        @Bean
        SchedulerFactoryBeanCustomizer clusteredStore() {
            return bean -> bean.setJobStore(new ClusteredRAMJobStore());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnSchedulerFactoryBeanConfiguration {

        @Bean
        SchedulerFactoryBean ownScheduler() {
            SchedulerFactoryBean bean = new SchedulerFactoryBean();
            bean.setSchedulerName("own");
            return bean;
        }
    }

}
