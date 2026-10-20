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
package org.quartz.impl.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.simpleSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.simpl.CascadingClassLoadHelper;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.support.MutableClock;

/**
 * Misfire detection in {@link PersistentJobStore} uses the scheduler's {@link Clock} (via the signaler), so
 * it is deterministic: nothing here depends on the wall clock (D-010). Runs the engine over the in-memory
 * test delegate; the misfire pass the handler thread would run is called directly.
 */
class PersistentJobStoreClockTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final List<Trigger> misfired = new ArrayList<>();
    private PersistentJobStore store;

    @BeforeEach
    void setUp() throws SchedulerException {
        store = new InMemoryPersistentJobStore(new InMemoryStoreDelegate.Data(),
                JobStoreSettings.builder().misfireThreshold(Duration.ofSeconds(5)).build());
        store.setInstanceName("ClockTest");
        store.setInstanceId("NON_CLUSTERED");
        CascadingClassLoadHelper loadHelper = new CascadingClassLoadHelper();
        loadHelper.initialize();
        store.initialize(loadHelper, new ClockSignaler());
    }

    private List<OperableTrigger> acquireAfterMisfirePass(Instant noLaterThan) throws Exception {
        store.recoverMisfiredTriggers();
        return store.acquireNextTriggers(noLaterThan, 1, Duration.ZERO);
    }

    @Test
    void fireNowMisfireUsesTheClockTime() throws Exception {
        store(newTrigger().withIdentity("once").startAt(T0)
                .withSchedule(simpleSchedule().withMisfireHandlingInstructionFireNow()).build());

        clock.advance(Duration.ofMinutes(10)); // far in the future in real time, but the clock says so

        List<OperableTrigger> acquired = acquireAfterMisfirePass(clock.instant().plusSeconds(30));

        assertThat(misfired).extracting(Trigger::getKey).extracting(Object::toString).containsExactly("DEFAULT.once");
        assertThat(acquired).singleElement()
                .extracting(Trigger::getNextFireTime).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void triggerWithinTheMisfireThresholdIsNotMisfired() throws Exception {
        store(newTrigger().withIdentity("late").startAt(T0)
                .withSchedule(simpleSchedule().withMisfireHandlingInstructionFireNow()).build());

        clock.advance(Duration.ofSeconds(4)); // below the 5 s threshold

        List<OperableTrigger> acquired = acquireAfterMisfirePass(clock.instant().plusSeconds(30));

        assertThat(misfired).isEmpty();
        assertThat(acquired).singleElement().extracting(Trigger::getNextFireTime).isEqualTo(T0);
    }

    @Test
    void rescheduleNextWithRemainingCountSkipsTheMissedFiresByClockTime() throws Exception {
        store(newTrigger().withIdentity("repeating").startAt(T0)
                .withSchedule(simpleSchedule().withInterval(Duration.ofMinutes(1)).withRepeatCount(10)
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build());

        clock.advance(Duration.ofMinutes(3).plusSeconds(30));

        List<OperableTrigger> acquired = acquireAfterMisfirePass(clock.instant().plusSeconds(60));

        assertThat(misfired).hasSize(1);
        assertThat(acquired).singleElement()
                .extracting(Trigger::getNextFireTime).isEqualTo(T0.plus(Duration.ofMinutes(4)));
    }

    private void store(Trigger trigger) throws SchedulerException {
        JobDetail job = newJob(NoOpJob.class).withIdentity(JobKey.jobKey("job-" + trigger.getKey().getName())).build();
        OperableTrigger operable = (OperableTrigger) trigger.getTriggerBuilder().forJob(job).build();
        operable.computeFirstFireTime(null);
        store.storeJobAndTrigger(job, operable);
    }

    public static class NoOpJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    private final class ClockSignaler implements SchedulerSignaler {
        @Override
        public void notifyTriggerListenersMisfired(Trigger trigger) {
            misfired.add(trigger);
        }

        @Override
        public void notifySchedulerListenersFinalized(Trigger trigger) {
        }

        @Override
        public void notifySchedulerListenersJobDeleted(JobKey jobKey) {
        }

        @Override
        public void signalSchedulingChange(Instant candidateNewNextFireTime) {
        }

        @Override
        public Instant getCurrentTime() {
            return clock.instant();
        }

        @Override
        public void notifySchedulerListenersError(String string, SchedulerException jpe) {
        }
    }
}
