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
 *
 */
package org.quartz.impl.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerKey;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.StringMatcher.StringOperatorName;
import org.quartz.impl.store.codec.JobDataCodec;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.quartz.utils.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class of the persistent, cluster-capable job stores. It implements the scheduling semantics
 * of Quartz's original JDBC store ({@code JobStoreSupport}) on top of a {@link StoreDelegate} of
 * primitive operations and a {@link StoreLock}:
 *
 * <ul>
 *   <li>trigger states ({@code WAITING}, {@code ACQUIRED}, {@code PAUSED}, {@code BLOCKED}, {@code
 *       PAUSED_BLOCKED}, {@code COMPLETE}, {@code ERROR}), paused trigger and job groups, {@link
 *       org.quartz.DisallowConcurrentExecution} blocking and {@link
 *       org.quartz.PersistJobDataAfterExecution};
 *   <li>fired-trigger records for every acquired or executing trigger, used for recovery;
 *   <li>a misfire handler thread, and recovery of interrupted work at startup;
 *   <li>when clustered, a cluster manager thread that checks in and recovers the work of instances
 *       that stopped checking in.
 * </ul>
 *
 * Compound operations run under the {@value #LOCK_TRIGGER_ACCESS} lock (and {@value
 * #LOCK_STATE_ACCESS} for cluster recovery); trigger acquisition without the lock relies on the
 * delegate's conditional {@code WAITING -> ACQUIRED} update. Scheduling time comes from {@link
 * SchedulerSignaler#getCurrentTime()}; check-ins and lock leases use the wall clock.
 *
 * @author James House (JobStoreSupport)
 */
public abstract class PersistentJobStore implements JobStore {

  /** Stored trigger state: waiting for its next fire time. */
  public static final String STATE_WAITING = "WAITING";

  /** Stored trigger state: acquired by a scheduler instance, about to fire. */
  public static final String STATE_ACQUIRED = "ACQUIRED";

  /** Fired-trigger state: its job is executing. */
  public static final String STATE_EXECUTING = "EXECUTING";

  /** Stored trigger state: it will not fire again. */
  public static final String STATE_COMPLETE = "COMPLETE";

  /** Stored trigger state: waiting for a non-concurrent execution of its job to end. */
  public static final String STATE_BLOCKED = "BLOCKED";

  /** Stored trigger state: in error, until reset. */
  public static final String STATE_ERROR = "ERROR";

  /** Stored trigger state: paused. */
  public static final String STATE_PAUSED = "PAUSED";

  /** Stored trigger state: paused while blocked; becomes {@value #STATE_BLOCKED} when resumed. */
  public static final String STATE_PAUSED_BLOCKED = "PAUSED_BLOCKED";

  /** Name of the lock serializing changes to jobs, triggers and calendars. */
  public static final String LOCK_TRIGGER_ACCESS = "TRIGGER_ACCESS";

  /** Name of the lock serializing cluster check-ins and the recovery of failed instances. */
  public static final String LOCK_STATE_ACCESS = "STATE_ACCESS";

  /** Marker paused-group entry written by {@link #pauseAll()}. */
  public static final String ALL_GROUPS_PAUSED = "_$_ALL_GROUPS_PAUSED_$_";

  private static final AtomicLong FIRE_INSTANCE_COUNTER =
      new AtomicLong(System.currentTimeMillis());

  private static final AtomicLong RECOVERY_COUNTER = new AtomicLong(System.currentTimeMillis());

  /** Logger available to subclasses. */
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final JobStoreSettings settings;

  @SuppressWarnings("NullAway.Init")
  private String instanceId;

  @SuppressWarnings("NullAway.Init")
  private String instanceName;

  @SuppressWarnings("NullAway.Init")
  private ClassLoadHelper classLoadHelper;

  @SuppressWarnings("NullAway.Init")
  private SchedulerSignaler signaler;

  @SuppressWarnings("NullAway.Init")
  private StoreDelegate delegate;

  @SuppressWarnings("NullAway.Init")
  private StoreLock lock;

  private @Nullable ClassLoader initializersLoader;

  /**
   * The store operation the current thread is running, bound by the outermost {@link
   * #executeInLock}.
   */
  private final ScopedValue<Operation> operation = ScopedValue.newInstance();

  private volatile boolean schedulerRunning;

  private volatile boolean shutdown;

  private @Nullable MisfireHandler misfireHandler;

  private @Nullable ClusterManager clusterManager;

  private volatile boolean firstCheckIn = true;

  private volatile Instant lastCheckin = Instant.now();

  /**
   * Create a store.
   *
   * @param settings the store-independent settings
   */
  protected PersistentJobStore(JobStoreSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  // ------------------------------------------------------------------
  // hooks for the concrete stores
  // ------------------------------------------------------------------

  /** Creates the delegate for the given scheduler name; called from {@link #initialize}. */
  protected abstract StoreDelegate createDelegate(String schedulerName, ClassLoadHelper loadHelper);

  /**
   * Creates the cluster-wide lock; called from {@link #initialize} when clustered.
   *
   * @param owner unique id of this store instance
   */
  protected abstract StoreLock createClusterLock(String schedulerName, String owner);

  /** Prepares the backing store (indexes etc.) once the delegate exists. */
  protected void initializeStore() throws JobPersistenceException {}

  /**
   * Runs a store operation; stores that support it wrap it in a transaction. The default runs it
   * directly. Called with the operation's locks held.
   */
  protected <T> T inTransaction(StoreCallback<T> callback) throws JobPersistenceException {
    return callback.execute();
  }

  /**
   * Whether single-trigger acquisition also takes the trigger lock (batches always do). Without it,
   * acquisition relies on the delegate's conditional {@code WAITING -> ACQUIRED} update. Defaults
   * to {@link JobStoreSettings#acquireTriggersWithinLock()}.
   */
  protected boolean acquireTriggersWithinLock() {
    return settings.acquireTriggersWithinLock();
  }

  /** A store operation. */
  @FunctionalInterface
  protected interface StoreCallback<T extends @Nullable Object> {
    /**
     * Run the operation.
     *
     * @return its result
     * @throws JobPersistenceException if the store fails
     */
    T execute() throws JobPersistenceException;
  }

  // ------------------------------------------------------------------
  // configuration and lifecycle
  // ------------------------------------------------------------------

  /**
   * The store-independent settings.
   *
   * @return the settings
   */
  public JobStoreSettings getSettings() {
    return settings;
  }

  /**
   * When this instance last checked in with the cluster (only meaningful while a clustered store's
   * scheduler runs; used by the health indicator).
   */
  public Instant getLastCheckin() {
    return lastCheckin;
  }

  /**
   * The delegate doing the store's primitive reads and writes; set by {@link #initialize}.
   *
   * @return the delegate
   */
  protected StoreDelegate getDelegate() {
    return delegate;
  }

  /** The store's lock (exposed for diagnostics and the contract tests). */
  public StoreLock getLock() {
    return lock;
  }

  /**
   * How job classes are loaded; set by {@link #initialize}.
   *
   * @return the class load helper
   */
  protected ClassLoadHelper getClassLoadHelper() {
    return classLoadHelper;
  }

  /**
   * The id of the scheduler instance using the store.
   *
   * @return the instance id
   */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * The name of the scheduler using the store; all records are scoped to it.
   *
   * @return the scheduler name
   */
  public String getInstanceName() {
    return instanceName;
  }

  @Override
  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  @Override
  public void setInstanceName(String instanceName) {
    this.instanceName = instanceName;
  }

  @Override
  public void setThreadPoolSize(int poolSize) {}

  @Override
  public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler signaler)
      throws SchedulerConfigException {
    if (instanceName == null || instanceId == null) {
      throw new SchedulerConfigException(
          "Scheduler name and instance id must be set before initialize()");
    }
    this.classLoadHelper = loadHelper;
    this.signaler = signaler;
    if (settings.threadsInheritInitializersClassLoadContext()) {
      initializersLoader = Thread.currentThread().getContextClassLoader();
    }
    this.delegate = createDelegate(instanceName, loadHelper);
    if (isClustered()) {
      this.lock = createClusterLock(instanceName, instanceId + "/" + UUID.randomUUID());
      log.info(
          "Using cluster-wide store locks for scheduler '{}', instance '{}'.",
          instanceName,
          instanceId);
    } else {
      this.lock = new JvmStoreLock();
    }
    try {
      initializeStore();
    } catch (JobPersistenceException ex) {
      throw new SchedulerConfigException(
          "Couldn't initialize the job store: " + ex.getMessage(), ex);
    }
  }

  @Override
  public void schedulerStarted() throws SchedulerException {
    if (isClustered()) {
      clusterManager = new ClusterManager();
      clusterManager.initialize();
    } else {
      try {
        recoverJobs();
      } catch (SchedulerException ex) {
        throw new SchedulerConfigException("Failure occurred during job recovery.", ex);
      }
    }
    misfireHandler = new MisfireHandler();
    misfireHandler.start();
    schedulerRunning = true;
    log.debug("JobStore background threads started (as scheduler was started).");
  }

  @Override
  public void schedulerPaused() {
    schedulerRunning = false;
  }

  @Override
  public void schedulerResumed() {
    schedulerRunning = true;
  }

  @Override
  public void shutdown() {
    shutdown = true;
    stopThread(misfireHandler);
    misfireHandler = null;
    stopThread(clusterManager);
    clusterManager = null;
    log.debug("JobStore background threads shutdown.");
  }

  private void stopThread(@Nullable StoreThread thread) {
    if (thread != null) {
      thread.halt();
      if (thread != Thread.currentThread()) {
        try {
          thread.join();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Whether {@link #shutdown()} was called.
   *
   * @return {@code true} once shut down
   */
  protected boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean supportsPersistence() {
    return true;
  }

  @Override
  public boolean isClustered() {
    return settings.clustered();
  }

  @Override
  public Duration getEstimatedTimeToReleaseAndAcquireTrigger() {
    return Duration.ofMillis(70);
  }

  @Override
  public Duration getAcquireRetryDelay(int failureCount) {
    return settings.retryInterval();
  }

  /**
   * The scheduler's current time ({@link SchedulerSignaler#getCurrentTime()}), which may come from
   * a custom {@link java.time.Clock}.
   *
   * @return the current scheduling time
   * @throws IllegalStateException if the signaler cannot provide it
   */
  protected Instant getSchedulingTime() {
    Instant now;
    try {
      now = signaler.getCurrentTime();
    } catch (SchedulerException ex) {
      throw new IllegalStateException("Unable to obtain the current scheduling time", ex);
    }
    if (now == null) {
      throw new IllegalStateException("SchedulerSignaler returned null scheduling time");
    }
    return now;
  }

  /**
   * The instant before which a trigger's next fire time counts as misfired: the scheduling time
   * minus the misfire threshold, not before the epoch.
   *
   * @return the misfire cut-off
   */
  protected Instant getMisfireTime() {
    Instant misfireTime = getSchedulingTime().minus(settings.misfireThreshold());
    return misfireTime.isBefore(Instant.EPOCH) ? Instant.EPOCH : misfireTime;
  }

  /**
   * A fire-instance id unique to this store: the instance id and a counter.
   *
   * @return the new id
   */
  protected String newFireInstanceId() {
    return instanceId + "_" + FIRE_INSTANCE_COUNTER.incrementAndGet();
  }

  // ------------------------------------------------------------------
  // locking and execution
  // ------------------------------------------------------------------

  private boolean obtainLock(Operation op, String lockName) throws JobPersistenceException {
    if (op.heldLocks.contains(lockName)) {
      return false;
    }
    lock.obtain(lockName);
    op.heldLocks.add(lockName);
    return true;
  }

  private void releaseLock(Operation op, String lockName) {
    op.heldLocks.remove(lockName);
    lock.release(lockName);
  }

  /**
   * Runs an operation under the given lock ({@code null} for none) and, once the outermost
   * operation has completed, signals scheduling changes it recorded.
   */
  protected <T extends @Nullable Object> T executeInLock(
      @Nullable String lockName, StoreCallback<T> callback) throws JobPersistenceException {
    if (operation.isBound()) {
      return execute(operation.get(), false, lockName, callback);
    }
    Operation op = new Operation();
    return ScopedValue.where(operation, op).call(() -> execute(op, true, lockName, callback));
  }

  private <T extends @Nullable Object> T execute(
      Operation op, boolean outermost, @Nullable String lockName, StoreCallback<T> callback)
      throws JobPersistenceException {
    boolean owner = false;
    try {
      if (lockName != null) {
        owner = obtainLock(op, lockName);
      }
      T result = inTransaction(callback);
      if (outermost && op.pending) {
        signaler.signalSchedulingChange(op.candidate);
      }
      return result;
    } catch (JobPersistenceException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new JobPersistenceException("Unexpected runtime exception: " + ex.getMessage(), ex);
    } finally {
      if (owner && lockName != null) {
        releaseLock(op, lockName);
      }
    }
  }

  /**
   * Run an operation without a lock (but in a transaction when the store has them).
   *
   * @param callback the operation
   * @param <T> its result type
   * @return its result
   * @throws JobPersistenceException if the store fails
   */
  protected <T extends @Nullable Object> T executeWithoutLock(StoreCallback<T> callback)
      throws JobPersistenceException {
    return executeInLock(null, callback);
  }

  /**
   * Like {@link #executeInLock}, but retries every {@code retryInterval} until it succeeds or the
   * store shuts down (used where the scheduler cannot handle a failure).
   */
  protected void retryExecuteInLock(String lockName, StoreCallback<@Nullable Void> callback) {
    for (int retry = 1; !shutdown; retry++) {
      try {
        executeInLock(lockName, callback);
        return;
      } catch (JobPersistenceException ex) {
        if (retry % 4 == 1) {
          signaler.notifySchedulerListenersError(
              "An error occurred while accessing the job store", ex);
        }
        log.error(
            "Store operation failed (attempt {}), retrying in {}",
            retry,
            settings.retryInterval(),
            ex);
      }
      try {
        Thread.sleep(settings.retryInterval());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Received interrupted exception", ex);
      }
    }
    throw new IllegalStateException("JobStore is shutdown - aborting retry");
  }

  /** Records that the scheduler thread should be woken once the current operation completes. */
  protected void signalSchedulingChangeOnCompletion(@Nullable Instant candidateNewNextFireTime) {
    if (!operation.isBound()) {
      signaler.signalSchedulingChange(candidateNewNextFireTime);
      return;
    }
    Operation signal = operation.get();
    if (!signal.pending) {
      signal.pending = true;
      signal.candidate = candidateNewNextFireTime;
    } else if (signal.candidate != null
        && (candidateNewNextFireTime == null
            || candidateNewNextFireTime.isBefore(signal.candidate))) {
      signal.candidate = candidateNewNextFireTime;
    }
  }

  /** Per-thread state of the outermost store operation and the operations nested in it. */
  private static final class Operation {
    final Set<String> heldLocks = new HashSet<>();
    boolean pending;
    @Nullable Instant candidate;
  }

  // ------------------------------------------------------------------
  // jobs
  // ------------------------------------------------------------------

  @Override
  public void storeJobAndTrigger(JobDetail newJob, OperableTrigger newTrigger)
      throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          storeJob(newJob, false, true);
          storeTrigger(newTrigger, newJob, false, STATE_WAITING, false, false);
          return null;
        });
  }

  @Override
  public void storeJob(JobDetail newJob, boolean replaceExisting) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          storeJob(newJob, replaceExisting, true);
          return null;
        });
  }

  private void storeJob(JobDetail newJob, boolean replaceExisting, boolean unused)
      throws JobPersistenceException {
    boolean exists = delegate.jobExists(newJob.getKey());
    if (exists && !replaceExisting) {
      throw new ObjectAlreadyExistsException(newJob);
    }
    JobDataCodec.checkUseProperties(newJob.getJobDataMap(), settings.useProperties());
    if (exists) {
      delegate.updateJob(newJob);
    } else {
      delegate.insertJob(newJob);
    }
  }

  @Override
  public void storeJobsAndTriggers(
      Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace)
      throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          for (Map.Entry<JobDetail, Set<? extends Trigger>> entry : triggersAndJobs.entrySet()) {
            storeJob(entry.getKey(), replace, true);
            for (Trigger trigger : entry.getValue()) {
              storeTrigger(
                  (OperableTrigger) trigger, entry.getKey(), replace, STATE_WAITING, false, false);
            }
          }
          return null;
        });
  }

  @Override
  public boolean removeJob(JobKey jobKey) throws JobPersistenceException {
    return executeInLock(LOCK_TRIGGER_ACCESS, () -> removeJobInternal(jobKey));
  }

  private boolean removeJobInternal(JobKey jobKey) {
    for (TriggerKey triggerKey : delegate.selectTriggerKeysForJob(jobKey)) {
      delegate.deleteTrigger(triggerKey);
    }
    return delegate.deleteJob(jobKey);
  }

  @Override
  public boolean removeJobs(List<JobKey> jobKeys) throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          boolean allFound = true;
          for (JobKey jobKey : jobKeys) {
            allFound = removeJobInternal(jobKey) && allFound;
          }
          return allFound;
        });
  }

  @Override
  public @Nullable JobDetail retrieveJob(JobKey jobKey) throws JobPersistenceException {
    return executeWithoutLock(() -> delegate.selectJob(jobKey));
  }

  @Override
  public List<JobDetail> getJobDetails(GroupMatcher<JobKey> matcher)
      throws JobPersistenceException {
    return executeWithoutLock(
        () -> {
          List<JobDetail> jobs = new ArrayList<>();
          for (JobKey key : jobKeys(matcher)) {
            JobDetail job = delegate.selectJob(key);
            if (job != null) {
              jobs.add(job);
            }
          }
          return jobs;
        });
  }

  @Override
  public boolean checkExists(JobKey jobKey) throws JobPersistenceException {
    return executeWithoutLock(() -> delegate.jobExists(jobKey));
  }

  @Override
  public int getNumberOfJobs() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectNumJobs);
  }

  @Override
  public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
    return executeWithoutLock(() -> jobKeys(matcher));
  }

  private Set<JobKey> jobKeys(GroupMatcher<JobKey> matcher) {
    Set<JobKey> keys = new LinkedHashSet<>();
    for (String group : matchingGroups(delegate.selectJobGroups(), matcher)) {
      keys.addAll(delegate.selectJobKeys(group));
    }
    return keys;
  }

  @Override
  public List<String> getJobGroupNames() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectJobGroups);
  }

  private static <K extends Key<K>> List<String> matchingGroups(
      List<String> groups, GroupMatcher<K> matcher) {
    StringOperatorName operator = matcher.getCompareWithOperator();
    String value = matcher.getCompareToValue();
    if (operator == StringOperatorName.EQUALS) {
      return groups.contains(value) ? List.of(value) : List.of();
    }
    return groups.stream().filter(group -> operator.evaluate(group, value)).toList();
  }

  // ------------------------------------------------------------------
  // triggers
  // ------------------------------------------------------------------

  @Override
  public void storeTrigger(OperableTrigger newTrigger, boolean replaceExisting)
      throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          storeTrigger(newTrigger, null, replaceExisting, STATE_WAITING, false, false);
          return null;
        });
  }

  /**
   * Stores a trigger. Unless {@code forceState} is set, a trigger in a paused group is stored
   * {@code PAUSED} and one whose non-concurrent job is executing {@code BLOCKED}.
   */
  protected void storeTrigger(
      OperableTrigger newTrigger,
      @Nullable JobDetail job,
      boolean replaceExisting,
      String state,
      boolean forceState,
      boolean recovering)
      throws JobPersistenceException {
    boolean existing = delegate.triggerExists(newTrigger.getKey());
    if (existing && !replaceExisting) {
      throw new ObjectAlreadyExistsException(newTrigger);
    }
    if (!forceState) {
      boolean shouldBePaused = delegate.isTriggerGroupPaused(newTrigger.getKey().getGroup());
      if (!shouldBePaused) {
        shouldBePaused = delegate.isTriggerGroupPaused(ALL_GROUPS_PAUSED);
        if (shouldBePaused) {
          delegate.insertPausedTriggerGroup(newTrigger.getKey().getGroup());
        }
      }
      if (!shouldBePaused) {
        shouldBePaused = delegate.isJobGroupPaused(newTrigger.getJobKey().getGroup());
      }
      if (shouldBePaused && (state.equals(STATE_WAITING) || state.equals(STATE_ACQUIRED))) {
        state = STATE_PAUSED;
      }
    }
    if (job == null) {
      job = delegate.selectJob(newTrigger.getJobKey());
    }
    if (job == null) {
      throw new JobPersistenceException(
          "The job (" + newTrigger.getJobKey() + ") referenced by the trigger does not exist.");
    }
    if (job.isConcurrentExecutionDisallowed() && !recovering) {
      state = checkBlockedState(job.getKey(), state);
    }
    JobDataCodec.checkUseProperties(newTrigger.getJobDataMap(), settings.useProperties());
    if (existing) {
      delegate.updateTrigger(newTrigger, state);
    } else {
      delegate.insertTrigger(newTrigger, state);
    }
  }

  /**
   * A {@code WAITING} or {@code PAUSED} trigger of a non-concurrent job that is executing becomes
   * {@code BLOCKED} / {@code PAUSED_BLOCKED}.
   */
  protected String checkBlockedState(JobKey jobKey, String currentState) {
    if (!currentState.equals(STATE_WAITING) && !currentState.equals(STATE_PAUSED)) {
      return currentState;
    }
    boolean executing =
        delegate.selectFiredTriggerRecordsForJob(jobKey).stream()
            .anyMatch(FiredTriggerRecord::nonConcurrent);
    if (executing) {
      return STATE_PAUSED.equals(currentState) ? STATE_PAUSED_BLOCKED : STATE_BLOCKED;
    }
    return currentState;
  }

  @Override
  public boolean removeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    return executeInLock(LOCK_TRIGGER_ACCESS, () -> removeTriggerInternal(triggerKey));
  }

  /** Removes a trigger, and its job if that is not durable and has no other trigger. */
  protected boolean removeTriggerInternal(TriggerKey triggerKey) throws JobPersistenceException {
    OperableTrigger trigger = delegate.selectTrigger(triggerKey);
    JobDetail job = (trigger != null) ? delegate.selectJob(trigger.getJobKey()) : null;
    boolean removed = delegate.deleteTrigger(triggerKey);
    if (job != null && !job.isDurable() && delegate.selectNumTriggersForJob(job.getKey()) == 0) {
      delegate.deleteJob(job.getKey());
    }
    return removed;
  }

  @Override
  public boolean removeTriggers(List<TriggerKey> triggerKeys) throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          boolean allFound = true;
          for (TriggerKey triggerKey : triggerKeys) {
            allFound &= removeTriggerInternal(triggerKey);
          }
          return allFound;
        });
  }

  @Override
  public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger)
      throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          OperableTrigger oldTrigger = delegate.selectTrigger(triggerKey);
          JobDetail job = (oldTrigger != null) ? delegate.selectJob(oldTrigger.getJobKey()) : null;
          if (job == null) {
            return false;
          }
          if (!newTrigger.getJobKey().equals(job.getKey())) {
            throw new JobPersistenceException(
                "New trigger is not related to the same job as the old trigger.");
          }
          boolean removed = delegate.deleteTrigger(triggerKey);
          storeTrigger(newTrigger, job, false, STATE_WAITING, false, false);
          return removed;
        });
  }

  @Override
  public @Nullable OperableTrigger retrieveTrigger(TriggerKey triggerKey)
      throws JobPersistenceException {
    return executeWithoutLock(() -> delegate.selectTrigger(triggerKey));
  }

  @Override
  public boolean checkExists(TriggerKey triggerKey) throws JobPersistenceException {
    return executeWithoutLock(() -> delegate.triggerExists(triggerKey));
  }

  @Override
  public void clearAllSchedulingData() throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          delegate.clearAllSchedulingData();
          return null;
        });
  }

  @Override
  public int getNumberOfTriggers() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectNumTriggers);
  }

  @Override
  public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher)
      throws JobPersistenceException {
    return executeWithoutLock(() -> triggerKeys(matcher));
  }

  private Set<TriggerKey> triggerKeys(GroupMatcher<TriggerKey> matcher) {
    Set<TriggerKey> keys = new LinkedHashSet<>();
    for (String group : matchingGroups(delegate.selectTriggerGroups(), matcher)) {
      keys.addAll(delegate.selectTriggerKeys(group));
    }
    return keys;
  }

  @Override
  public List<String> getTriggerGroupNames() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectTriggerGroups);
  }

  @Override
  public List<OperableTrigger> getTriggersForJob(JobKey jobKey) throws JobPersistenceException {
    return executeWithoutLock(() -> triggersForJob(jobKey));
  }

  private List<OperableTrigger> triggersForJob(JobKey jobKey) throws JobPersistenceException {
    List<OperableTrigger> triggers = new ArrayList<>();
    for (TriggerKey key : delegate.selectTriggerKeysForJob(jobKey)) {
      OperableTrigger trigger = delegate.selectTrigger(key);
      if (trigger != null) {
        triggers.add(trigger);
      }
    }
    return triggers;
  }

  @Override
  public List<OperableTrigger> getTriggersByJobAndTriggerGroup(
      GroupMatcher<JobKey> jobMatcher, GroupMatcher<TriggerKey> triggerMatcher)
      throws JobPersistenceException {
    return executeWithoutLock(
        () -> {
          List<OperableTrigger> triggers = new ArrayList<>();
          for (TriggerKey key : triggerKeys(triggerMatcher)) {
            OperableTrigger trigger = delegate.selectTrigger(key);
            if (trigger != null && jobMatcher.isMatch(trigger.getJobKey())) {
              triggers.add(trigger);
            }
          }
          return triggers;
        });
  }

  @Override
  public TriggerState getTriggerState(TriggerKey triggerKey) throws JobPersistenceException {
    return executeWithoutLock(
        () -> {
          String state = delegate.selectTriggerState(triggerKey);
          if (state == null) {
            return TriggerState.NONE;
          }
          return switch (state) {
            case STATE_COMPLETE -> TriggerState.COMPLETE;
            case STATE_PAUSED, STATE_PAUSED_BLOCKED -> TriggerState.PAUSED;
            case STATE_ERROR -> TriggerState.ERROR;
            case STATE_BLOCKED -> TriggerState.BLOCKED;
            default -> TriggerState.NORMAL;
          };
        });
  }

  @Override
  public void resetTriggerFromErrorState(TriggerKey triggerKey) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          String newState =
              delegate.isTriggerGroupPaused(triggerKey.getGroup()) ? STATE_PAUSED : STATE_WAITING;
          delegate.updateTriggerStateFromOtherStates(triggerKey, newState, List.of(STATE_ERROR));
          log.info("Trigger {} reset from ERROR state to: {}", triggerKey, newState);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // calendars
  // ------------------------------------------------------------------

  @Override
  public void storeCalendar(
      String name, Calendar calendar, boolean replaceExisting, boolean updateTriggers)
      throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          boolean exists = delegate.calendarExists(name);
          if (exists && !replaceExisting) {
            throw new ObjectAlreadyExistsException(
                "Calendar with name '" + name + "' already exists.");
          }
          if (exists) {
            delegate.updateCalendar(name, calendar);
            if (updateTriggers) {
              for (TriggerKey key : delegate.selectTriggerKeysForCalendar(name)) {
                OperableTrigger trigger = delegate.selectTrigger(key);
                if (trigger != null) {
                  trigger.updateWithNewCalendar(
                      calendar, settings.misfireThreshold(), getSchedulingTime());
                  storeTrigger(trigger, null, true, STATE_WAITING, false, false);
                }
              }
            }
          } else {
            delegate.insertCalendar(name, calendar);
          }
          return null;
        });
  }

  @Override
  public boolean removeCalendar(String name) throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          if (!delegate.selectTriggerKeysForCalendar(name).isEmpty()) {
            throw new JobPersistenceException(
                "Calender cannot be removed if it referenced by a trigger!");
          }
          return delegate.deleteCalendar(name);
        });
  }

  @Override
  public @Nullable Calendar retrieveCalendar(String name) throws JobPersistenceException {
    return executeWithoutLock(() -> delegate.selectCalendar(name));
  }

  @Override
  public int getNumberOfCalendars() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectNumCalendars);
  }

  @Override
  public List<String> getCalendarNames() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectCalendarNames);
  }

  // ------------------------------------------------------------------
  // pause / resume
  // ------------------------------------------------------------------

  @Override
  public void pauseTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          pauseTriggerInternal(triggerKey);
          return null;
        });
  }

  private void pauseTriggerInternal(TriggerKey triggerKey) {
    String oldState = delegate.selectTriggerState(triggerKey);
    if (STATE_WAITING.equals(oldState) || STATE_ACQUIRED.equals(oldState)) {
      delegate.updateTriggerState(triggerKey, STATE_PAUSED);
    } else if (STATE_BLOCKED.equals(oldState)) {
      delegate.updateTriggerState(triggerKey, STATE_PAUSED_BLOCKED);
    }
  }

  @Override
  public Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher)
      throws JobPersistenceException {
    return executeInLock(LOCK_TRIGGER_ACCESS, () -> pauseTriggerGroups(matcher));
  }

  private Set<String> pauseTriggerGroups(GroupMatcher<TriggerKey> matcher) {
    List<String> groups = new ArrayList<>(matchingGroups(delegate.selectTriggerGroups(), matcher));
    // an exact match also pauses a group that does not exist yet
    if (matcher.getCompareWithOperator() == StringOperatorName.EQUALS
        && !groups.contains(matcher.getCompareToValue())) {
      groups.add(matcher.getCompareToValue());
    }
    for (String group : groups) {
      delegate.updateTriggerGroupStateFromOtherStates(
          group, STATE_PAUSED, List.of(STATE_ACQUIRED, STATE_WAITING));
      delegate.updateTriggerGroupStateFromOtherStates(
          group, STATE_PAUSED_BLOCKED, List.of(STATE_BLOCKED));
      if (!delegate.isTriggerGroupPaused(group)) {
        delegate.insertPausedTriggerGroup(group);
      }
    }
    return new LinkedHashSet<>(groups);
  }

  @Override
  public void pauseJob(JobKey jobKey) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          for (TriggerKey key : delegate.selectTriggerKeysForJob(jobKey)) {
            pauseTriggerInternal(key);
          }
          return null;
        });
  }

  @Override
  public Collection<String> pauseJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          List<String> groups =
              new ArrayList<>(matchingGroups(delegate.selectJobGroups(), matcher));
          // an exact match also pauses jobs added to the group later (as Quartz's in-memory store
          // did)
          if (matcher.getCompareWithOperator() == StringOperatorName.EQUALS
              && !groups.contains(matcher.getCompareToValue())) {
            groups.add(matcher.getCompareToValue());
          }
          for (String group : groups) {
            delegate.insertPausedJobGroup(group);
            for (JobKey jobKey : delegate.selectJobKeys(group)) {
              for (TriggerKey key : delegate.selectTriggerKeysForJob(jobKey)) {
                pauseTriggerInternal(key);
              }
            }
          }
          return new LinkedHashSet<>(groups);
        });
  }

  @Override
  public void resumeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          resumeTriggerInternal(triggerKey);
          return null;
        });
  }

  private void resumeTriggerInternal(TriggerKey triggerKey) throws JobPersistenceException {
    OperableTrigger trigger = delegate.selectTrigger(triggerKey);
    if (trigger == null || trigger.getNextFireTime() == null) {
      return;
    }
    String state = delegate.selectTriggerState(triggerKey);
    boolean blocked = STATE_PAUSED_BLOCKED.equals(state);
    String newState = checkBlockedState(trigger.getJobKey(), STATE_WAITING);
    boolean misfired = false;
    if (schedulerRunning && trigger.getNextFireTime().isBefore(getSchedulingTime())) {
      misfired = updateMisfiredTrigger(triggerKey, newState, true);
    }
    if (!misfired) {
      delegate.updateTriggerStateFromOtherStates(
          triggerKey, newState, List.of(blocked ? STATE_PAUSED_BLOCKED : STATE_PAUSED));
    }
  }

  @Override
  public Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher)
      throws JobPersistenceException {
    return executeInLock(LOCK_TRIGGER_ACCESS, () -> resumeTriggerGroups(matcher));
  }

  private Set<String> resumeTriggerGroups(GroupMatcher<TriggerKey> matcher)
      throws JobPersistenceException {
    for (String group : delegate.selectPausedTriggerGroups()) {
      if (!ALL_GROUPS_PAUSED.equals(group)
          && matcher.getCompareWithOperator().evaluate(group, matcher.getCompareToValue())) {
        delegate.deletePausedTriggerGroup(group);
      }
    }
    Set<String> groups = new LinkedHashSet<>();
    for (TriggerKey key : triggerKeys(matcher)) {
      resumeTriggerInternal(key);
      groups.add(key.getGroup());
    }
    return groups;
  }

  @Override
  public Set<String> getPausedTriggerGroups() throws JobPersistenceException {
    return executeWithoutLock(
        () -> {
          Set<String> groups = new LinkedHashSet<>(delegate.selectPausedTriggerGroups());
          groups.remove(ALL_GROUPS_PAUSED);
          return groups;
        });
  }

  @Override
  public void resumeJob(JobKey jobKey) throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          for (TriggerKey key : delegate.selectTriggerKeysForJob(jobKey)) {
            resumeTriggerInternal(key);
          }
          return null;
        });
  }

  @Override
  public Collection<String> resumeJobs(GroupMatcher<JobKey> matcher)
      throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          Set<String> groups = new LinkedHashSet<>();
          for (String group : delegate.selectPausedJobGroups()) {
            if (matcher.getCompareWithOperator().evaluate(group, matcher.getCompareToValue())) {
              delegate.deletePausedJobGroup(group);
              groups.add(group);
            }
          }
          for (JobKey jobKey : jobKeys(matcher)) {
            for (TriggerKey key : delegate.selectTriggerKeysForJob(jobKey)) {
              resumeTriggerInternal(key);
            }
            groups.add(jobKey.getGroup());
          }
          return groups;
        });
  }

  @Override
  public void pauseAll() throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          for (String group : delegate.selectTriggerGroups()) {
            pauseTriggerGroups(GroupMatcher.triggerGroupEquals(group));
          }
          if (!delegate.isTriggerGroupPaused(ALL_GROUPS_PAUSED)) {
            delegate.insertPausedTriggerGroup(ALL_GROUPS_PAUSED);
          }
          return null;
        });
  }

  @Override
  public void resumeAll() throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          for (String group : delegate.selectPausedJobGroups()) {
            delegate.deletePausedJobGroup(group);
          }
          for (String group : delegate.selectTriggerGroups()) {
            resumeTriggerGroups(GroupMatcher.triggerGroupEquals(group));
          }
          delegate.deletePausedTriggerGroup(ALL_GROUPS_PAUSED);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // acquisition, firing, completion
  // ------------------------------------------------------------------

  @Override
  public List<OperableTrigger> acquireNextTriggers(
      Instant noLaterThan, int maxCount, Duration timeWindow) throws JobPersistenceException {
    if (timeWindow.isNegative()) {
      throw new IllegalArgumentException("timeWindow must not be negative");
    }
    String lockName = (acquireTriggersWithinLock() || maxCount > 1) ? LOCK_TRIGGER_ACCESS : null;
    return executeInLock(
        lockName, () -> acquireNextTriggersInternal(noLaterThan, maxCount, timeWindow));
  }

  private List<OperableTrigger> acquireNextTriggersInternal(
      Instant noLaterThan, int maxCount, Duration timeWindow) throws JobPersistenceException {
    List<OperableTrigger> acquired = new ArrayList<>();
    Set<JobKey> acquiredNonConcurrentJobs = new HashSet<>();
    final int maxAttempts = 3;
    for (int attempt = 1; ; attempt++) {
      List<TriggerKey> keys =
          delegate.selectTriggersToAcquire(
              noLaterThan.plus(timeWindow), getMisfireTime(), maxCount);
      if (keys.isEmpty()) {
        return acquired;
      }
      Instant batchEnd = noLaterThan;
      for (TriggerKey triggerKey : keys) {
        OperableTrigger nextTrigger = delegate.selectTrigger(triggerKey);
        if (nextTrigger == null) {
          continue;
        }
        JobDetail job;
        try {
          job = delegate.selectJob(nextTrigger.getJobKey());
        } catch (JobPersistenceException ex) {
          log.error("Error retrieving job, setting trigger state to ERROR.", ex);
          delegate.updateTriggerState(triggerKey, STATE_ERROR);
          continue;
        }
        if (job == null) {
          continue;
        }
        if (job.isConcurrentExecutionDisallowed() && !acquiredNonConcurrentJobs.add(job.getKey())) {
          continue;
        }
        Instant nextFireTime = nextTrigger.getNextFireTime();
        if (nextFireTime == null) {
          log.warn(
              "Trigger {} returned null on nextFireTime and yet still exists in the store!",
              nextTrigger.getKey());
          continue;
        }
        if (nextFireTime.isAfter(batchEnd)) {
          break;
        }
        // The fired-trigger record is written before the trigger is marked ACQUIRED: a store
        // without transactions (Redis) runs the two writes separately, and an instance that
        // stops between them (killed, or interrupted at shutdown after the server applied the
        // state change) must leave a record, or cluster recovery never frees the trigger and it
        // never fires again. A record without the state change is harmless: recovery deletes it.
        String fireInstanceId = newFireInstanceId();
        delegate.insertFiredTrigger(
            new FiredTriggerRecord(
                fireInstanceId,
                instanceId,
                triggerKey,
                nextTrigger.getJobKey(),
                STATE_ACQUIRED,
                Instant.now(),
                nextFireTime,
                nextTrigger.getPriority(),
                false,
                false));
        // somebody else may have acquired it meanwhile: only a successful WAITING -> ACQUIRED
        // counts
        if (delegate.updateTriggerStateFromOtherStates(
                triggerKey, STATE_ACQUIRED, List.of(STATE_WAITING))
            <= 0) {
          delegate.deleteFiredTrigger(fireInstanceId);
          continue;
        }
        nextTrigger.setFireInstanceId(fireInstanceId);
        if (acquired.isEmpty()) {
          Instant now = getSchedulingTime();
          batchEnd = (nextFireTime.isAfter(now) ? nextFireTime : now).plus(timeWindow);
        }
        acquired.add(nextTrigger);
      }
      if (acquired.isEmpty() && attempt < maxAttempts) {
        continue;
      }
      return acquired;
    }
  }

  @Override
  public void releaseAcquiredTrigger(OperableTrigger trigger) {
    retryExecuteInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          delegate.updateTriggerStateFromOtherStates(
              trigger.getKey(), STATE_WAITING, List.of(STATE_ACQUIRED));
          delegate.updateTriggerStateFromOtherStates(
              trigger.getKey(), STATE_WAITING, List.of(STATE_BLOCKED));
          if (trigger.getFireInstanceId() != null) {
            delegate.deleteFiredTrigger(trigger.getFireInstanceId());
          }
          return null;
        });
  }

  @Override
  public List<TriggerFiredResult> triggersFired(List<OperableTrigger> triggers)
      throws JobPersistenceException {
    return executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          List<TriggerFiredResult> results = new ArrayList<>();
          for (OperableTrigger trigger : triggers) {
            TriggerFiredResult result;
            try {
              result = new TriggerFiredResult(triggerFired(trigger));
            } catch (JobPersistenceException | RuntimeException ex) {
              result = new TriggerFiredResult(ex);
            }
            results.add(result);
          }
          return results;
        });
  }

  private @Nullable TriggerFiredBundle triggerFired(OperableTrigger trigger)
      throws JobPersistenceException {
    // make sure the trigger wasn't deleted, paused, or completed since it was acquired
    if (!STATE_ACQUIRED.equals(delegate.selectTriggerState(trigger.getKey()))) {
      return null;
    }
    JobDetail job;
    try {
      job = delegate.selectJob(trigger.getJobKey());
      if (job == null) {
        return null;
      }
    } catch (JobPersistenceException ex) {
      log.error("Error retrieving job, setting trigger state to ERROR.", ex);
      delegate.updateTriggerState(trigger.getKey(), STATE_ERROR);
      throw ex;
    }
    Calendar calendar = null;
    if (trigger.getCalendarName() != null) {
      calendar = delegate.selectCalendar(trigger.getCalendarName());
      if (calendar == null) {
        return null;
      }
    }
    // an acquired trigger has its fire instance id and next fire time
    delegate.updateFiredTrigger(
        new FiredTriggerRecord(
            Objects.requireNonNull(trigger.getFireInstanceId()),
            instanceId,
            trigger.getKey(),
            job.getKey(),
            STATE_EXECUTING,
            Instant.now(),
            Objects.requireNonNull(trigger.getNextFireTime()),
            trigger.getPriority(),
            job.isConcurrentExecutionDisallowed(),
            job.requestsRecovery()));

    Instant prevFireTime = trigger.getPreviousFireTime();
    // update the trigger's next fire time
    trigger.triggered(calendar);

    String state = STATE_WAITING;
    boolean force = true;
    if (job.isConcurrentExecutionDisallowed()) {
      state = STATE_BLOCKED;
      force = false;
      delegate.updateTriggerStatesForJobFromOtherState(job.getKey(), STATE_BLOCKED, STATE_WAITING);
      delegate.updateTriggerStatesForJobFromOtherState(job.getKey(), STATE_BLOCKED, STATE_ACQUIRED);
      delegate.updateTriggerStatesForJobFromOtherState(
          job.getKey(), STATE_PAUSED_BLOCKED, STATE_PAUSED);
    }
    if (trigger.getNextFireTime() == null) {
      state = STATE_COMPLETE;
      force = true;
    }
    storeTrigger(trigger, job, true, state, force, false);
    job.getJobDataMap().clearDirtyFlag();

    return new TriggerFiredBundle(
        job,
        trigger,
        calendar,
        trigger.getKey().getGroup().equals(Scheduler.DEFAULT_RECOVERY_GROUP),
        getSchedulingTime(),
        // the time it has just been fired for
        Objects.requireNonNull(trigger.getPreviousFireTime()),
        prevFireTime,
        trigger.getNextFireTime());
  }

  @Override
  public void triggeredJobComplete(
      OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction triggerInstCode) {
    retryExecuteInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          triggeredJobCompleteInternal(trigger, jobDetail, triggerInstCode);
          return null;
        });
  }

  private void triggeredJobCompleteInternal(
      OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction triggerInstCode)
      throws JobPersistenceException {
    switch (triggerInstCode) {
      case DELETE_TRIGGER -> {
        if (trigger.getNextFireTime() == null) {
          // double check for possible reschedule within job execution, which would cancel the need
          // to delete
          OperableTrigger stored = delegate.selectTrigger(trigger.getKey());
          if (stored != null && stored.getNextFireTime() == null) {
            removeTriggerInternal(trigger.getKey());
          }
        } else {
          removeTriggerInternal(trigger.getKey());
          signalSchedulingChangeOnCompletion(null);
        }
      }
      case SET_TRIGGER_COMPLETE -> {
        delegate.updateTriggerState(trigger.getKey(), STATE_COMPLETE);
        signalSchedulingChangeOnCompletion(null);
      }
      case SET_TRIGGER_ERROR -> {
        log.info("Trigger {} set to ERROR state.", trigger.getKey());
        delegate.updateTriggerState(trigger.getKey(), STATE_ERROR);
        signalSchedulingChangeOnCompletion(null);
      }
      case SET_ALL_JOB_TRIGGERS_COMPLETE -> {
        delegate.updateTriggerStatesForJob(trigger.getJobKey(), STATE_COMPLETE);
        signalSchedulingChangeOnCompletion(null);
      }
      case SET_ALL_JOB_TRIGGERS_ERROR -> {
        log.info("All triggers of Job {} set to ERROR state.", trigger.getJobKey());
        delegate.updateTriggerStatesForJob(trigger.getJobKey(), STATE_ERROR);
        signalSchedulingChangeOnCompletion(null);
      }
      default -> {
        // NOOP, RE_EXECUTE_JOB
      }
    }
    if (jobDetail.isConcurrentExecutionDisallowed()) {
      delegate.updateTriggerStatesForJobFromOtherState(
          jobDetail.getKey(), STATE_WAITING, STATE_BLOCKED);
      delegate.updateTriggerStatesForJobFromOtherState(
          jobDetail.getKey(), STATE_PAUSED, STATE_PAUSED_BLOCKED);
      signalSchedulingChangeOnCompletion(null);
    }
    if (jobDetail.isPersistJobDataAfterExecution()
        && jobDetail.getJobDataMap().isDirty()
        && delegate.jobExists(jobDetail.getKey())) {
      JobDataCodec.checkUseProperties(jobDetail.getJobDataMap(), settings.useProperties());
      delegate.updateJobData(jobDetail);
    }
    if (trigger.getFireInstanceId() != null) {
      delegate.deleteFiredTrigger(trigger.getFireInstanceId());
    }
  }

  // ------------------------------------------------------------------
  // misfires and recovery
  // ------------------------------------------------------------------

  /** Result of a misfire pass. */
  public record MisfireResult(
      boolean hasMoreMisfiredTriggers,
      int processedMisfiredTriggerCount,
      @Nullable Instant earliestNewTime) {

    static final MisfireResult NO_OP = new MisfireResult(false, 0, null);
  }

  /**
   * Applies the misfire instruction of every trigger that missed its fire time by more than the
   * misfire threshold (at most {@code maxMisfiresToHandleAtATime} per call). The misfire handler
   * thread calls this periodically.
   */
  public MisfireResult recoverMisfiredTriggers() throws JobPersistenceException {
    if (settings.doubleCheckLockMisfireHandler()) {
      // peek before taking the lock
      int count =
          executeWithoutLock(
              () -> delegate.countMisfiredTriggersInState(STATE_WAITING, getMisfireTime()));
      if (count == 0) {
        log.debug("Found 0 triggers that missed their scheduled fire-time.");
        return MisfireResult.NO_OP;
      }
    }
    return executeInLock(LOCK_TRIGGER_ACCESS, () -> recoverMisfiredJobs(false));
  }

  /**
   * Apply the misfire instructions of triggers whose next fire time is before {@link
   * #getMisfireTime()}; called with the trigger lock held.
   *
   * @param recovering {@code true} at startup or cluster recovery: handle all misfired triggers,
   *     not only {@code maxMisfiresToHandleAtATime}
   * @return how many were handled, whether more remain, and the earliest new fire time
   * @throws JobPersistenceException if the store fails
   */
  protected MisfireResult recoverMisfiredJobs(boolean recovering) throws JobPersistenceException {
    // when recovering, handle all misfired triggers right away
    int max = recovering ? -1 : settings.maxMisfiresToHandleAtATime();
    List<TriggerKey> misfired =
        new ArrayList<>(
            delegate.selectMisfiredTriggersInState(
                STATE_WAITING, getMisfireTime(), (max < 0) ? -1 : max + 1));
    boolean hasMore = max > 0 && misfired.size() > max;
    if (hasMore) {
      misfired = misfired.subList(0, max);
      log.info(
          "Handling the first {} triggers that missed their scheduled fire-time. "
              + "More misfired triggers remain to be processed.",
          misfired.size());
    } else if (!misfired.isEmpty()) {
      log.info("Handling {} trigger(s) that missed their scheduled fire-time.", misfired.size());
    } else {
      log.debug("Found 0 triggers that missed their scheduled fire-time.");
      return MisfireResult.NO_OP;
    }
    Instant earliestNewTime = null;
    for (TriggerKey key : misfired) {
      OperableTrigger trigger;
      try {
        trigger = delegate.selectTrigger(key);
      } catch (JobPersistenceException | RuntimeException ex) {
        log.error("Error retrieving the misfired trigger: {}", key, ex);
        continue;
      }
      if (trigger == null) {
        continue;
      }
      try {
        doUpdateOfMisfiredTrigger(trigger, false, STATE_WAITING, recovering);
      } catch (JobPersistenceException | RuntimeException ex) {
        log.error("Error updating misfired trigger: {}", key, ex);
        continue;
      }
      Instant next = trigger.getNextFireTime();
      if (next != null && (earliestNewTime == null || next.isBefore(earliestNewTime))) {
        earliestNewTime = next;
      }
    }
    return new MisfireResult(hasMore, misfired.size(), earliestNewTime);
  }

  /**
   * Apply the misfire instruction of one trigger, if it has misfired.
   *
   * @param triggerKey the trigger
   * @param newStateIfNotComplete the state to store if the trigger fires again
   * @param forceState whether to store that state whatever the trigger's current state
   * @return {@code false} if the trigger is gone or has not misfired
   * @throws JobPersistenceException if the store fails
   */
  protected boolean updateMisfiredTrigger(
      TriggerKey triggerKey, String newStateIfNotComplete, boolean forceState)
      throws JobPersistenceException {
    OperableTrigger trigger = delegate.selectTrigger(triggerKey);
    Instant nextFireTime = (trigger != null) ? trigger.getNextFireTime() : null;
    if (trigger == null || nextFireTime == null || nextFireTime.isAfter(getMisfireTime())) {
      return false;
    }
    doUpdateOfMisfiredTrigger(trigger, forceState, newStateIfNotComplete, false);
    return true;
  }

  private void doUpdateOfMisfiredTrigger(
      OperableTrigger trigger, boolean forceState, String newStateIfNotComplete, boolean recovering)
      throws JobPersistenceException {
    Calendar calendar = null;
    if (trigger.getCalendarName() != null) {
      calendar = delegate.selectCalendar(trigger.getCalendarName());
    }
    signaler.notifyTriggerListenersMisfired(trigger);
    trigger.updateAfterMisfire(calendar, getSchedulingTime());
    if (trigger.getNextFireTime() == null) {
      storeTrigger(trigger, null, true, STATE_COMPLETE, forceState, recovering);
      signaler.notifySchedulerListenersFinalized(trigger);
    } else {
      storeTrigger(trigger, null, true, newStateIfNotComplete, forceState, recovering);
    }
  }

  /**
   * At startup of a non-clustered store: frees acquired/blocked triggers, applies misfires,
   * schedules recovery of interrupted jobs that request it, and cleans up.
   */
  protected void recoverJobs() throws JobPersistenceException {
    executeInLock(
        LOCK_TRIGGER_ACCESS,
        () -> {
          int freed =
              delegate.updateTriggerStatesFromOtherStates(
                  STATE_WAITING, List.of(STATE_ACQUIRED, STATE_BLOCKED));
          freed +=
              delegate.updateTriggerStatesFromOtherStates(
                  STATE_PAUSED, List.of(STATE_PAUSED_BLOCKED));
          log.info("Freed {} triggers from 'acquired' / 'blocked' state.", freed);

          recoverMisfiredJobs(true);

          List<FiredTriggerRecord> recovering =
              delegate.selectFiredTriggerRecordsForInstance(instanceId).stream()
                  .filter(FiredTriggerRecord::requestsRecovery)
                  .toList();
          log.info(
              "Recovering {} jobs that were in-progress at the time of the last shut-down.",
              recovering.size());
          for (FiredTriggerRecord record : recovering) {
            if (delegate.jobExists(record.jobKey())) {
              storeTrigger(recoveryTrigger(record), null, false, STATE_WAITING, false, true);
            }
          }
          log.info("Recovery complete.");

          List<TriggerKey> complete = delegate.selectTriggerKeysInState(STATE_COMPLETE);
          for (TriggerKey key : complete) {
            removeTriggerInternal(key);
          }
          log.info("Removed {} 'complete' triggers.", complete.size());

          int removed = delegate.deleteFiredTriggers();
          log.info("Removed {} stale fired job entries.", removed);
          return null;
        });
  }

  /** A one-shot trigger that re-runs an interrupted job, flagged as recovering. */
  protected OperableTrigger recoveryTrigger(FiredTriggerRecord record)
      throws JobPersistenceException {
    TriggerKey original = record.triggerKey();
    SimpleTriggerImpl trigger =
        new SimpleTriggerImpl(
            "recover_" + record.instanceId() + "_" + RECOVERY_COUNTER.incrementAndGet(),
            Scheduler.DEFAULT_RECOVERY_GROUP,
            record.scheduledTime());
    trigger.setJobKey(record.jobKey());
    trigger.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY);
    trigger.setPriority(record.priority());
    JobDataMap data = delegate.selectTriggerJobData(original);
    data.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME, original.getName());
    data.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP, original.getGroup());
    data.put(
        Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS,
        String.valueOf(record.firedTime().toEpochMilli()));
    data.put(
        Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS,
        String.valueOf(record.scheduledTime().toEpochMilli()));
    trigger.setJobDataMap(data);
    trigger.computeFirstFireTime(null);
    return trigger;
  }

  // ------------------------------------------------------------------
  // clustering
  // ------------------------------------------------------------------

  /** The check-in records of all instances (for tests and diagnostics). */
  public List<SchedulerStateRecord> getSchedulerStates() throws JobPersistenceException {
    return executeWithoutLock(delegate::selectSchedulerStateRecords);
  }

  /**
   * Checks in, and recovers the work of instances that stopped checking in.
   *
   * @return whether anything was recovered
   */
  protected boolean doCheckin() throws JobPersistenceException {
    boolean recovered = false;
    List<SchedulerStateRecord> failed = List.of();
    // other than the first time, check in first to see whether there is work to do before taking
    // the locks
    if (!firstCheckIn) {
      failed = executeWithoutLock(this::clusterCheckIn);
    }
    if (firstCheckIn || !failed.isEmpty()) {
      recovered =
          executeInLock(
              LOCK_STATE_ACCESS,
              () -> {
                // now that we own the lock, make sure we still have work to do
                List<SchedulerStateRecord> stillFailed =
                    firstCheckIn ? clusterCheckIn() : findFailedInstances();
                if (stillFailed.isEmpty()) {
                  return false;
                }
                executeInLock(
                    LOCK_TRIGGER_ACCESS,
                    () -> {
                      clusterRecover(stillFailed);
                      return null;
                    });
                return true;
              });
    }
    firstCheckIn = false;
    return recovered;
  }

  private List<SchedulerStateRecord> findFailedInstances() {
    List<SchedulerStateRecord> failed = new ArrayList<>();
    boolean foundThisScheduler = false;
    Instant now = Instant.now();
    List<SchedulerStateRecord> states = delegate.selectSchedulerStateRecords();
    for (SchedulerStateRecord record : states) {
      if (record.instanceId().equals(instanceId)) {
        foundThisScheduler = true;
        if (firstCheckIn) {
          // recover what a previous run of this instance left behind
          failed.add(record);
        }
      } else if (failedIfAfter(record, now).isBefore(now)) {
        failed.add(record);
      }
    }
    if (firstCheckIn) {
      Set<String> orphaned = new HashSet<>(delegate.selectFiredTriggerInstanceIds());
      states.forEach(record -> orphaned.remove(record.instanceId()));
      for (String id : orphaned) {
        log.warn("Found orphaned fired triggers for instance: {}", id);
        failed.add(new SchedulerStateRecord(id, null, null));
      }
    }
    if (!foundThisScheduler && !firstCheckIn) {
      log.warn(
          "This scheduler instance ({}) is still active but was recovered by another instance in the "
              + "cluster. This may cause inconsistent behavior.",
          instanceId);
    }
    return failed;
  }

  private Instant failedIfAfter(SchedulerStateRecord record, Instant now) {
    Duration sinceOwnCheckin = Duration.between(lastCheckin, now);
    Duration interval = record.checkinInterval();
    Duration window =
        (interval != null && interval.compareTo(sinceOwnCheckin) > 0) ? interval : sinceOwnCheckin;
    return Objects.requireNonNull(record.lastCheckin(), "lastCheckin")
        .plus(window)
        .plus(settings.clusterCheckinMisfireThreshold());
  }

  private List<SchedulerStateRecord> clusterCheckIn() {
    List<SchedulerStateRecord> failed = findFailedInstances();
    lastCheckin = Instant.now();
    delegate.upsertSchedulerState(instanceId, lastCheckin, settings.clusterCheckinInterval());
    return failed;
  }

  private void clusterRecover(List<SchedulerStateRecord> failedInstances)
      throws JobPersistenceException {
    log.warn("ClusterManager: detected {} failed or restarted instances.", failedInstances.size());
    for (SchedulerStateRecord failed : failedInstances) {
      String failedId = failed.instanceId();
      log.info("ClusterManager: Scanning for instance \"{}\"'s failed in-progress jobs.", failedId);
      int acquiredCount = 0;
      int recoveredCount = 0;
      int otherCount = 0;
      Set<TriggerKey> triggerKeys = new HashSet<>();
      for (FiredTriggerRecord record : delegate.selectFiredTriggerRecordsForInstance(failedId)) {
        TriggerKey triggerKey = record.triggerKey();
        JobKey jobKey = record.jobKey();
        triggerKeys.add(triggerKey);
        if (STATE_ACQUIRED.equals(record.state())) {
          // release acquired triggers, unless a live instance acquired the trigger since: every
          // instance writes its fired-trigger record before marking a trigger ACQUIRED, so a
          // trigger it holds always has its record (this one may be left over, for example from
          // an interrupted clean-up after losing the trigger to that instance)
          boolean heldByAnother =
              delegate.selectFiredTriggerRecordsForTrigger(triggerKey).stream()
                  .anyMatch((other) -> !other.instanceId().equals(failedId));
          if (!heldByAnother) {
            delegate.updateTriggerStateFromOtherStates(
                triggerKey, STATE_WAITING, List.of(STATE_ACQUIRED));
          }
          acquiredCount++;
        } else if (record.requestsRecovery()) {
          // re-run jobs that request recovery
          if (delegate.jobExists(jobKey)) {
            storeTrigger(recoveryTrigger(record), null, false, STATE_WAITING, false, true);
            recoveredCount++;
          } else {
            log.warn(
                "ClusterManager: failed job '{}' no longer exists, cannot schedule recovery.",
                jobKey);
            otherCount++;
          }
        } else {
          otherCount++;
        }
        // free up a non-concurrent job's triggers
        if (record.nonConcurrent()) {
          delegate.updateTriggerStatesForJobFromOtherState(jobKey, STATE_WAITING, STATE_BLOCKED);
          delegate.updateTriggerStatesForJobFromOtherState(
              jobKey, STATE_PAUSED, STATE_PAUSED_BLOCKED);
        }
      }
      delegate.deleteFiredTriggers(failedId);

      // remove COMPLETE triggers whose last fired entry we just deleted
      int completeCount = 0;
      for (TriggerKey triggerKey : triggerKeys) {
        if (STATE_COMPLETE.equals(delegate.selectTriggerState(triggerKey))
            && delegate.selectFiredTriggerRecordsForTrigger(triggerKey).isEmpty()
            && removeTriggerInternal(triggerKey)) {
          completeCount++;
        }
      }
      logIfNonZero(acquiredCount, "ClusterManager: ......Freed {} acquired trigger(s).");
      logIfNonZero(completeCount, "ClusterManager: ......Deleted {} complete triggers(s).");
      logIfNonZero(
          recoveredCount, "ClusterManager: ......Scheduled {} recoverable job(s) for recovery.");
      logIfNonZero(otherCount, "ClusterManager: ......Cleaned-up {} other failed job(s).");
      if (!failedId.equals(instanceId)) {
        delegate.deleteSchedulerState(failedId);
      }
    }
  }

  private void logIfNonZero(int value, String message) {
    if (value > 0) {
      log.info(message, value);
    }
  }

  // ------------------------------------------------------------------
  // background threads
  // ------------------------------------------------------------------

  private abstract class StoreThread extends Thread {

    volatile boolean halted;

    int numFails;

    StoreThread(String suffix) {
      setName("QuartzScheduler_" + instanceName + "-" + instanceId + "_" + suffix);
      setDaemon(settings.makeThreadsDaemons());
      if (initializersLoader != null) {
        setContextClassLoader(initializersLoader);
      }
    }

    void halt() {
      halted = true;
      interrupt();
    }

    boolean pause(Duration duration) {
      try {
        Thread.sleep(duration);
        return true;
      } catch (InterruptedException ex) {
        return false;
      }
    }
  }

  private final class MisfireHandler extends StoreThread {

    MisfireHandler() {
      super("MisfireHandler");
    }

    private MisfireResult manage() {
      try {
        log.debug("MisfireHandler: scanning for misfires...");
        MisfireResult result = recoverMisfiredTriggers();
        numFails = 0;
        return result;
      } catch (Exception ex) {
        if (numFails % 4 == 0) {
          log.error("MisfireHandler: Error handling misfires: {}", ex.getMessage(), ex);
        }
        numFails++;
      }
      return MisfireResult.NO_OP;
    }

    @Override
    public void run() {
      while (!halted) {
        long start = System.currentTimeMillis();
        MisfireResult result = manage();
        if (result.processedMisfiredTriggerCount() > 0) {
          signaler.signalSchedulingChange(result.earliestNewTime());
        }
        if (halted) {
          break;
        }
        long sleep = 50L; // at least a short pause to help balance threads
        if (!result.hasMoreMisfiredTriggers()) {
          sleep = settings.misfireThreshold().toMillis() - (System.currentTimeMillis() - start);
          if (sleep <= 0) {
            sleep = 50L;
          }
          if (numFails > 0) {
            sleep = Math.max(settings.retryInterval().toMillis(), sleep);
          }
        }
        if (!pause(Duration.ofMillis(sleep))) {
          break;
        }
      }
    }
  }

  private final class ClusterManager extends StoreThread {

    ClusterManager() {
      super("ClusterManager");
      setPriority(Thread.NORM_PRIORITY + 2);
    }

    void initialize() {
      manage();
      start();
    }

    private boolean manage() {
      boolean recovered = false;
      try {
        recovered = doCheckin();
        numFails = 0;
        log.debug("ClusterManager: Check-in complete.");
      } catch (Exception ex) {
        if (numFails % 4 == 0) {
          log.error("ClusterManager: Error managing cluster: {}", ex.getMessage(), ex);
        }
        numFails++;
      }
      return recovered;
    }

    @Override
    public void run() {
      while (!halted) {
        long sleep =
            settings.clusterCheckinInterval().toMillis()
                - Duration.between(lastCheckin, Instant.now()).toMillis();
        if (sleep <= 0) {
          sleep = 100L;
        }
        if (numFails > 0) {
          sleep = Math.max(settings.retryInterval().toMillis(), sleep);
        }
        if (!pause(Duration.ofMillis(sleep))) {
          break;
        }
        if (!halted && manage()) {
          signaler.signalSchedulingChange(null);
        }
      }
    }
  }
}
