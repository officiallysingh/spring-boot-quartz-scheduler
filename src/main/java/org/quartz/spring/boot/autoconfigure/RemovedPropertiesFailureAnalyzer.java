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

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Lists the removed Quartz properties the application still sets, and what to do instead. */
class RemovedPropertiesFailureAnalyzer extends AbstractFailureAnalyzer<RemovedPropertiesException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, RemovedPropertiesException cause) {
    String description =
        "These Quartz properties no longer exist; binding would ignore them and change behaviour"
            + " silently:\n"
            + cause.getProperties().stream()
                .map((property) -> "    - " + property)
                .collect(Collectors.joining("\n"));
    String action =
        IntStream.range(0, cause.getProperties().size())
            .mapToObj(
                (i) -> "    - " + cause.getProperties().get(i) + ": " + cause.getActions().get(i))
            .collect(Collectors.joining("\n", "Update the configuration:\n", ""));
    return new FailureAnalysis(description, action, cause);
  }
}
