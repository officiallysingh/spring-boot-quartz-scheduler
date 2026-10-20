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
package org.quartz.impl.mongodb;

import java.time.Duration;

import org.quartz.spi.JobStore;
import org.quartz.store.AbstractClusteredJobStoreContractTest;
import org.quartz.support.AbstractMongoIT;

/**
 * The clustered job store contract against MongoDB: two scheduler instances on the same
 * {@code mongo:8.0} container.
 */
class MongoClusteredJobStoreIT extends AbstractClusteredJobStoreContractTest {

    @Override
    protected JobStore createJobStore(String instanceId) {
        return new MongoJobStore(AbstractMongoIT.mongoClient(), MongoJobStoreIT.DATABASE, clusteredSettings(),
                new MongoJobStoreSettings("qrtz_", Duration.ofSeconds(2)));
    }

    @Override
    protected void cleanStore() {
        AbstractMongoIT.dropAllCollections();
    }
}
