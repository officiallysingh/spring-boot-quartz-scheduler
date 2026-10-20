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

import org.quartz.spi.JobStore;
import org.quartz.store.AbstractClusteredJobStoreContractTest;

/**
 * Runs the clustered contract kit against the persistent store engine over an in-memory
 * delegate shared by the instances (no containers).
 */
class ClusteredPersistentJobStoreEngineTest extends AbstractClusteredJobStoreContractTest {

    private final InMemoryStoreDelegate.Data data = new InMemoryStoreDelegate.Data();

    @Override
    protected JobStore createJobStore(String instanceId) {
        return new InMemoryPersistentJobStore(data, clusteredSettings());
    }

    @Override
    protected void cleanStore() {
        synchronized (data) {
            new InMemoryStoreDelegate(data, null).clearAllSchedulingData();
            data.schedulers.clear();
            data.locks.clear();
        }
    }
}
