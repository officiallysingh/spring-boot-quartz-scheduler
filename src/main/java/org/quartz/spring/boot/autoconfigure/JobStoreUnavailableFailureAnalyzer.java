/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.quartz.spring.boot.autoconfigure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Explains a {@link JobStoreUnavailableException} at startup: what is missing and how to configure
 * a MongoDB or Redis job store.
 */
class JobStoreUnavailableFailureAnalyzer
    extends AbstractFailureAnalyzer<JobStoreUnavailableException> {

  private static final String MONGODB_ACTION =
      "add spring-boot-starter-data-mongodb and configure "
          + "spring.mongodb.* (a replica set, if job transactions are used)";

  private static final String REDIS_ACTION =
      "add spring-boot-starter-data-redis and configure spring.data.redis.*";

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, JobStoreUnavailableException cause) {
    return new FailureAnalysis(cause.getMessage(), action(cause), cause);
  }

  private static String action(JobStoreUnavailableException cause) {
    JobStoreType type = cause.getConfiguredType();
    if (type != null) {
      return "Make a "
          + JobStoreConfigurations.requirement(type)
          + " available: "
          + ((type == JobStoreType.MONGODB) ? MONGODB_ACTION : REDIS_ACTION)
          + ". Or set 'spring.quartz.job-store.type' to a store whose connection is configured.";
    }
    if (cause.getAvailableTypes().isEmpty()) {
      return "Quartz needs a persistent job store. Either "
          + MONGODB_ACTION
          + " and set 'spring.quartz.job-store.type=mongodb', or "
          + REDIS_ACTION
          + " and set 'spring.quartz.job-store.type=redis'.";
    }
    return "Set 'spring.quartz.job-store.type' to one of: "
        + String.join(
            ", ",
            cause.getAvailableTypes().stream().map(JobStoreUnavailableException::name).toList())
        + ".";
  }
}
