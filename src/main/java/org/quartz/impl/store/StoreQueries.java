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
package org.quartz.impl.store;

import java.util.Comparator;
import java.util.List;
import org.quartz.TriggerSort;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.StringMatcher.StringOperatorName;
import org.quartz.spi.OperableTrigger;
import org.quartz.utils.Key;

/** Group matching and trigger ordering shared by the stores' queries. */
public final class StoreQueries {

  private StoreQueries() {}

  /** The groups the matcher accepts. */
  public static <K extends Key<K>> List<String> matchingGroups(
      List<String> groups, GroupMatcher<K> matcher) {
    StringOperatorName operator = matcher.getCompareWithOperator();
    String value = matcher.getCompareToValue();
    if (operator == StringOperatorName.EQUALS) {
      return groups.contains(value) ? List.of(value) : List.of();
    }
    return groups.stream().filter(group -> operator.evaluate(group, value)).toList();
  }

  /** Orders keys by group, then name. */
  public static <K extends Key<K>> Comparator<K> keyOrder() {
    return Comparator.comparing((K key) -> key.getGroup()).thenComparing(Key::getName);
  }

  /**
   * The order of {@link org.quartz.Scheduler#getTriggers}: by the sort field (missing times first),
   * ties by group and name; all reversed when descending. The MongoDB store sorts the same way.
   */
  public static Comparator<OperableTrigger> triggerOrder(TriggerSort sort, boolean descending) {
    Comparator<OperableTrigger> byKey =
        Comparator.comparing(OperableTrigger::getKey, StoreQueries.keyOrder());
    Comparator<OperableTrigger> order =
        switch (sort) {
          case KEY -> byKey;
          case NEXT_FIRE_TIME ->
              Comparator.comparing(
                      OperableTrigger::getNextFireTime,
                      Comparator.nullsFirst(Comparator.naturalOrder()))
                  .thenComparing(byKey);
          case PREVIOUS_FIRE_TIME ->
              Comparator.comparing(
                      OperableTrigger::getPreviousFireTime,
                      Comparator.nullsFirst(Comparator.naturalOrder()))
                  .thenComparing(byKey);
          case PRIORITY ->
              Comparator.comparingInt(OperableTrigger::getPriority).thenComparing(byKey);
        };
    return descending ? order.reversed() : order;
  }
}
