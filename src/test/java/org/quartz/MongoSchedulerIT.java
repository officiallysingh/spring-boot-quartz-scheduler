/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.quartz;

import org.junit.jupiter.api.AfterEach;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** {@link AbstractSchedulerTest} on a {@code MongoJobStore} in the shared container. */
public class MongoSchedulerIT extends AbstractSchedulerTest {

  @AfterEach
  void cleanStore() {
    AbstractMongoIT.dropAllCollections();
  }

  @Override
  protected Scheduler createScheduler(String name, int threadPoolSize) throws SchedulerException {
    return TestSchedulers.create(
        TestSchedulers.scheduler().name(name + "Scheduler").instanceIdAuto(true), threadPoolSize);
  }
}
