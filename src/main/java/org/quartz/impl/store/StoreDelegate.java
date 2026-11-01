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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.TriggerKey;
import org.quartz.TriggerSort;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.OperableTrigger;

/**
 * Primitive reads and writes of a persistent job store, scoped to one scheduler name.
 *
 * <p>{@link PersistentJobStore} implements the scheduling semantics on top of these and serializes
 * compound operations with its locks; each primitive must be atomic on its own (for example a
 * single conditional update, or a Redis Lua script). Trigger states are the {@code STATE_*}
 * constants of {@link PersistentJobStore}. Methods returning a count return the number of records
 * changed.
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

  /**
   * One page of the jobs in the matching groups, ordered by group, then name, with the number of
   * matching jobs. The default reads the keys, then only the page's jobs; stores with a query
   * language override it with one query and one count.
   */
  default StorePage<JobDetail> selectJobDetails(GroupMatcher<JobKey> matcher, int offset, int limit)
      throws JobPersistenceException {
    List<JobKey> keys = new ArrayList<>();
    for (String group : StoreQueries.matchingGroups(selectJobGroups(), matcher)) {
      keys.addAll(selectJobKeys(group));
    }
    keys.sort(StoreQueries.keyOrder());
    List<JobDetail> jobs = new ArrayList<>();
    for (JobKey key : StorePage.of(keys, offset, limit).content()) {
      JobDetail job = selectJob(key);
      if (job != null) {
        jobs.add(job);
      }
    }
    return new StorePage<>(jobs, keys.size());
  }

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

  /**
   * Keys of the triggers in the matching groups whose stored state is one of {@code states} (all
   * states if empty), ordered by group, then name. The default reads each trigger's state; stores
   * with a query language override it with one query.
   */
  default List<TriggerKey> selectTriggerKeys(
      GroupMatcher<TriggerKey> matcher, Collection<String> states) {
    return selectTriggerStates(matcher, states).stream().map(Map.Entry::getKey).toList();
  }

  /**
   * One page of the triggers in the matching groups whose stored state is one of {@code states}
   * (all states if empty), in {@link StoreQueries#triggerOrder} order, with the number of matching
   * triggers. The default reads each matching trigger's state once; ordered by key it then loads
   * only the page's triggers, otherwise every matching trigger, to sort them. Stores with a query
   * language override it with one query and one count.
   */
  default StorePage<StoredTrigger> selectTriggers(
      GroupMatcher<TriggerKey> matcher,
      Collection<String> states,
      TriggerSort sort,
      boolean descending,
      int offset,
      int limit)
      throws JobPersistenceException {
    List<Map.Entry<TriggerKey, String>> matching = selectTriggerStates(matcher, states);
    if (sort == TriggerSort.KEY) {
      if (descending) {
        matching = matching.reversed();
      }
      return new StorePage<>(
          load(StorePage.of(matching, offset, limit).content()), matching.size());
    }
    List<StoredTrigger> triggers = load(matching);
    triggers.sort(
        Comparator.comparing(StoredTrigger::trigger, StoreQueries.triggerOrder(sort, descending)));
    return new StorePage<>(StorePage.of(triggers, offset, limit).content(), matching.size());
  }

  /** The key and stored state of each matching trigger, ordered by key. */
  private List<Map.Entry<TriggerKey, String>> selectTriggerStates(
      GroupMatcher<TriggerKey> matcher, Collection<String> states) {
    List<Map.Entry<TriggerKey, String>> matching = new ArrayList<>();
    for (String group : StoreQueries.matchingGroups(selectTriggerGroups(), matcher)) {
      for (TriggerKey key : selectTriggerKeys(group)) {
        String state = selectTriggerState(key);
        if (state != null && (states.isEmpty() || states.contains(state))) {
          matching.add(Map.entry(key, state));
        }
      }
    }
    matching.sort(Map.Entry.comparingByKey(StoreQueries.keyOrder()));
    return matching;
  }

  /** The triggers of the keys, with the given states; triggers deleted meanwhile are left out. */
  private List<StoredTrigger> load(List<Map.Entry<TriggerKey, String>> keyStates)
      throws JobPersistenceException {
    List<StoredTrigger> triggers = new ArrayList<>();
    for (Map.Entry<TriggerKey, String> keyState : keyStates) {
      OperableTrigger trigger = selectTrigger(keyState.getKey());
      if (trigger != null) {
        triggers.add(new StoredTrigger(trigger, keyState.getValue()));
      }
    }
    return triggers;
  }

  int updateTriggerState(TriggerKey triggerKey, String newState);

  /** Changes the state only if it currently is one of {@code oldStates}. */
  int updateTriggerStateFromOtherStates(
      TriggerKey triggerKey, String newState, Collection<String> oldStates);

  int updateTriggerGroupStateFromOtherStates(
      String group, String newState, Collection<String> oldStates);

  int updateTriggerStatesForJob(JobKey jobKey, String newState);

  int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState);

  /** Changes every trigger of the scheduler that is in one of {@code oldStates}. */
  int updateTriggerStatesFromOtherStates(String newState, Collection<String> oldStates);

  /**
   * {@code WAITING} triggers due no later than {@code noLaterThan} and not misfired (fire time at
   * or after {@code misfireTime}, unless they ignore misfires), ordered by next fire time, then
   * priority descending.
   */
  List<TriggerKey> selectTriggersToAcquire(Instant noLaterThan, Instant misfireTime, int maxCount);

  /**
   * Triggers in {@code state} whose next fire time is before {@code misfireTime} and that do not
   * ignore misfires, ordered by next fire time, then priority descending; {@code limit < 0} means
   * all.
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

  /**
   * Updates state, job flags and fire time of an existing entry; leaves its {@link
   * FiredTriggerRecord#interruptRequested()} flag as it is.
   */
  void updateFiredTrigger(FiredTriggerRecord record);

  /**
   * Flags an {@code EXECUTING} entry as asked to be interrupted.
   *
   * @return whether an executing entry with that id exists (flagged before or now)
   */
  boolean updateFiredTriggerInterruptRequested(String fireInstanceId);

  void deleteFiredTrigger(String fireInstanceId);

  int deleteFiredTriggers();

  int deleteFiredTriggers(String instanceId);

  /** The fired-trigger entry with that id, or {@code null}. */
  @Nullable FiredTriggerRecord selectFiredTriggerRecord(String fireInstanceId);

  /** All fired-trigger entries of the scheduler, on every instance. */
  List<FiredTriggerRecord> selectFiredTriggerRecords();

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
