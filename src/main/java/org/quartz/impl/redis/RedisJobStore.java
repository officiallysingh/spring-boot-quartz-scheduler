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
import java.util.List;
import java.util.Objects;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.store.LeaseStoreLock;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.store.StoreDelegate;
import org.quartz.impl.store.StoreLock;
import org.quartz.spi.ClassLoadHelper;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A job store keeping all scheduling data in Redis.
 *
 * <p>Every key of a scheduler is {@code keyPrefix{schedName}:...}: the {@code {schedName}} hash tag
 * keeps them in one slot, so the store works on Redis Cluster. Every multi-key change is a Lua
 * script ({@link RedisScripts}), so it is atomic; clustered stores lock with {@code SET NX PX}
 * leases released by compare-and-delete.
 *
 * <p>Durability is Redis's: run Redis with AOF persistence ({@code appendonly yes}, {@code
 * appendfsync everysec}); with RDB snapshots only, schedule changes made since the last snapshot
 * are lost when Redis restarts.
 */
public class RedisJobStore extends PersistentJobStore {

  private final StringRedisTemplate redis;

  private final RedisJobStoreSettings redisSettings;

  @SuppressWarnings("NullAway.Init")
  private RedisKeys keys;

  /**
   * Create a Redis job store.
   *
   * @param redis the template the store talks to Redis through; its connection factory is not
   *     closed
   * @param settings the store-independent settings
   * @param redisSettings the Redis-specific settings
   */
  public RedisJobStore(
      StringRedisTemplate redis, JobStoreSettings settings, RedisJobStoreSettings redisSettings) {
    super(settings);
    this.redis = Objects.requireNonNull(redis, "redis");
    this.redisSettings = Objects.requireNonNull(redisSettings, "redisSettings");
  }

  /**
   * The Redis-specific settings.
   *
   * @return the settings
   */
  public RedisJobStoreSettings getRedisSettings() {
    return redisSettings;
  }

  @Override
  protected StoreDelegate createDelegate(String schedulerName, ClassLoadHelper loadHelper) {
    keys = new RedisKeys(redisSettings.keyPrefix(), schedulerName);
    return new RedisStoreDelegate(redis, keys, loadHelper);
  }

  @Override
  protected StoreLock createClusterLock(String schedulerName, String owner) {
    return new RedisLeaseLock(
        redis,
        keys,
        owner,
        redisSettings.lockTimeout(),
        getSettings().acquireTriggersWithinLockRetry());
  }

  /** {@code SET key owner NX PX lease}; released by compare-and-delete. */
  static final class RedisLeaseLock extends LeaseStoreLock {

    private final StringRedisTemplate redis;

    private final RedisKeys keys;

    RedisLeaseLock(
        StringRedisTemplate redis,
        RedisKeys keys,
        String owner,
        Duration leaseTime,
        Duration retryInterval) {
      super(owner, leaseTime, retryInterval);
      this.redis = redis;
      this.keys = keys;
    }

    @Override
    protected boolean tryAcquire(String lockName, String owner, Duration leaseTime) {
      Long result =
          redis.execute(
              RedisScripts.ACQUIRE_LOCK,
              List.of(keys.lock(lockName)),
              owner,
              String.valueOf(leaseTime.toMillis()));
      return result != null && result == 1L;
    }

    @Override
    protected void releaseLease(String lockName, String owner) {
      redis.execute(RedisScripts.RELEASE_LOCK, List.of(keys.lock(lockName)), owner);
    }
  }
}
