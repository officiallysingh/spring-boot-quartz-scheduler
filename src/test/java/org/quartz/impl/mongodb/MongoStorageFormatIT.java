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
import static org.bson.codecs.configuration.CodecRegistries.fromCodecs;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.store.AbstractJobStoreContractTest.SampleSignaler;
import org.quartz.support.AbstractMongoIT;

/**
 * The MongoDB store keeps instants as BSON dates and durations as int64 milliseconds, whatever the
 * client's codec registry does with {@code java.time} types.
 */
class MongoStorageFormatIT extends AbstractMongoIT {

  private static final String DATABASE = "quartz_format";

  private static final String SCHED = "FormatScheduler";

  /** An application codec writing instants as numbers, as some applications do. */
  static final class InstantAsNumberCodec implements Codec<Instant> {
    @Override
    public void encode(BsonWriter writer, Instant value, EncoderContext context) {
      writer.writeInt64(value.toEpochMilli());
    }

    @Override
    public Instant decode(BsonReader reader, DecoderContext context) {
      return Instant.ofEpochMilli(reader.readInt64());
    }

    @Override
    public Class<Instant> getEncoderClass() {
      return Instant.class;
    }
  }

  /** An application codec writing durations as ISO strings. */
  static final class DurationAsStringCodec implements Codec<Duration> {
    @Override
    public void encode(BsonWriter writer, Duration value, EncoderContext context) {
      writer.writeString(value.toString());
    }

    @Override
    public Duration decode(BsonReader reader, DecoderContext context) {
      return Duration.parse(reader.readString());
    }

    @Override
    public Class<Duration> getEncoderClass() {
      return Duration.class;
    }
  }

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  private MongoClient client;

  private PersistentJobStore store;

  @BeforeEach
  void setUp() {
    client =
        MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString()))
                .codecRegistry(
                    fromRegistries(
                        fromCodecs(new InstantAsNumberCodec(), new DurationAsStringCodec()),
                        MongoClientSettings.getDefaultCodecRegistry()))
                .build());
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.shutdown();
    }
    client.close();
  }

  private PersistentJobStore startStore() throws Exception {
    store =
        new MongoJobStore(
            client,
            DATABASE,
            JobStoreSettings.builder()
                .clustered(true)
                .clusterCheckinInterval(Duration.ofSeconds(1))
                .build(),
            new MongoJobStoreSettings("fmt_", Duration.ofSeconds(5)));
    store.setInstanceName(SCHED);
    store.setInstanceId("node-1");
    store.initialize(getClass().getClassLoader(), new SampleSignaler());
    store.schedulerStarted();
    return store;
  }

  private MongoDatabase database() {
    return mongoClient().getDatabase(DATABASE);
  }

  private Document only(String collection) {
    List<Document> documents =
        database().getCollection(collection).find().into(new java.util.ArrayList<>());
    assertThat(documents).hasSize(1);
    return documents.getFirst();
  }

  @Test
  void instantsAreBsonDatesAndDurationsMillisWhateverTheClientsCodecs() throws Exception {
    PersistentJobStore store = startStore();
    Instant start = Instant.now().minusSeconds(1);
    JobDetail job = JobBuilder.newJob(NoOpJob.class).withIdentity("job").build();
    OperableTrigger newTrigger =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("trigger")
                .forJob(job)
                .startAt(start)
                .endAt(start.plus(Duration.ofDays(1)))
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule().withIntervalInHours(1).repeatForever())
                .build();
    newTrigger.computeFirstFireTime(null); // as the scheduler does before storing
    store.storeJobAndTrigger(job, newTrigger);

    Document trigger = only("fmt_triggers");
    assertThat(trigger.get("nextFireTime")).isInstanceOf(Date.class);
    assertThat(trigger.get("startTime")).isInstanceOf(Date.class);
    assertThat(trigger.get("endTime")).isInstanceOf(Date.class);
    assertThat(((Date) trigger.get("startTime")).toInstant())
        .isEqualTo(store.retrieveTrigger(TriggerKey.triggerKey("trigger")).getStartTime());
    assertThat(trigger.get("repeatInterval")).isEqualTo(3_600_000L);

    // acquiring and firing writes the fired-trigger record and takes the trigger lock
    List<OperableTrigger> acquired =
        store.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO);
    assertThat(acquired).hasSize(1);
    store.triggersFired(acquired);
    Document fired = only("fmt_fired_triggers");
    assertThat(fired.get("firedTime")).isInstanceOf(Date.class);
    assertThat(fired.get("scheduledTime")).isInstanceOf(Date.class);
    assertThat(only("fmt_triggers").get("previousFireTime")).isInstanceOf(Date.class);

    Document scheduler = only("fmt_schedulers");
    assertThat(scheduler.get("lastCheckin")).isInstanceOf(Date.class);
    assertThat(scheduler.get("checkinInterval")).isEqualTo(1_000L);
    // a held lease (released ones are deleted)
    store.getLock().obtain(PersistentJobStore.LOCK_TRIGGER_ACCESS);
    try {
      assertThat(only("fmt_locks").get("expiresAt")).isInstanceOf(Date.class);
    } finally {
      store.getLock().release(PersistentJobStore.LOCK_TRIGGER_ACCESS);
    }

    // and the store reads its own format through the hostile client
    assertThat(store.getExecutingJobs())
        .singleElement()
        .satisfies(
            executing ->
                assertThat(executing.firedTime())
                    .isEqualTo(((Date) fired.get("firedTime")).toInstant()));
    assertThat(store.getSchedulerInstances())
        .singleElement()
        .satisfies(
            instance -> {
              assertThat(instance.alive()).isTrue();
              assertThat(instance.checkinInterval()).isEqualTo(Duration.ofSeconds(1));
            });
  }
}
