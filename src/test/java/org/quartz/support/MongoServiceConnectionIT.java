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

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

/**
 * Proves {@link AbstractMongoIT}: the Spring context connects through {@code @ServiceConnection} to
 * the shared container, and collections are dropped between tests.
 */
@SpringBootTest(classes = MongoServiceConnectionIT.Config.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoServiceConnectionIT extends AbstractMongoIT {

  @Autowired private MongoClient springClient;

  @Test
  @Order(1)
  void springContextWritesToTheSharedContainer() {
    springClient.getDatabase("test").getCollection("smoke").insertOne(new Document("n", 1));

    assertThat(mongoClient().getDatabase("test").getCollection("smoke").countDocuments())
        .isEqualTo(1);
  }

  @Test
  @Order(2)
  void collectionsAreDroppedBetweenTests() {
    assertThat(mongoClient().getDatabase("test").listCollectionNames()).doesNotContain("smoke");
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(MongoAutoConfiguration.class)
  static class Config {}
}
