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

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Proves {@link AbstractRedisIT}: the Spring context connects through {@code @ServiceConnection} to
 * the shared container, and the database is flushed between tests.
 */
@SpringBootTest(classes = RedisServiceConnectionIT.Config.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisServiceConnectionIT extends AbstractRedisIT {

  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  @Order(1)
  void springContextWritesToTheSharedContainer() {
    redisTemplate.opsForValue().set("smoke", "1");

    assertThat(redisConnection().sync().get("smoke")).isEqualTo("1");
  }

  @Test
  @Order(2)
  void databaseIsFlushedBetweenTests() {
    assertThat(redisConnection().sync().dbsize()).isZero();
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
  static class Config {}
}
