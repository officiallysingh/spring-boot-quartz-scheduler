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
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.support.AbstractMongoIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code spring.quartz.job-store.type=mongodb} end to end: the auto-configured scheduler stores its
 * jobs in the {@code @ServiceConnection} MongoDB and runs them.
 */
@SpringBootTest(
    classes = QuartzMongoAutoConfigurationIT.Config.class,
    properties = {
      "spring.quartz.job-store.type=mongodb",
      "spring.quartz.scheduler.name=mongoApp",
      "spring.quartz.job-store.mongodb.collection-prefix=app_"
    })
class QuartzMongoAutoConfigurationIT extends AbstractMongoIT {

  static final Set<String> EXECUTED = ConcurrentHashMap.newKeySet();

  @Autowired private Scheduler scheduler;

  public static class RecordingJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      EXECUTED.add(context.getJobDetail().getKey().getName());
    }
  }

  @Test
  void jobsAreStoredInMongoAndRun() throws Exception {
    assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(MongoJobStore.class);
    assertThat(scheduler.getSchedulerName()).isEqualTo("mongoApp");

    await().atMost(Duration.ofSeconds(30)).until(() -> EXECUTED.contains("mongoJob"));
    assertThat(mongoClient().getDatabase("test").getCollection("app_jobs").countDocuments())
        .isEqualTo(1);
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    MongoAutoConfiguration.class,
    DataMongoAutoConfiguration.class,
    QuartzAutoConfiguration.class
  })
  static class Config {

    @Bean
    JobDetail mongoJob() {
      return JobBuilder.newJob(RecordingJob.class).withIdentity("mongoJob").storeDurably().build();
    }

    @Bean
    Trigger mongoTrigger(JobDetail mongoJob) {
      return TriggerBuilder.newTrigger()
          .forJob(mongoJob)
          .withIdentity("mongoTrigger")
          .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever())
          .startNow()
          .build();
    }
  }
}
