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

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;

/**
 * Primitive reads and writes of a persistent job store, scoped to one scheduler name.
 * <p>
 * {@link PersistentJobStore} implements the scheduling semantics on top of these and
 * serializes compound operations with its locks; each primitive must be atomic on its
 * own (for example a single conditional update, or a Redis Lua script). Trigger states
 * are the {@code STATE_*} constants of {@link PersistentJobStore}. Methods returning a
 * count return the number of records changed.
 */
public interface StoreDelegate {

    // jobs

    void insertJob(JobDetail job) throws JobPersistenceException;

    /** Replaces an existing job (and its job data). */
    void updateJob(JobDetail job) throws JobPersistenceException;

    void updateJobData(JobDetail job) throws JobPersistenceException;

    boolean jobExists(JobKey jobKey);

    @Nullable JobDetail selectJob(JobKey jobKey) throws JobPersistenceException;

    boolean deleteJob(JobKey jobKey);

    int selectNumJobs();

    List<String> selectJobGroups();

    Set<JobKey> selectJobKeys(String group);

    // triggers

    void insertTrigger(OperableTrigger trigger, String state) throws JobPersistenceException;

    /** Replaces an existing trigger, possibly with one of another type. */
    void updateTrigger(OperableTrigger trigger, String state) throws JobPersistenceException;

    boolean triggerExists(TriggerKey triggerKey);

    @Nullable OperableTrigger selectTrigger(TriggerKey triggerKey) throws JobPersistenceException;

    /** The trigger's job data only (used to build recovery triggers). */
    JobDataMap selectTriggerJobData(TriggerKey triggerKey) throws JobPersistenceException;

    /** The trigger's state, or {@code null} if it does not exist. */
    @Nullable String selectTriggerState(TriggerKey triggerKey);

    boolean deleteTrigger(TriggerKey triggerKey);

    int selectNumTriggers();

    int selectNumTriggersForJob(JobKey jobKey);

    List<String> selectTriggerGroups();

    Set<TriggerKey> selectTriggerKeys(String group);

    List<TriggerKey> selectTriggerKeysForJob(JobKey jobKey);

    List<TriggerKey> selectTriggerKeysForCalendar(String calendarName);

    List<TriggerKey> selectTriggerKeysInState(String state);

    int updateTriggerState(TriggerKey triggerKey, String newState);

    /** Changes the state only if it currently is one of {@code oldStates}. */
    int updateTriggerStateFromOtherStates(TriggerKey triggerKey, String newState, Collection<String> oldStates);

    int updateTriggerGroupStateFromOtherStates(String group, String newState, Collection<String> oldStates);

    int updateTriggerStatesForJob(JobKey jobKey, String newState);

    int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState);

    /** Changes every trigger of the scheduler that is in one of {@code oldStates}. */
    int updateTriggerStatesFromOtherStates(String newState, Collection<String> oldStates);

    /**
     * {@code WAITING} triggers due no later than {@code noLaterThan} and not misfired
     * (fire time at or after {@code misfireTime}, unless they ignore misfires), ordered by
     * next fire time, then priority descending.
     */
    List<TriggerKey> selectTriggersToAcquire(Instant noLaterThan, Instant misfireTime, int maxCount);

    /**
     * Triggers in {@code state} whose next fire time is before {@code misfireTime} and that
     * do not ignore misfires, ordered by next fire time, then priority descending;
     * {@code limit < 0} means all.
     */
    List<TriggerKey> selectMisfiredTriggersInState(String state, Instant misfireTime, int limit);

    int countMisfiredTriggersInState(String state, Instant misfireTime);

    // calendars

    void insertCalendar(String name, Calendar calendar) throws JobPersistenceException;

    void updateCalendar(String name, Calendar calendar) throws JobPersistenceException;

    boolean calendarExists(String name);

    @Nullable Calendar selectCalendar(String name) throws JobPersistenceException;

    boolean deleteCalendar(String name);

    List<String> selectCalendarNames();

    int selectNumCalendars();

    // paused groups

    void insertPausedTriggerGroup(String group);

    void deletePausedTriggerGroup(String group);

    boolean isTriggerGroupPaused(String group);

    Set<String> selectPausedTriggerGroups();

    void insertPausedJobGroup(String group);

    void deletePausedJobGroup(String group);

    boolean isJobGroupPaused(String group);

    Set<String> selectPausedJobGroups();

    // fired triggers

    void insertFiredTrigger(FiredTriggerRecord record);

    /** Updates state, job flags and fire time of an existing entry. */
    void updateFiredTrigger(FiredTriggerRecord record);

    void deleteFiredTrigger(String fireInstanceId);

    int deleteFiredTriggers();

    int deleteFiredTriggers(String instanceId);

    List<FiredTriggerRecord> selectFiredTriggerRecordsForInstance(String instanceId);

    List<FiredTriggerRecord> selectFiredTriggerRecordsForJob(JobKey jobKey);

    List<FiredTriggerRecord> selectFiredTriggerRecordsForTrigger(TriggerKey triggerKey);

    Set<String> selectFiredTriggerInstanceIds();

    // scheduler states (clustering)

    /** Records a check-in, inserting the instance's record if needed. */
    void upsertSchedulerState(String instanceId, Instant checkin, Duration checkinInterval);

    List<SchedulerStateRecord> selectSchedulerStateRecords();

    void deleteSchedulerState(String instanceId);

    // housekeeping

    /** Deletes jobs, triggers, calendars, paused groups and fired triggers of the scheduler. */
    void clearAllSchedulingData();
}
