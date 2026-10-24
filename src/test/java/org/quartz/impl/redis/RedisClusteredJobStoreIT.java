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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobPersistenceException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.store.PersistentJobStore;
import org.quartz.impl.store.StoreDelegate;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.store.AbstractClusteredJobStoreContractTest;
import org.quartz.support.AbstractRedisIT;

/**
 * The clustered job store contract against Redis: two scheduler instances on the same {@code
 * redis:7.4} container.
 */
class RedisClusteredJobStoreIT extends AbstractClusteredJobStoreContractTest {

  /** When set, the next store created applies its first ACQUIRED state change and then fails. */
  private boolean nextStoreFailsAfterAcquiring;

  @Override
  protected JobStore createJobStore(String instanceId) {
    RedisJobStoreSettings redisSettings =
        new RedisJobStoreSettings("quartz:", Duration.ofSeconds(2));
    if (nextStoreFailsAfterAcquiring) {
      nextStoreFailsAfterAcquiring = false;
      return new FailingAfterAcquiringRedisJobStore(redisSettings);
    }
    return new RedisJobStore(AbstractRedisIT.redisTemplate(), clusteredSettings(), redisSettings);
  }

  /**
   * Redis runs the two writes of a trigger acquisition (fired-trigger record, WAITING -> ACQUIRED)
   * separately. An instance stopped between them, for example interrupted at shutdown after the
   * server applied the state change, used to leave the trigger ACQUIRED without a fired-trigger
   * record: cluster recovery never freed it, and it never fired again.
   */
  @Test
  void aTriggerWhoseAcquisitionWasCutShortIsFreedWhenTheNodeFails() throws Exception {
    nextStoreFailsAfterAcquiring = true;
    PersistentJobStore nodeA = startStore("node-a");
    PersistentJobStore nodeB = startStore("node-b");
    OperableTrigger trigger =
        (OperableTrigger)
            TriggerBuilder.newTrigger()
                .withIdentity("cutShortTrigger")
                .forJob("cutShort")
                .startAt(Instant.now().minusMillis(100))
                .build();
    trigger.computeFirstFireTime(null); // as the scheduler does before storing a trigger
    nodeA.storeJobAndTrigger(
        JobBuilder.newJob(CountingJob.class).withIdentity("cutShort").build(), trigger);

    // node-a's state change reaches Redis, but the call fails before the acquisition completes
    assertThatThrownBy(
            () -> nodeA.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .isInstanceOf(JobPersistenceException.class);
    assertThat(nodeB.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO))
        .as("held as ACQUIRED by node-a")
        .isEmpty();

    nodeA.shutdown(); // node-a stops checking in: node-b recovers it

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                nodeB.acquireNextTriggers(Instant.now().plusSeconds(1), 1, Duration.ZERO).stream()
                    .anyMatch((t) -> t.getKey().getName().equals("cutShortTrigger")));
  }

  /** A store whose delegate applies its first ACQUIRED state change, then throws. */
  private static final class FailingAfterAcquiringRedisJobStore extends RedisJobStore {

    private final AtomicBoolean failed = new AtomicBoolean();

    FailingAfterAcquiringRedisJobStore(RedisJobStoreSettings redisSettings) {
      super(AbstractRedisIT.redisTemplate(), clusteredSettings(), redisSettings);
    }

    @Override
    protected StoreDelegate createDelegate(String schedulerName, ClassLoader classLoader) {
      StoreDelegate delegate = super.createDelegate(schedulerName, classLoader);
      return (StoreDelegate)
          Proxy.newProxyInstance(
              StoreDelegate.class.getClassLoader(),
              new Class<?>[] {StoreDelegate.class},
              (proxy, method, args) -> {
                Object result;
                try {
                  result = method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                  throw ex.getCause();
                }
                if (method.getName().equals("updateTriggerStateFromOtherStates")
                    && PersistentJobStore.STATE_ACQUIRED.equals(args[1])
                    && failed.compareAndSet(false, true)) {
                  throw new IllegalStateException("Redis command interrupted (simulated)");
                }
                return result;
              });
    }
  }

  @Override
  protected void cleanStore() {
    AbstractRedisIT.flushDatabase();
  }
}
