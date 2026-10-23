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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class JobStoreUnavailableFailureAnalyzerTest {

  private final JobStoreUnavailableFailureAnalyzer analyzer =
      new JobStoreUnavailableFailureAnalyzer();

  @Test
  void noStoreAtAll() {
    FailureAnalysis analysis = analyze(new JobStoreUnavailableException(null, List.of()));

    assertThat(analysis.getDescription())
        .contains("No Quartz job store configured")
        .contains("spring.quartz.job-store.type");
    assertThat(analysis.getAction())
        .contains("spring-boot-starter-data-mongodb")
        .contains("spring.quartz.job-store.type=mongodb")
        .contains("spring-boot-starter-data-redis")
        .contains("spring.quartz.job-store.type=redis");
  }

  @Test
  void configuredTypeWithoutItsConnectionBean() {
    FailureAnalysis analysis =
        analyze(
            new JobStoreUnavailableException(JobStoreType.REDIS, List.of(JobStoreType.MONGODB)));

    assertThat(analysis.getDescription())
        .contains("'spring.quartz.job-store.type' is redis")
        .contains("no RedisConnectionFactory bean");
    assertThat(analysis.getAction())
        .contains("spring-boot-starter-data-redis")
        .contains("spring.data.redis.*");
  }

  @Test
  void severalStoresWithoutAType() {
    FailureAnalysis analysis =
        analyze(
            new JobStoreUnavailableException(
                null, List.of(JobStoreType.MONGODB, JobStoreType.REDIS)));

    assertThat(analysis.getDescription())
        .contains("Several Quartz job stores are available (mongodb, redis)");
    assertThat(analysis.getAction())
        .isEqualTo("Set 'spring.quartz.job-store.type' to one of: mongodb, redis.");
  }

  @Test
  void causeIsFoundInsideABeanCreationFailure() {
    JobStoreUnavailableException cause = new JobStoreUnavailableException(null, List.of());

    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException("context failed", cause));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getCause()).isSameAs(cause);
  }

  @Test
  void isRegisteredSoStartupFailuresAreExplained(CapturedOutput output) {
    SpringApplication application = new SpringApplication(QuartzOnlyConfiguration.class);
    application.setWebApplicationType(WebApplicationType.NONE);

    assertThatThrownBy(application::run).hasRootCauseInstanceOf(JobStoreUnavailableException.class);
    assertThat(output)
        .contains("APPLICATION FAILED TO START")
        .contains("No Quartz job store configured")
        .contains("Quartz needs a persistent job store");
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration(QuartzAutoConfiguration.class)
  static class QuartzOnlyConfiguration {}

  private FailureAnalysis analyze(JobStoreUnavailableException failure) {
    FailureAnalysis analysis = analyzer.analyze(failure);
    assertThat(analysis).isNotNull();
    return analysis;
  }
}
