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
package org.quartz.support;

import java.util.Set;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Base class for integration tests against the JVM-wide MongoDB container
 * (single-node replica set, so transactions work).
 * <p>
 * Spring tests get the connection through {@link ServiceConnection @ServiceConnection};
 * plain store tests use {@link #mongoClient()} / {@link #connectionString()}. After each
 * test every collection outside the {@code admin}, {@code config} and {@code local}
 * databases is dropped; the container itself is never restarted.
 */
public abstract class AbstractMongoIT {

    private static final Set<String> SYSTEM_DATABASES = Set.of("admin", "config", "local");

    @ServiceConnection
    protected static final MongoDBContainer MONGO = SharedContainers.mongo();

    /** Connection string for the replica set, database {@code test}. */
    protected static String connectionString() {
        return MONGO.getReplicaSetUrl();
    }

    /** A client shared by all tests in the JVM; do not close it. */
    protected static MongoClient mongoClient() {
        return ClientHolder.CLIENT;
    }

    @AfterEach
    void dropCollections() {
        for (String databaseName : mongoClient().listDatabaseNames()) {
            if (!SYSTEM_DATABASES.contains(databaseName)) {
                MongoDatabase database = mongoClient().getDatabase(databaseName);
                for (String collection : database.listCollectionNames()) {
                    database.getCollection(collection).drop();
                }
            }
        }
    }

    private static final class ClientHolder {
        static final MongoClient CLIENT = MongoClients.create(connectionString());
    }
}
