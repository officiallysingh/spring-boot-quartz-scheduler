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

import java.util.List;

/**
 * One page of a store query.
 *
 * @param content the page
 * @param total how many items match the query in all
 * @param <T> the item type
 */
public record StorePage<T>(List<T> content, long total) {

  /** Copies the content. */
  public StorePage {
    content = List.copyOf(content);
  }

  /** The page {@code offset .. offset + limit} of all matching items, already ordered. */
  static <T> StorePage<T> of(List<T> all, int offset, int limit) {
    int from = Math.min(offset, all.size());
    int to = (int) Math.min((long) from + limit, all.size());
    return new StorePage<>(all.subList(from, to), all.size());
  }
}
