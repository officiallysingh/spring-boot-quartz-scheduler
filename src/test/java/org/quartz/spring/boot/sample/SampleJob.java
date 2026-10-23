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
package org.quartz.spring.boot.sample;

import org.bson.Document;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records each execution in the business database. Created by {@code SpringBeanJobFactory}, so it
 * can be autowired; the write joins the job's transaction when there is one.
 */
public class SampleJob implements Job {

  static final String EXECUTIONS = "sample_executions";

  @Autowired private MongoTemplate mongoTemplate;

  @Value("${sample.run-id}")
  private String runId;

  @Override
  public void execute(JobExecutionContext context) {
    try {
      mongoTemplate.insert(
          new Document("runId", runId)
              .append("instanceId", context.getScheduler().getSchedulerInstanceId())
              .append("scheduledFireTime", context.getScheduledFireTime())
              .append(
                  "inTransaction", TransactionSynchronizationManager.isActualTransactionActive()),
          EXECUTIONS);
    } catch (SchedulerException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
