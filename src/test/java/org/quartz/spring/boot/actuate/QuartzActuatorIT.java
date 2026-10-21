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
package org.quartz.spring.boot.actuate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CronScheduleBuilder;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.spring.boot.actuate.ActuatorHttp.Response;
import org.quartz.support.AbstractMongoIT;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * {@code /actuator/quartz}, {@code /actuator/health/quartz} and the Quartz meters of an
 * application on the shared MongoDB container ({@code @ServiceConnection}). One context per
 * test: the base class drops the store's collections after each test.
 */
@SpringBootTest(classes = QuartzActuatorIT.App.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = { "spring.quartz.job-store.type=mongodb", "spring.quartz.scheduler.name=actuatorApp",
                "management.endpoints.web.exposure.include=quartz,health",
                "management.endpoint.health.show-details=always" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QuartzActuatorIT extends AbstractMongoIT {

    static final Instant FAR_FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    private ActuatorHttp http;

    @BeforeEach
    void setUp() {
        http = new ActuatorHttp(port);
        EXECUTED.clear();
    }

    @Test
    void groupsAreListed() {
        JsonNode body = ok(http.get("/quartz"));

        assertThat(texts(body.at("/jobs/groups"))).containsExactlyInAnyOrder("reports", "maintenance");
        assertThat(texts(body.at("/triggers/groups"))).containsExactlyInAnyOrder("reports", "maintenance");
    }

    @Test
    void jobsAndTriggersAreListedByGroup() {
        JsonNode jobs = ok(http.get("/quartz/jobs"));
        assertThat(texts(jobs.at("/groups/reports/jobs"))).containsExactly("dailyReport");
        assertThat(texts(jobs.at("/groups/maintenance/jobs"))).containsExactly("cleanup");

        JsonNode triggers = ok(http.get("/quartz/triggers"));
        assertThat(triggers.at("/groups/reports/paused").asBoolean()).isFalse();
        assertThat(texts(triggers.at("/groups/reports/triggers"))).containsExactlyInAnyOrder("reportCron",
                "reportSimple");
        assertThat(texts(triggers.at("/groups/maintenance/triggers"))).containsExactlyInAnyOrder("cleanupDaily",
                "cleanupMonthly");
    }

    @Test
    void groupSummariesDescribeEveryTriggerType() {
        JsonNode jobGroup = ok(http.get("/quartz/jobs/reports"));
        assertThat(jobGroup.at("/group").asString()).isEqualTo("reports");
        assertThat(jobGroup.at("/jobs/dailyReport/className").asString()).isEqualTo(RecordingJob.class.getName());

        JsonNode reports = ok(http.get("/quartz/triggers/reports"));
        assertThat(reports.at("/triggers/cron/reportCron/expression").asString()).isEqualTo("0 0 6 * * ?");
        assertThat(reports.at("/triggers/cron/reportCron/timeZone").asString()).isEqualTo("Europe/Paris");
        assertThat(reports.at("/triggers/simple/reportSimple/interval").asLong()).isEqualTo(3_600_000L);
        assertThat(reports.at("/triggers/simple/reportSimple/nextFireTime").asString())
            .isEqualTo("2099-01-01T00:00:00Z");

        JsonNode maintenance = ok(http.get("/quartz/triggers/maintenance"));
        assertThat(maintenance.at("/triggers/dailyTimeInterval/cleanupDaily/interval").asLong())
            .isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(maintenance.at("/triggers/dailyTimeInterval/cleanupDaily/startTimeOfDay").asString())
            .isEqualTo("02:00:00");
        assertThat(maintenance.at("/triggers/calendarInterval/cleanupMonthly/interval").asLong())
            .isEqualTo(ChronoMonth.MILLIS);
        assertThat(maintenance.at("/triggers/calendarInterval/cleanupMonthly/timeZone").asString())
            .isEqualTo("UTC");
    }

    @Test
    void jobDetailIsSanitizedByDefault() {
        JsonNode job = ok(http.get("/quartz/jobs/reports/dailyReport"));

        assertThat(job.at("/group").asString()).isEqualTo("reports");
        assertThat(job.at("/name").asString()).isEqualTo("dailyReport");
        assertThat(job.at("/durable").asBoolean()).isTrue();
        assertThat(job.at("/data/password").asString()).isEqualTo("******");
        assertThat(job.at("/triggers")).hasSize(2);
    }

    @Test
    void triggerDetail() {
        JsonNode trigger = ok(http.get("/quartz/triggers/reports/reportSimple"));

        assertThat(trigger.at("/state").asString()).isEqualTo("NORMAL");
        assertThat(trigger.at("/type").asString()).isEqualTo("simple");
        assertThat(trigger.at("/startTime").asString()).isEqualTo("2099-01-01T00:00:00Z");
        assertThat(trigger.at("/simple/interval").asLong()).isEqualTo(3_600_000L);
        assertThat(trigger.at("/simple/repeatCount").asInt()).isEqualTo(-1);
    }

    @Test
    void unknownJobsTriggersAndCategoriesAreRejected() {
        assertThat(http.get("/quartz/jobs/reports/missing").status()).isEqualTo(404);
        assertThat(http.get("/quartz/triggers/reports/missing").status()).isEqualTo(404);
        assertThat(http.get("/quartz/jobs/missing").status()).isEqualTo(404);
        assertThat(http.get("/quartz/calendars").status()).isEqualTo(400);
    }

    @Test
    void aJobCanBeTriggeredAndIsMeasured() {
        Response response = http.post("/quartz/jobs/maintenance/cleanup", "{\"state\":\"running\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().at("/className").asString()).isEqualTo(RecordingJob.class.getName());
        assertThat(response.body().at("/triggerTime").isMissingNode()).isFalse();
        await().atMost(Duration.ofSeconds(30)).until(() -> EXECUTED.contains("cleanup"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(meterRegistry.get("quartz.jobs.executed")
            .tags("scheduler", "actuatorApp", "job.group", "maintenance").counter().count()).isEqualTo(1));
        assertThat(meterRegistry.get("quartz.jobs.duration").tags("result", "success").timer().count()).isEqualTo(1);

        assertThat(http.post("/quartz/jobs/maintenance/cleanup", "{\"state\":\"paused\"}").status()).isEqualTo(400);
        assertThat(http.post("/quartz/jobs/maintenance/missing", "{\"state\":\"running\"}").status()).isEqualTo(404);
    }

    @Test
    void healthIsUp() {
        JsonNode health = ok(http.get("/health/quartz"));

        assertThat(health.at("/status").asString()).isEqualTo("UP");
        assertThat(health.at("/details/scheduler").asString()).isEqualTo("actuatorApp");
        assertThat(health.at("/details/state").asString()).isEqualTo("started");
        assertThat(health.at("/details/jobStore").asString()).isEqualTo(MongoJobStore.class.getName());
    }

    private static JsonNode ok(Response response) {
        assertThat(response.status()).as("HTTP status").isEqualTo(200);
        return response.body();
    }

    private static java.util.List<String> texts(JsonNode array) {
        assertThat(array.isArray()).as("array: " + array).isTrue();
        return array.valueStream().map(JsonNode::asString).toList();
    }

    /** One calendar month, as the endpoint reports a calendar interval (ChronoUnit estimate). */
    static final class ChronoMonth {
        static final long MILLIS = java.time.temporal.ChronoUnit.MONTHS.getDuration().toMillis();
    }

    public static class RecordingJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            EXECUTED.add(context.getJobDetail().getKey().getName());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration" })
    static class App {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        JobDetail dailyReport() {
            return JobBuilder.newJob(RecordingJob.class).withIdentity("dailyReport", "reports")
                .usingJobData("password", "secret").storeDurably().build();
        }

        @Bean
        JobDetail cleanup() {
            return JobBuilder.newJob(RecordingJob.class).withIdentity("cleanup", "maintenance").storeDurably().build();
        }

        @Bean
        Trigger reportCron(JobDetail dailyReport) {
            return TriggerBuilder.newTrigger().forJob(dailyReport).withIdentity("reportCron", "reports")
                .withSchedule(CronScheduleBuilder.cronSchedule("0 0 6 * * ?").inTimeZone(ZoneId.of("Europe/Paris")))
                .startAt(FAR_FUTURE).build();
        }

        @Bean
        Trigger reportSimple(JobDetail dailyReport) {
            return TriggerBuilder.newTrigger().forJob(dailyReport).withIdentity("reportSimple", "reports")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withInterval(Duration.ofHours(1)).repeatForever())
                .startAt(FAR_FUTURE).build();
        }

        @Bean
        Trigger cleanupDaily(JobDetail cleanup) {
            return TriggerBuilder.newTrigger().forJob(cleanup).withIdentity("cleanupDaily", "maintenance")
                .withSchedule(DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                    .startingDailyAt(org.quartz.TimeOfDay.hourAndMinuteOfDay(2, 0)).withIntervalInMinutes(30))
                .startAt(FAR_FUTURE).build();
        }

        @Bean
        Trigger cleanupMonthly(JobDetail cleanup) {
            return TriggerBuilder.newTrigger().forJob(cleanup).withIdentity("cleanupMonthly", "maintenance")
                .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInMonths(1)
                    .inTimeZone(ZoneId.of("UTC")))
                .startAt(FAR_FUTURE).build();
        }
    }
}
