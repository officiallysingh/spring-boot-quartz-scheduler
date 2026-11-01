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

import java.util.List;

/**
 * One page of {@link Scheduler#getJobDetails(org.quartz.impl.matchers.GroupMatcher, int, int)}.
 *
 * @param content the jobs of the page, ordered by key (group, then name)
 * @param total how many jobs match in all
 */
public record JobDetailPage(List<JobDetail> content, long total) {

  /** Copies the content. */
  public JobDetailPage {
    content = List.copyOf(content);
  }
}
