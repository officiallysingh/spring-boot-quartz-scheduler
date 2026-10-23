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
package org.quartz.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

class RemovedPropertiesExceptionTest {

  @Test
  void currentPropertiesPass() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.quartz.thread-pool.thread-count", "20")
            .withProperty("spring.quartz.job-store.mongodb.collection-prefix", "jobs_");
    assertThatNoException().isThrownBy(() -> RemovedPropertiesException.check(environment));
  }

  @Test
  void removedPropertiesAndGroupsFailInRelaxedForms() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("spring.quartz.jobStore.mongodb.database", "quartz")
            .withProperty("spring.quartz.plugins.jobHistory.enabled", "true")
            .withProperty("spring.quartz.job-store.use-properties", "true");

    RemovedPropertiesException failure =
        catchThrowableOfType(
            RemovedPropertiesException.class, () -> RemovedPropertiesException.check(environment));

    assertThat(failure.getProperties())
        .containsExactly(
            "spring.quartz.job-store.mongodb.database",
            "spring.quartz.job-store.use-properties",
            "spring.quartz.plugins");
    assertThat(failure.getActions().get(0)).contains("spring.mongodb.database");
    assertThat(failure.getActions().get(2)).contains("LoggingJobHistoryPlugin");
  }

  @Test
  void theFailureAnalysisListsWhatToDo() {
    RemovedPropertiesException failure =
        catchThrowableOfType(
            RemovedPropertiesException.class,
            () ->
                RemovedPropertiesException.check(
                    new MockEnvironment()
                        .withProperty("spring.quartz.job-store.mongodb.database", "quartz")));

    FailureAnalysis analysis = new RemovedPropertiesFailureAnalyzer().analyze(failure);

    assertThat(analysis.getDescription()).contains("spring.quartz.job-store.mongodb.database");
    assertThat(analysis.getAction())
        .contains("spring.quartz.job-store.mongodb.database: the store uses the application's");
  }
}
