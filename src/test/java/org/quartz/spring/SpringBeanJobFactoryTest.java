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
package org.quartz.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;

/** The scheduler's default job factory, used without an application context. */
class SpringBeanJobFactoryTest {

  public static class JobWithProperties implements Job {
    String name;
    int retries;

    public void setName(String name) {
      this.name = name;
    }

    public void setRetries(int retries) {
      this.retries = retries;
    }

    @Override
    public void execute(JobExecutionContext context) {}
  }

  @Test
  void withoutAnApplicationContextCreatesTheJobAndAppliesItsJobData() throws Exception {
    JobDetail job =
        JobBuilder.newJob(JobWithProperties.class)
            .usingJobData("name", "from job")
            .usingJobData("retries", "3")
            .build();
    OperableTrigger trigger =
        (OperableTrigger)
            TriggerBuilder.newTrigger().forJob(job).usingJobData("name", "from trigger").build();
    Instant now = Instant.now();
    TriggerFiredBundle bundle =
        new TriggerFiredBundle(job, trigger, null, false, now, now, null, null);

    Job created = new SpringBeanJobFactory().newJob(bundle, mock(Scheduler.class));

    assertThat(created).isInstanceOf(JobWithProperties.class);
    JobWithProperties instance = (JobWithProperties) created;
    assertThat(instance.name).isEqualTo("from trigger"); // the trigger's data wins
    assertThat(instance.retries).isEqualTo(3);
  }
}
