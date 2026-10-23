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
package org.quartz.impl.redis;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis-specific settings of {@link RedisJobStore}.
 *
 * @param keyPrefix prefix of the keys the store uses (followed by {@code {schedName}:})
 * @param lockTimeout how long a cluster lock is held before it expires
 */
public record RedisJobStoreSettings(String keyPrefix, Duration lockTimeout) {

  public RedisJobStoreSettings {
    Objects.requireNonNull(keyPrefix, "keyPrefix");
    Objects.requireNonNull(lockTimeout, "lockTimeout");
    if (lockTimeout.toMillis() < 1) {
      throw new IllegalArgumentException("lockTimeout must be at least 1ms, was " + lockTimeout);
    }
  }

  public static RedisJobStoreSettings defaults() {
    return new RedisJobStoreSettings("quartz:", Duration.ofSeconds(30));
  }
}
