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
package org.quartz.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.quartz.spring.TransactionTestJobs.COUNTS;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.spring.TransactionTestJobs.CountingJob;
import org.quartz.spring.TransactionTestJobs.RecordingMongoTransactionManager;
import org.quartz.spring.boot.autoconfigure.QuartzAutoConfiguration;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.AbstractRedisIT;
import org.quartz.support.SharedContainers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The scheduler stores its state in Redis while jobs run in MongoDB business
 * transactions: rolling back the business transaction does not touch the store, so the job
 * data change of a failed {@code @PersistJobDataAfterExecution} execution is kept (as
 * Quartz always did) while its business document is rolled back.
 * <p>
 * Needs both shared containers, so it cannot extend {@link AbstractMongoIT} or
 * {@link AbstractRedisIT}; it uses their static cleanup helpers instead.
 */
@SpringBootTest(classes = RedisStoreMongoTransactionIT.Config.class,
        properties = { "spring.quartz.job-store.type=redis", "spring.quartz.scheduler.name=redisTxApp" })
class RedisStoreMongoTransactionIT {

    @ServiceConnection
    static final MongoDBContainer MONGO = SharedContainers.mongo();

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = SharedContainers.redis();

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private RecordingMongoTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        TransactionTestJobs.reset();
    }

    @AfterEach
    void cleanUp() {
        AbstractMongoIT.dropAllCollections();
        AbstractRedisIT.flushDatabase();
    }

    @Test
    void storeStateIsIndependentOfTheBusinessTransaction() throws Exception {
        assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(RedisJobStore.class);
        JobDetail job = JobBuilder.newJob(CountingJob.class).withIdentity("redisCounting").usingJobData("count", 0)
            .storeDurably().build();
        scheduler.scheduleJob(job, TriggerBuilder.newTrigger().withIdentity("redisCounting").startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMilliseconds(100).withRepeatCount(1))
            .build());

        // the failed first execution's business document is rolled back, its job data is stored anyway
        await().atMost(Duration.ofSeconds(30)).until(() -> COUNTS.size() == 2);
        assertThat(COUNTS).containsExactly(1, 2);
        await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(scheduler.getJobDetail(job.getKey()).getJobDataMap().getInt("count"))
                .isEqualTo(2));
        assertThat(SpringTransactionJobExecutionIT.businessDocuments("redisCounting")).isEqualTo(1);
        assertThat(transactionManager.begunNames()).containsExactly("DEFAULT.redisCounting", "DEFAULT.redisCounting");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ MongoAutoConfiguration.class, DataMongoAutoConfiguration.class,
            DataRedisAutoConfiguration.class, QuartzAutoConfiguration.class })
    static class Config {

        @Bean
        RecordingMongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
            return new RecordingMongoTransactionManager(databaseFactory);
        }
    }
}
