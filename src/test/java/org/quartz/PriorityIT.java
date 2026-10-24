/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.MutableTrigger;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;

/** Test Trigger priority support. */
class PriorityIT extends AbstractMongoIT {

  private static CountDownLatch latch;
  private static StringBuffer result;

  @SuppressWarnings("deprecation")
  @DisallowConcurrentExecution
  @PersistJobDataAfterExecution
  public static class TestJob implements Job {
    public void execute(JobExecutionContext context) throws JobExecutionException {
      result.append(context.getTrigger().getKey().getName());
      latch.countDown();
    }
  }

  @BeforeEach
  protected void setUp() {
    PriorityIT.latch = new CountDownLatch(2);
    PriorityIT.result = new StringBuffer();
  }

  @SuppressWarnings("deprecation")
  @Test
  void testSameDefaultPriority() throws Exception {
    Scheduler sched = TestSchedulers.create(TestSchedulers.scheduler(), 1);

    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.SECOND, 1);

    MutableTrigger trig1 =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger().withIdentity("T1", null).startAt(cal.toInstant()).build();
    MutableTrigger trig2 =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger().withIdentity("T2", null).startAt(cal.toInstant()).build();

    JobDetail jobDetail =
        (JobDetailImpl) JobBuilder.newJob(TestJob.class).withIdentity("JD", null).build();

    sched.scheduleJob(jobDetail, trig1);

    trig2.setJobKey(new JobKey(jobDetail.getKey().getName()));
    sched.scheduleJob(trig2);

    sched.start();

    latch.await();

    assertEquals("T1T2", result.toString());

    sched.shutdown();
  }

  @SuppressWarnings("deprecation")
  @Test
  void testDifferentPriority() throws Exception {
    Scheduler sched = TestSchedulers.create(TestSchedulers.scheduler(), 1);

    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.SECOND, 1);

    MutableTrigger trig1 =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger().withIdentity("T1", null).startAt(cal.toInstant()).build();
    trig1.setPriority(5);

    MutableTrigger trig2 =
        (SimpleTriggerImpl)
            TriggerBuilder.newTrigger().withIdentity("T2", null).startAt(cal.toInstant()).build();
    trig2.setPriority(10);

    JobDetail jobDetail =
        (JobDetailImpl) JobBuilder.newJob(TestJob.class).withIdentity("JD", null).build();

    sched.scheduleJob(jobDetail, trig1);

    trig2.setJobKey(new JobKey(jobDetail.getKey().getName(), null));
    sched.scheduleJob(trig2);

    sched.start();

    latch.await();

    assertEquals("T2T1", result.toString());

    sched.shutdown();
  }
}
