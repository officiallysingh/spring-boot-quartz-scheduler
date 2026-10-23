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
package org.quartz.spring.boot.actuate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.quartz.spring.boot.actuate.ActuatorHttp.Response;
import org.quartz.support.SharedContainers;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * {@code /actuator/health/quartz} turns {@code DOWN} when the store's MongoDB goes away. Uses its
 * own container (not the shared one), which the test stops.
 */
@Testcontainers
@SpringBootTest(
    classes = QuartzHealthDownIT.App.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.quartz.job-store.type=mongodb",
      "management.endpoints.web.exposure.include=health",
      "management.endpoint.health.show-details=always"
    })
@DirtiesContext
class QuartzHealthDownIT {

  @Container @ServiceConnection
  static final MongoDBContainer MONGO = new MongoDBContainer(SharedContainers.MONGO_IMAGE);

  @LocalServerPort private int port;

  @Test
  void healthIsDownOnceTheStoreIsUnreachable() {
    ActuatorHttp http = new ActuatorHttp(port);
    Response up = http.get("/health/quartz");
    assertThat(up.status()).isEqualTo(200);
    assertThat(up.body().at("/status").asString()).isEqualTo("UP");

    MONGO.stop();

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> http.get("/health/quartz").status() == 503);
    Response down = http.get("/health/quartz");
    assertThat(down.body().at("/status").asString()).isEqualTo("DOWN");
    assertThat(down.body().at("/details/error").asString()).isNotBlank();
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration"
      })
  static class App {

    // fail fast once the server is gone instead of waiting 30 s per store call
    @Bean
    MongoClientSettingsBuilderCustomizer shortTimeouts() {
      return (settings) ->
          settings
              .applyToClusterSettings(
                  (cluster) -> cluster.serverSelectionTimeout(2, TimeUnit.SECONDS))
              .applyToSocketSettings(
                  (socket) ->
                      socket.connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS));
    }
  }
}
