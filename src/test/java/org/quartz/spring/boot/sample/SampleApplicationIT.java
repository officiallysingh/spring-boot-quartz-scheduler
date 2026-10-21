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
package org.quartz.spring.boot.sample;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerMetaData;
import org.quartz.TriggerKey;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.spi.JobStore;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.AbstractRedisIT;
import org.quartz.support.SharedContainers;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoCollection;

/**
 * Runs {@link SampleApplication} as an application would: {MongoDB, Redis} job store &times;
 * {standalone, 2-node cluster} &times; {every job in a transaction, no transaction}. The two
 * nodes of a cluster are two application contexts sharing one container.
 */
class SampleApplicationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String DATABASE = "sample";

    private static final MongoDBContainer MONGO = SharedContainers.mongo();

    private static final GenericContainer<?> REDIS = SharedContainers.redis();

    private static final JobKey JOB = JobKey.jobKey("sampleJob");

    private static final TriggerKey TRIGGER = TriggerKey.triggerKey("sampleTrigger");

    private final List<ConfigurableApplicationContext> nodes = new ArrayList<>();

    private final String runId = UUID.randomUUID().toString();

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (String store : List.of("mongodb", "redis")) {
            for (boolean clustered : List.of(false, true)) {
                for (boolean transaction : List.of(true, false)) {
                    cases.add(Arguments.of(store, clustered, transaction));
                }
            }
        }
        return cases.stream();
    }

    @AfterEach
    void stopNodes() {
        nodes.forEach(ConfigurableApplicationContext::close);
        AbstractMongoIT.dropAllCollections();
        AbstractRedisIT.flushDatabase();
    }

    @ParameterizedTest(name = "{0}, clustered={1}, transaction={2}")
    @MethodSource("cases")
    void jobDeclaredAsBeanRunsOnTheConfiguredStore(String store, boolean clustered, boolean transaction)
            throws Exception {
        // the application's collection exists before jobs run: concurrent transactions cannot create it
        AbstractMongoIT.mongoClient().getDatabase(DATABASE).createCollection(SampleJob.EXECUTIONS);
        Scheduler first = start(store, clustered, transaction);
        Scheduler second = clustered ? start(store, true, transaction) : null;

        Class<? extends JobStore> storeClass = "mongodb".equals(store) ? MongoJobStore.class : RedisJobStore.class;
        for (Scheduler scheduler : nodes(first, second)) {
            SchedulerMetaData metaData = scheduler.getMetaData();
            assertThat(metaData.getJobStoreClass()).isEqualTo(storeClass);
            assertThat(metaData.isJobStoreClustered()).isEqualTo(clustered);
            assertThat(scheduler.getJobDetail(JOB)).isNotNull();
            assertThat(scheduler.getTrigger(TRIGGER)).isNotNull();
        }

        await().atMost(TIMEOUT).until(() -> executions().countDocuments(eq("runId", runId)) >= 3);
        assertExecutions(transaction);

        if (clustered) {
            assertThat(second.getSchedulerName()).isEqualTo(first.getSchedulerName());
            assertThat(second.getSchedulerInstanceId()).isNotEqualTo(first.getSchedulerInstanceId());

            // fail over: once the first node is gone, the second one keeps firing the trigger
            nodes.remove(0).close();
            Instant stopped = Instant.now();
            Bson bySecond = and(eq("runId", runId), eq("instanceId", second.getSchedulerInstanceId()),
                    gt("scheduledFireTime", Date.from(stopped)));
            await().atMost(TIMEOUT).until(() -> executions().countDocuments(bySecond) >= 3);
            assertExecutions(transaction);
        }
    }

    /** Each execution ran in a transaction exactly when enabled, and no fire time ran twice. */
    private void assertExecutions(boolean transaction) {
        Map<Object, Integer> perFireTime = new HashMap<>();
        for (Document execution : executions().find(eq("runId", runId))) {
            assertThat(execution.getBoolean("inTransaction")).isEqualTo(transaction);
            perFireTime.merge(execution.get("scheduledFireTime"), 1, Integer::sum);
        }
        assertThat(perFireTime).isNotEmpty().allSatisfy((fireTime, count) -> assertThat(count).isOne());
    }

    private Scheduler start(String store, boolean clustered, boolean transaction) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("sample.run-id", runId);
        properties.put("spring.mongodb.uri", MONGO.getReplicaSetUrl(DATABASE));
        properties.put("spring.data.redis.host", REDIS.getHost());
        properties.put("spring.data.redis.port", REDIS.getMappedPort(SharedContainers.REDIS_PORT));
        properties.put("spring.quartz.scheduler.name", "sample");
        properties.put("spring.quartz.job-store.type", store);
        properties.put("spring.quartz.transaction.enabled", transaction);
        properties.put("spring.quartz.wait-for-jobs-to-complete-on-shutdown", true);
        if (clustered) {
            properties.put("spring.quartz.scheduler.instance-id", "AUTO");
            properties.put("spring.quartz.job-store.clustered", true);
            properties.put("spring.quartz.job-store.cluster-checkin-interval", "1s");
            properties.put("spring.quartz.job-store.cluster-checkin-misfire-threshold", "1s");
        }
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SampleApplication.class)
            .web(WebApplicationType.NONE)
            .properties(properties)
            .run();
        nodes.add(context);
        return context.getBean(Scheduler.class);
    }

    private static List<Scheduler> nodes(Scheduler first, Scheduler second) {
        return (second != null) ? List.of(first, second) : List.of(first);
    }

    private static MongoCollection<Document> executions() {
        return AbstractMongoIT.mongoClient().getDatabase(DATABASE).getCollection(SampleJob.EXECUTIONS);
    }
}
