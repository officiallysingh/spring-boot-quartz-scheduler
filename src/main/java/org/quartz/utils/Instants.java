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

import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

/**
 * Scheduling instants are kept at millisecond precision, the precision of the original Date-based
 * API, so fire-time arithmetic and equality are unchanged.
 */
public final class Instants {

  private Instants() {}

  /**
   * The current instant of the given source (the scheduler's {@link java.time.Clock}), truncated to
   * milliseconds. There is no overload without a source: every "now" of the scheduler comes from
   * its clock.
   */
  public static Instant now(InstantSource source) {
    return Instant.ofEpochMilli(source.millis());
  }

  /** The given instant truncated to milliseconds, or {@code null} if it is {@code null}. */
  @Contract("null -> null; !null -> !null")
  public static @Nullable Instant toMillis(@Nullable Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }
}
