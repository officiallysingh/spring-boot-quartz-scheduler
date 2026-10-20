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

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.support.AbstractRedisIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code spring.quartz.job-store.type=redis} end to end: the auto-configured scheduler
 * stores its jobs in the {@code @ServiceConnection} Redis and runs them.
 */
@SpringBootTest(classes = QuartzRedisAutoConfigurationIT.Config.class,
        properties = { "spring.quartz.job-store.type=redis", "spring.quartz.scheduler.name=redisApp",
                "spring.quartz.job-store.redis.key-prefix=app:" })
class QuartzRedisAutoConfigurationIT extends AbstractRedisIT {

    static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();

    @Autowired
    private Scheduler scheduler;

    public static class RecordingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            EXECUTED.add(context.getJobDetail().getKey().getName());
        }
    }

    @Test
    void jobsAreStoredInRedisAndRun() throws Exception {
        assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(RedisJobStore.class);
        assertThat(scheduler.getSchedulerName()).isEqualTo("redisApp");

        await().atMost(Duration.ofSeconds(30)).until(() -> EXECUTED.contains("redisJob"));
        assertThat(redisConnection().sync().exists("app:{redisApp}:job:DEFAULT:redisJob")).isEqualTo(1L);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ DataRedisAutoConfiguration.class, QuartzAutoConfiguration.class })
    static class Config {

        @Bean
        JobDetail redisJob() {
            return JobBuilder.newJob(RecordingJob.class).withIdentity("redisJob").storeDurably().build();
        }

        @Bean
        Trigger redisTrigger(JobDetail redisJob) {
            return TriggerBuilder.newTrigger().forJob(redisJob).withIdentity("redisTrigger")
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever()).startNow().build();
        }
    }
}
