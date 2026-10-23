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
import static org.quartz.spring.TransactionTestJobs.IN_TRANSACTION;
import static org.quartz.spring.TransactionTestJobs.OUTCOME;
import static org.quartz.spring.TransactionTestJobs.RUNTIME_EXCEPTION;
import static org.quartz.spring.TransactionTestJobs.SUCCEED;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.spring.TransactionTestJobs.CompletionRecorder;
import org.quartz.spring.TransactionTestJobs.PlainJob;
import org.quartz.spring.TransactionTestJobs.RecordingMongoTransactionManager;
import org.quartz.spring.boot.autoconfigure.QuartzAutoConfiguration;
import org.quartz.support.AbstractMongoIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

/**
 * {@code spring.quartz.transaction.enabled=true}: jobs without {@code @ExecuteInTransaction} run in
 * a transaction of the unique transaction manager.
 */
@SpringBootTest(
    classes = SpringTransactionGlobalSwitchIT.Config.class,
    properties = {"spring.quartz.job-store.type=mongodb", "spring.quartz.transaction.enabled=true"})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class SpringTransactionGlobalSwitchIT extends AbstractMongoIT {

  @Autowired private Scheduler scheduler;

  @Autowired private CompletionRecorder recorder;

  @Autowired private RecordingMongoTransactionManager transactionManager;

  @BeforeEach
  void reset() {
    TransactionTestJobs.reset();
  }

  @Test
  void jobWithoutAnnotationIsRolledBack() throws Exception {
    run("failingPlain", RUNTIME_EXCEPTION);

    assertThat(IN_TRANSACTION).containsEntry("failingPlain", true);
    assertThat(transactionManager.begunNames()).containsExactly("DEFAULT.failingPlain");
    assertThat(SpringTransactionJobExecutionIT.businessDocuments("failingPlain")).isZero();
  }

  @Test
  void jobWithoutAnnotationIsCommitted() throws Exception {
    run("succeedingPlain", SUCCEED);

    assertThat(IN_TRANSACTION).containsEntry("succeedingPlain", true);
    assertThat(SpringTransactionJobExecutionIT.businessDocuments("succeedingPlain")).isEqualTo(1);
  }

  private void run(String name, String outcome) throws Exception {
    scheduler.scheduleJob(
        JobBuilder.newJob(PlainJob.class).withIdentity(name).usingJobData(OUTCOME, outcome).build(),
        TriggerBuilder.newTrigger().withIdentity(name).startNow().build());
    await().atMost(Duration.ofSeconds(30)).until(() -> recorder.executed.containsKey(name));
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    MongoAutoConfiguration.class,
    DataMongoAutoConfiguration.class,
    QuartzAutoConfiguration.class
  })
  static class Config {

    @Bean
    RecordingMongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
      return new RecordingMongoTransactionManager(databaseFactory);
    }

    @Bean
    CompletionRecorder completionRecorder() {
      return new CompletionRecorder();
    }
  }
}
