/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
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
 *
 */
package org.quartz.simpl;

import org.quartz.spi.JobStore;
import org.quartz.store.AbstractJobStoreContractTest;

/**
 * Binds the in-memory store to the standalone job store contract. Each store is
 * independent, so there is nothing to clean.
 */
public class RAMJobStoreTest extends AbstractJobStoreContractTest {

    @Override
    protected JobStore createJobStore(String instanceId) {
        return new RAMJobStore();
    }

    @Override
    protected void cleanStore() {
    }
}
