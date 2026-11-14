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

import org.quartz.ClusteredInstanceIdException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Explains a {@link ClusteredInstanceIdException} at startup: a clustered job store needs an
 * instance id that differs on every instance.
 */
class ClusteredInstanceIdFailureAnalyzer
    extends AbstractFailureAnalyzer<ClusteredInstanceIdException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, ClusteredInstanceIdException cause) {
    return new FailureAnalysis(
        cause.getMessage()
            + " ('spring.quartz.job-store.clustered' is true). Instances with the same id would"
            + " recover each other's running jobs and run them twice.",
        "Set 'spring.quartz.scheduler.instance-id' to AUTO, which generates an id (host name and"
            + " start time, or an InstanceIdGenerator bean), or to a value that differs on every"
            + " instance, for example ${HOSTNAME}.",
        cause);
  }
}
