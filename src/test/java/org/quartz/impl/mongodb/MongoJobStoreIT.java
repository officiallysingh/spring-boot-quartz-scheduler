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

import org.quartz.impl.config.JobStoreSettings;
import org.quartz.spi.JobStore;
import org.quartz.store.AbstractJobStoreContractTest;
import org.quartz.support.AbstractMongoIT;

/**
 * The standalone job store contract against MongoDB (shared {@code mongo:8.0} replica set,
 * so store operations run in transactions).
 */
class MongoJobStoreIT extends AbstractJobStoreContractTest {

    static final String DATABASE = "quartz_it";

    @Override
    protected JobStore createJobStore(String instanceId) {
        return new MongoJobStore(AbstractMongoIT.mongoClient(), DATABASE, JobStoreSettings.defaults(),
                MongoJobStoreSettings.defaults());
    }

    @Override
    protected void cleanStore() {
        AbstractMongoIT.dropAllCollections();
    }
}
