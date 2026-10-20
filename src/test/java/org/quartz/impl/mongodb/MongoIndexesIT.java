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

import java.util.ArrayList;
import java.util.List;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.simpl.CascadingClassLoadHelper;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.store.AbstractJobStoreContractTest.SampleSignaler;
import org.quartz.support.AbstractMongoIT;

/**
 * The MongoDB store creates its collections' indexes on startup and detects that the
 * replica set supports transactions.
 */
class MongoIndexesIT extends AbstractMongoIT {

    private static List<Document> indexes(MongoDatabase database, String collection) {
        return database.getCollection(collection).listIndexes().into(new ArrayList<>());
    }

    private static List<Object> keys(List<Document> indexes) {
        return indexes.stream().map(index -> (Object) index.get("key", Document.class)).toList();
    }

    @Test
    void indexesExistAfterStartup() throws Exception {
        MongoJobStore store = new MongoJobStore(mongoClient(), "quartz_indexes", JobStoreSettings.defaults(),
                new MongoJobStoreSettings("idx_", MongoJobStoreSettings.defaults().lockTimeout()));
        store.setInstanceName("IndexScheduler");
        store.setInstanceId("node-1");
        ClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();
        store.initialize(loadHelper, new SampleSignaler());

        MongoDatabase database = mongoClient().getDatabase("quartz_indexes");
        List<Document> triggerIndexes = indexes(database, "idx_triggers");
        assertThat(keys(triggerIndexes)).contains(
                new Document("schedName", 1).append("group", 1).append("name", 1),
                new Document("schedName", 1).append("state", 1).append("nextFireTime", 1).append("priority", -1),
                new Document("schedName", 1).append("jobGroup", 1).append("jobName", 1));
        assertThat(triggerIndexes).anySatisfy(index -> {
            assertThat(index.get("key")).isEqualTo(new Document("schedName", 1).append("group", 1).append("name", 1));
            assertThat(index.getBoolean("unique")).isTrue();
        });
        assertThat(keys(indexes(database, "idx_jobs")))
            .contains(new Document("schedName", 1).append("group", 1).append("name", 1));
        assertThat(keys(indexes(database, "idx_calendars"))).contains(new Document("schedName", 1).append("name", 1));
        assertThat(keys(indexes(database, "idx_fired_triggers")))
            .contains(new Document("schedName", 1).append("fireInstanceId", 1),
                    new Document("schedName", 1).append("instanceId", 1));
        assertThat(keys(indexes(database, "idx_paused_groups")))
            .contains(new Document("schedName", 1).append("kind", 1).append("group", 1));
        assertThat(keys(indexes(database, "idx_schedulers")))
            .contains(new Document("schedName", 1).append("instanceId", 1));
        assertThat(indexes(database, "idx_locks")).anySatisfy(index -> {
            assertThat(index.get("key")).isEqualTo(new Document("expiresAt", 1));
            assertThat(((Number) index.get("expireAfterSeconds")).longValue()).isZero();
        });
        assertThat(store.isTransactional()).isTrue();
        store.shutdown();
    }
}
