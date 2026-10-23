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

import java.time.Duration;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * A typical application using the library: a job and its trigger declared as beans, the job store
 * chosen in {@code application.yml}, and the business data in MongoDB.
 */
@SpringBootApplication
public class SampleApplication {

  static final Duration INTERVAL = Duration.ofMillis(250);

  @Bean
  JobDetail sampleJobDetail() {
    return JobBuilder.newJob(SampleJob.class).withIdentity("sampleJob").storeDurably().build();
  }

  @Bean
  Trigger sampleTrigger(JobDetail sampleJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(sampleJobDetail)
        .withIdentity("sampleTrigger")
        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withInterval(INTERVAL).repeatForever())
        .build();
  }

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
  }
}
