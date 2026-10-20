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

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class for integration tests against the JVM-wide Redis container.
 * <p>
 * Spring tests get the connection through {@link ServiceConnection @ServiceConnection};
 * plain store tests use {@link #redisUri()} / {@link #redisConnection()}. After each test
 * the default database is cleared with {@code FLUSHDB}; the container itself is never
 * restarted.
 */
public abstract class AbstractRedisIT {

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = SharedContainers.redis();

    protected static RedisURI redisUri() {
        return RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(SharedContainers.REDIS_PORT));
    }

    /** A connection shared by all tests in the JVM; do not close it. */
    protected static StatefulRedisConnection<String, String> redisConnection() {
        return ConnectionHolder.CONNECTION;
    }

    @AfterEach
    void flushDb() {
        redisConnection().sync().flushdb();
    }

    private static final class ConnectionHolder {
        static final RedisClient CLIENT = RedisClient.create(redisUri());
        static final StatefulRedisConnection<String, String> CONNECTION = CLIENT.connect();
    }
}
