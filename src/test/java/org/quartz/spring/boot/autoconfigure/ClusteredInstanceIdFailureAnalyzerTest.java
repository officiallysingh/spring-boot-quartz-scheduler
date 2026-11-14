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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.quartz.ClusteredInstanceIdException;
import org.springframework.boot.diagnostics.FailureAnalysis;

class ClusteredInstanceIdFailureAnalyzerTest {

  private final ClusteredInstanceIdFailureAnalyzer analyzer =
      new ClusteredInstanceIdFailureAnalyzer();

  @Test
  void explainsTheSharedInstanceIdAndWhatToSet() {
    ClusteredInstanceIdException cause = new ClusteredInstanceIdException("NON_CLUSTERED");

    FailureAnalysis analysis = analyzer.analyze(new IllegalStateException("context failed", cause));

    assertThat(analysis).isNotNull();
    assertThat(analysis.getCause()).isSameAs(cause);
    assertThat(analysis.getDescription())
        .contains("'NON_CLUSTERED'")
        .contains("spring.quartz.job-store.clustered");
    assertThat(analysis.getAction())
        .contains("spring.quartz.scheduler.instance-id")
        .contains("AUTO")
        .contains("${HOSTNAME}");
  }

  @Test
  void isRegistered() throws IOException {
    try (InputStream in = getClass().getResourceAsStream("/META-INF/spring.factories")) {
      assertThat(in).isNotNull();
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .contains(ClusteredInstanceIdFailureAnalyzer.class.getName());
    }
  }
}
