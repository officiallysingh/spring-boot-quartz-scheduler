/*
 * Copyright 2026 the spring-boot-quartz-scheduler authors.
 *
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
package org.quartz;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.quartz.Trigger.TriggerState;
import org.quartz.impl.matchers.GroupMatcher;

/**
 * A query for one page of triggers with their states; see {@link Scheduler#getTriggers}.
 *
 * <pre>{@code
 * // the 20 error triggers that were due soonest
 * scheduler.getTriggers(
 *     new TriggerQuery(GroupMatcher.anyTriggerGroup(), Set.of(TriggerState.ERROR),
 *         TriggerSort.NEXT_FIRE_TIME, false, 0, 20));
 * }</pre>
 *
 * @param matcher the trigger groups to include
 * @param states the states to include; empty for all
 * @param sort the order
 * @param descending whether to reverse the order (ties included)
 * @param offset how many matching triggers to skip, at least 0
 * @param limit the page size, at least 1
 */
public record TriggerQuery(
    GroupMatcher<TriggerKey> matcher,
    Set<TriggerState> states,
    TriggerSort sort,
    boolean descending,
    int offset,
    int limit) {

  /**
   * Validates the query and copies the states.
   *
   * @throws IllegalArgumentException if {@code offset} is negative or {@code limit} not positive
   */
  public TriggerQuery {
    Objects.requireNonNull(matcher, "matcher");
    Objects.requireNonNull(sort, "sort");
    states = states.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(states));
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative: " + offset);
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
  }

  /**
   * The first page of triggers in the matching groups, in every state, ordered by key.
   *
   * @param matcher the trigger groups to include
   * @param limit the page size
   * @return the query
   */
  public static TriggerQuery of(GroupMatcher<TriggerKey> matcher, int limit) {
    return new TriggerQuery(matcher, Set.of(), TriggerSort.KEY, false, 0, limit);
  }
}
