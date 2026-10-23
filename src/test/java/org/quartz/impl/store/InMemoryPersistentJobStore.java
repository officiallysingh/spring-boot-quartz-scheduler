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
package org.quartz.impl.store;

import java.time.Duration;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.spi.ClassLoadHelper;

/**
 * {@link PersistentJobStore} over {@link InMemoryStoreDelegate}: exercises the engine, including
 * the lease lock, without containers.
 */
class InMemoryPersistentJobStore extends PersistentJobStore {

  private final InMemoryStoreDelegate.Data data;

  InMemoryPersistentJobStore(InMemoryStoreDelegate.Data data, JobStoreSettings settings) {
    super(settings);
    this.data = data;
  }

  @Override
  protected StoreDelegate createDelegate(String schedulerName, ClassLoadHelper loadHelper) {
    return new InMemoryStoreDelegate(data, loadHelper);
  }

  @Override
  protected StoreLock createClusterLock(String schedulerName, String owner) {
    return new LeaseStoreLock(
        owner, Duration.ofSeconds(5), getSettings().acquireTriggersWithinLockRetry()) {
      @Override
      protected boolean tryAcquire(String lockName, String owner, Duration leaseTime) {
        long now = System.currentTimeMillis();
        String[] lease = new String[] {owner, String.valueOf(now + leaseTime.toMillis())};
        String[] current =
            data.locks.compute(
                lockName,
                (name, existing) ->
                    (existing == null
                            || existing[0].equals(owner)
                            || Long.parseLong(existing[1]) < now)
                        ? lease
                        : existing);
        return current == lease;
      }

      @Override
      protected void releaseLease(String lockName, String owner) {
        data.locks.computeIfPresent(
            lockName, (name, existing) -> existing[0].equals(owner) ? null : existing);
      }
    };
  }
}
