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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;

class FactoryBeansTest {

  public static class NoOpJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  private static JobDetail jobDetail(String beanName) {
    JobDetailFactoryBean factory = new JobDetailFactoryBean();
    factory.setBeanName(beanName);
    factory.setJobClass(NoOpJob.class);
    factory.setJobDataAsMap(Map.of("answer", 42));
    factory.setDurability(true);
    factory.afterPropertiesSet();
    return factory.getObject();
  }

  @Test
  void jobDetailDefaultsNameToBeanNameAndGroupToDefault() {
    JobDetail job = jobDetail("myJob");

    assertThat(job.getKey().getName()).isEqualTo("myJob");
    assertThat(job.getKey().getGroup()).isEqualTo(Scheduler.DEFAULT_GROUP);
    assertThat(job.getJobClass()).isEqualTo(NoOpJob.class);
    assertThat(job.getJobDataMap().get("answer")).isEqualTo(42);
    assertThat(job.isDurable()).isTrue();
  }

  @Test
  void jobDetailRequiresJobClass() {
    JobDetailFactoryBean factory = new JobDetailFactoryBean();
    factory.setBeanName("noClass");

    assertThatThrownBy(factory::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cronTriggerUsesJavaTimeTypes() throws Exception {
    JobDetail job = jobDetail("cronJob");
    Instant start = Instant.parse("2030-01-01T00:00:00Z");
    CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
    factory.setBeanName("cronTrigger");
    factory.setJobDetail(job);
    factory.setCronExpression("0 0 12 * * ?");
    factory.setTimeZone(ZoneId.of("Europe/London"));
    factory.setStartTime(start);
    factory.setMisfireInstructionName("MISFIRE_INSTRUCTION_DO_NOTHING");
    factory.afterPropertiesSet();
    CronTrigger trigger = factory.getObject();

    assertThat(trigger.getKey().getName()).isEqualTo("cronTrigger");
    assertThat(trigger.getJobKey()).isEqualTo(job.getKey());
    assertThat(trigger.getCronExpression()).isEqualTo("0 0 12 * * ?");
    assertThat(trigger.getTimeZone()).isEqualTo(ZoneId.of("Europe/London"));
    assertThat(trigger.getStartTime()).isEqualTo(start);
    assertThat(trigger.getMisfireInstruction())
        .isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    assertThat(trigger.getJobDataMap().get("jobDetail")).isSameAs(job);
  }

  @Test
  void cronTriggerDefaultsToSystemZone() throws Exception {
    CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
    factory.setBeanName("defaultZone");
    factory.setCronExpression("0 0 12 * * ?");
    factory.afterPropertiesSet();

    assertThat(factory.getObject().getTimeZone()).isEqualTo(ZoneId.systemDefault());
  }

  @Test
  void simpleTriggerStartDelayAndIntervalAreDurations() {
    SimpleTriggerFactoryBean factory = new SimpleTriggerFactoryBean();
    factory.setBeanName("simpleTrigger");
    factory.setStartDelay(Duration.ofMinutes(5));
    factory.setRepeatInterval(Duration.ofSeconds(30));
    factory.setRepeatCount(3);
    Instant before = Instant.now();
    factory.afterPropertiesSet();
    SimpleTrigger trigger = factory.getObject();

    assertThat(trigger.getRepeatInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(trigger.getRepeatCount()).isEqualTo(3);
    assertThat(trigger.getStartTime())
        .isBetween(
            before.plus(Duration.ofMinutes(5)).minusMillis(1),
            Instant.now().plus(Duration.ofMinutes(5)));
  }

  @Test
  void negativeStartDelayIsRejected() {
    assertThatThrownBy(() -> new SimpleTriggerFactoryBean().setStartDelay(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CronTriggerFactoryBean().setStartDelay(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownMisfireInstructionIsRejected() {
    assertThatThrownBy(() -> new SimpleTriggerFactoryBean().setMisfireInstructionName("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
