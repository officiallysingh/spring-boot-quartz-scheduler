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
package org.quartz.impl.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/**
 * A store thread interrupted inside a transaction (as at scheduler shutdown) must still abort it:
 * an open transaction keeps its locks on the server until its lifetime (60 s) expires.
 */
class MongoTransactionInterruptIT extends AbstractMongoIT {

  private MongoJobStore store;

  @BeforeEach
  void setUp() throws SchedulerException {
    store = TestSchedulers.mongoStore(JobStoreSettings.defaults());
    store.setInstanceName("InterruptTest");
    store.setInstanceId("NON_CLUSTERED");
    ClassLoader classLoader = getClass().getClassLoader();
    store.initialize(classLoader, null);
  }

  @AfterEach
  void tearDown() {
    Thread.interrupted();
    store.shutdown();
  }

  @Test
  void anInterruptedOperationAbortsItsTransaction() {
    // the job data is Java-serialized after the existence check has opened the transaction
    JobDetail job =
        JobBuilder.newJob(NoOpJob.class)
            .withIdentity("interrupted")
            .storeDurably()
            .usingJobData(new JobDataMap(Map.of("value", new InterruptingValue())))
            .build();

    assertThatThrownBy(() -> store.storeJob(job, false)).isInstanceOf(SchedulerException.class);
    assertThat(Thread.interrupted()).as("interrupt flag restored").isTrue();

    assertThat(openTransactions()).isEmpty();
  }

  private static List<Document> openTransactions() {
    return mongoClient()
        .getDatabase("admin")
        .aggregate(
            List.of(
                new Document(
                    "$currentOp", new Document("idleSessions", true).append("allUsers", true)),
                new Document("$match", new Document("transaction", new Document("$exists", true)))))
        .into(new ArrayList<>());
  }

  /** Interrupts the serializing thread, so the store's next server call fails. */
  static final class InterruptingValue implements Serializable {

    private void writeObject(ObjectOutputStream out) throws IOException {
      Thread.currentThread().interrupt();
      out.defaultWriteObject();
    }
  }

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }
}
