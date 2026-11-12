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
package org.quartz.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InstantMessageFormatTest {

  private static final String PATTERN = "Job {1}.{0} fired at: {2,date,HH:mm:ss MM/dd/yyyy}";

  @Test
  void rendersDateArgumentsInTheGivenZone() {
    Instant at = Instant.parse("2026-03-10T23:30:00Z");

    assertThat(InstantMessageFormat.format(ZoneOffset.UTC, PATTERN, "job", "g", at))
        .isEqualTo("Job g.job fired at: 23:30:00 03/10/2026");
    assertThat(InstantMessageFormat.format(ZoneId.of("Asia/Kolkata"), PATTERN, "job", "g", at))
        .isEqualTo("Job g.job fired at: 05:00:00 03/11/2026");
  }

  @Test
  void leavesTheOtherArgumentsToMessageFormat() {
    assertThat(InstantMessageFormat.format(ZoneOffset.UTC, "{0} and {1}", "a", 2))
        .isEqualTo("a and 2");
  }
}
