/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.quartz;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;
import org.quartz.impl.JobDetailImpl;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;



/**
 * DefaultSchedulerIT
 */
class DefaultSchedulerIT extends AbstractMongoIT {

    @Test
    void testAddJobNoTrigger() throws Exception {
        Scheduler scheduler = TestSchedulers.create(TestSchedulers.scheduler(), 1);
        JobDetailImpl jobDetail = new JobDetailImpl();
        jobDetail.setName("testjob");
        jobDetail.setJobClass(NoOpJob.class); // persistent stores need the job class

        try {
            scheduler.addJob(jobDetail, false);
        } catch (SchedulerException e) {
            assertThat(e.getMessage()).contains("durable");
        }

        jobDetail.setDurability(true);
        scheduler.addJob(jobDetail, false);
        scheduler.shutdown();
    }

    public static class NoOpJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }
}
