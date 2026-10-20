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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Docker / Testcontainers wiring: starts the shared MongoDB and Redis
 * containers and pings both. No MongoDB or Redis client library is on the
 * classpath yet (DECISIONS.md D-008), so MongoDB is pinged through {@code mongosh}
 * inside the container and Redis over a raw RESP socket from the host.
 */
class ContainersSmokeIT {

    @Test
    void mongoAnswersPingAndRunsAsReplicaSet() throws Exception {
        MongoDBContainer mongo = SharedContainers.mongo();

        assertThat(mongo.isRunning()).isTrue();
        assertThat(mongosh(mongo, "db.runCommand({ping: 1}).ok")).isEqualTo("1");
        assertThat(mongosh(mongo, "rs.status().ok")).isEqualTo("1");
        assertThat(mongo.getReplicaSetUrl()).startsWith("mongodb://");

        // the mapped port is reachable from the host
        try (Socket socket = new Socket(mongo.getHost(), mongo.getFirstMappedPort())) {
            assertThat(socket.isConnected()).isTrue();
        }
    }

    @Test
    void redisAnswersPing() throws IOException {
        GenericContainer<?> redis = SharedContainers.redis();

        assertThat(redis.isRunning()).isTrue();
        try (Socket socket = new Socket(redis.getHost(), redis.getMappedPort(SharedContainers.REDIS_PORT))) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(in.readLine()).isEqualTo("+PONG");
        }
    }

    private static String mongosh(MongoDBContainer mongo, String expression) throws Exception {
        ExecResult result = mongo.execInContainer("mongosh", "--quiet", "--eval", expression);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().trim();
    }
}
