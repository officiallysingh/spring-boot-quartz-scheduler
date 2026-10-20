/*
 * Copyright 2026 the spring-boot-quartz-scheduler authors.
 *
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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MongoDB and Redis containers shared by every integration test in the JVM.
 * Each container starts on first use and is stopped by Testcontainers' Ryuk
 * when the JVM exits; tests clean state themselves instead of restarting it.
 */
public final class SharedContainers {

    public static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:8.0");
    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4");
    public static final int REDIS_PORT = 6379;

    private SharedContainers() {
    }

    /** Single-node replica set, so multi-document transactions work. */
    public static MongoDBContainer mongo() {
        return MongoHolder.CONTAINER;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.CONTAINER;
    }

    private static final class MongoHolder {
        static final MongoDBContainer CONTAINER = new MongoDBContainer(MONGO_IMAGE)
                .withReplicaSet(); // not the default since Testcontainers 2

        static {
            CONTAINER.start();
        }
    }

    private static final class RedisHolder {
        // AOF persistence, as recommended for the Redis job store
        static final GenericContainer<?> CONTAINER = new GenericContainer<>(REDIS_IMAGE)
                .withCommand("redis-server", "--appendonly", "yes")
                .withExposedPorts(REDIS_PORT);

        static {
            CONTAINER.start();
        }
    }
}
