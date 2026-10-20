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

import org.quartz.spi.JobStore;
import org.quartz.store.AbstractClusteredJobStoreContractTest;
import org.quartz.support.AbstractRedisIT;

/**
 * The clustered job store contract against Redis: two scheduler instances on the same
 * {@code redis:7.4} container.
 */
class RedisClusteredJobStoreIT extends AbstractClusteredJobStoreContractTest {

    @Override
    protected JobStore createJobStore(String instanceId) {
        return new RedisJobStore(AbstractRedisIT.redisTemplate(), clusteredSettings(),
                new RedisJobStoreSettings("quartz:", Duration.ofSeconds(2)));
    }

    @Override
    protected void cleanStore() {
        AbstractRedisIT.flushDatabase();
    }
}
