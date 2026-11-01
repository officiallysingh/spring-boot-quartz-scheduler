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

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerPage;
import org.quartz.TriggerQuery;
import org.quartz.TriggerSort;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.OperableTrigger;
import org.quartz.store.AbstractJobStoreContractTest.SampleSignaler;
import org.quartz.support.AbstractMongoIT;

/** {@code getTriggers} reads one page of triggers with one query and one count. */
class MongoTriggerQueryIT extends AbstractMongoIT {

  /** Store reads; session commands (commit, abort) are not counted. */
  private static final Set<String> READS = Set.of("find", "aggregate", "count", "distinct");

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  private final List<String> commands = new CopyOnWriteArrayList<>();

  private MongoClient client;

  private MongoJobStore store;

  @BeforeEach
  void setUp() throws Exception {
    client =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString()))
                .addCommandListener(
                    new CommandListener() {
                      @Override
                      public void commandStarted(CommandStartedEvent event) {
                        commands.add(event.getCommandName());
                      }
                    })
                .build());
    store =
        new MongoJobStore(
            client,
            "quartz_query",
            JobStoreSettings.defaults(),
            new MongoJobStoreSettings("q_", Duration.ofSeconds(5)));
    store.setInstanceName("QueryScheduler");
    store.setInstanceId("node-1");
    store.initialize(getClass().getClassLoader(), new SampleSignaler());
  }

  @AfterEach
  void tearDown() {
    store.shutdown();
    client.close();
  }

  @Test
  void aPageOfAThousandTriggersTakesTwoStoreReads() throws Exception {
    JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("auction").build();
    Instant base = Instant.parse("2036-01-01T00:00:00Z");
    Set<Trigger> triggers = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      OperableTrigger trigger =
          (OperableTrigger)
              TriggerBuilder.newTrigger()
                  .withIdentity("trigger-" + i, "auctions")
                  .forJob(job)
                  .startAt(base.plusSeconds((i * 7919L) % 100_000))
                  .build();
      trigger.computeFirstFireTime(null);
      triggers.add(trigger);
    }
    store.storeJobsAndTriggers(Map.of(job, triggers), false);

    commands.clear();
    TriggerPage page =
        store.getTriggers(
            new TriggerQuery(
                GroupMatcher.anyTriggerGroup(),
                Set.of(),
                TriggerSort.NEXT_FIRE_TIME,
                false,
                0,
                20));

    assertThat(page.total()).isEqualTo(1000);
    assertThat(page.content()).hasSize(20);
    assertThat(page.content().getFirst().trigger().getNextFireTime()).isEqualTo(base);
    assertThat(commands.stream().filter(READS::contains)).containsExactly("find", "aggregate");
  }
}
