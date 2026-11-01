# Changelog

Changes to the public API, behaviour and storage format, by release. Breaking changes are marked
**Breaking**.

## 1.0.0-rc2 (2026-11-01)

### Job exceptions

- When a job throws anything other than a `JobExecutionException`, job and trigger listeners now
  get a `JobExecutionException` whose `getCause()` is exactly what the job threw, with the message
  `Job <group.name> threw an unhandled exception: <class>: <message>`. Before, the cause was a
  `JobExecutionProcessException` wrapping the job's throwable, and the message was always
  `org.quartz.core.JobExecutionProcessException: Job threw an unhandled exception`. Scheduler
  listeners still get the `JobExecutionProcessException` (with the execution context) through
  `schedulerError`. A `JobExecutionException` thrown by the job is passed on unchanged, as before.
  **Breaking** for listeners that unwrapped two levels of causes.

### Recovery

- New job-data keys on recovery triggers: `Scheduler.FAILED_JOB_ORIGINAL_FIRE_INSTANCE_ID` (the
  `getFireInstanceId()` of the interrupted execution) and
  `Scheduler.FAILED_JOB_ORIGINAL_SCHEDULER_INSTANCE_ID` (the instance that ran it).
- An execution's fire time and its fired-trigger record now share one instant, taken from the
  scheduler's `Clock` and truncated to milliseconds, so a recovery trigger's
  `FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS` equals the interrupted execution's
  `getFireTime()`. Before, the record used a separate `Instant.now()`.

### Manual fires

- New `Scheduler.triggerNow(TriggerKey)`: runs the trigger's job once, now, with a copy of the
  trigger's job data and its priority, and leaves the trigger unchanged. The one-off trigger's job
  data holds `Scheduler.MANUAL_FIRE_OF_TRIGGER` = the source trigger's key (`group.name`). It
  throws a `JobPersistenceException` if the trigger does not exist.
- **Breaking:** the one-off triggers of `triggerJob(...)` and `triggerNow(...)` are now in the
  group `Scheduler.MANUAL_TRIGGER_GROUP` (`MANUAL_TRIGGER`), not `DEFAULT`, so listeners and
  history can tell manual fires from scheduled ones. They start at the scheduler `Clock`'s now.
  Manual fires follow the pause state of `MANUAL_TRIGGER` (and of the job's group), not of
  `DEFAULT`: pause `MANUAL_TRIGGER` to hold them.
- Removed `QuartzScheduler.triggerJob(OperableTrigger)` (internal class, unused).

### Interrupting jobs

- `Scheduler.interrupt(JobKey)` now attempts every executing instance of the job. If any could
  not be interrupted (not an `InterruptableJob`, or `interrupt()` threw), it throws one
  `UnableToInterruptJobException` at the end, with each failure as a suppressed exception. Before,
  it stopped at the first instance that could not be interrupted. An exception thrown by
  `InterruptableJob.interrupt()` is now reported as an `UnableToInterruptJobException` with that
  exception as its cause.
- New `Scheduler.getClusterExecutingJobs()`: the executions in progress on every instance of the
  cluster, from the store's fired-trigger records, as `ExecutingJob` (fire instance id, scheduler
  instance id, job key, trigger key, fired time, scheduled fire time, requests recovery, interrupt
  requested).
- New `Scheduler.interruptAnywhere(String fireInstanceId)`: interrupts the execution at once if it
  runs on this instance; otherwise flags its fired-trigger record, and the instance running it
  interrupts it at its next cluster check-in (within `cluster-checkin-interval`). It looks up
  only that execution's fired-trigger record.
- New `JobDetail.isInterruptible()`: whether the job class implements `InterruptableJob`.
- SPI: `JobStore.getExecutingJobs()`, `JobStore.getExecutingJob(String)`,
  `JobStore.requestInterrupt(String)`, `SchedulerSignaler.interruptRequested(String)` (default:
  does nothing), `StoreDelegate.selectFiredTriggerRecords()`,
  `StoreDelegate.selectFiredTriggerRecord(String)`,
  `StoreDelegate.selectFiredTriggerRecordsInState(String)`,
  `StoreDelegate.selectInterruptRequestedFireInstanceIds(String)` and
  `StoreDelegate.updateFiredTriggerInterruptRequested(String)`; `FiredTriggerRecord` has a new
  `interruptRequested` component. Stores record the flag as `interruptRequested` on the
  fired-trigger document (MongoDB) or hash (Redis); Redis also keeps an
  `interrupts_by_instance:<id>` set, so a check-in finds requests with one read.

### Cluster instances

- The new cluster methods (`getSchedulerInstances()`, `getClusterExecutingJobs()`,
  `interruptAnywhere()`) throw "The Scheduler has been shutdown." after shutdown, like the other
  `Scheduler` methods.
- New `Scheduler.getSchedulerInstances()`: the scheduler instances known to the job store, as
  `SchedulerInstance` (instance id, last check-in, check-in interval, `alive`, `current`).
  `alive` uses the one rule the store applies before recovering an instance (last check-in +
  interval + `cluster-checkin-misfire-threshold`), for every instance including the calling one,
  and `current` marks the calling instance. A
  store that is not clustered lists only the current instance, without check-in times. Use this
  rather than reading `qrtz_schedulers` yourself: it ships in the same release as the switch of
  stored instants to BSON dates (below), which breaks readers of the raw documents.
- SPI: `JobStore.getSchedulerInstances()`.
- **Breaking:** `QuartzHealthIndicator` now takes only the `Scheduler` (the `JobStore` argument
  is gone) and judges the instance with `getSchedulerInstances()`. It also reports `instances` and
  `aliveInstances`, and is `DOWN` when this instance has no check-in record (its check-ins fail,
  or another instance has already recovered it).

### Time

- Every scheduling "now" comes from the scheduler's `Clock`: the fired-trigger record times (see
  Recovery), the start of manual-fire triggers, the scheduler's `runningSince()`. Cluster check-ins,
  liveness (`getSchedulerInstances()`) and lock leases deliberately stay on the system clock:
  instances compare these times with each other, and under a fixed or offset `Clock` check-ins would
  stop advancing. `Instants.now()` (the default "now" of triggers built or computed outside a
  scheduler, e.g. `TriggerBuilder.startNow()`) is the system clock too.
- Instant arithmetic no longer goes through epoch milliseconds (`plusSeconds`/`minusSeconds`
  instead of `Instant.ofEpochMilli(x.toEpochMilli() ± 1000)`), in the cron, calendar-interval and
  daily-time-interval triggers, `TriggerUtils` and `DailyTimeIntervalScheduleBuilder`.
- **Breaking:** `JobExecutionContext.getJobRunTime()` is `@Nullable`: `null` until the job has
  finished, instead of the old `-1 ms` sentinel. Replace `getJobRunTime().isNegative()` checks with
  null checks. `QuartzMetrics` skips the timer when it is `null`.
- The run time is measured with `System.nanoTime()` (nanosecond `Duration`), so a wall-clock step
  during a run can no longer make it wrong or negative.
- Scheduling times have millisecond precision, the stores' precision: trigger start, end, next and
  previous fire times were already truncated by the trigger setters (`Instants.toMillis`); a
  `SimpleTrigger`'s repeat interval now is too. A trigger reports the same times before and after
  a store round-trip.

### MongoDB storage format

- **Breaking (storage):** the MongoDB store keeps instants as BSON `date`s instead of int64 epoch
  milliseconds: `qrtz_triggers` `nextFireTime`, `previousFireTime`, `startTime` and `endTime`;
  `qrtz_fired_triggers` `firedTime` and `scheduledTime`; `qrtz_schedulers` `lastCheckin`. Mongo
  date operators (`$dateToString`, `$dateDiff`, TTL indexes) now work on them, and Compass shows
  dates. Durations (`checkinInterval`, `repeatInterval`) stay int64 milliseconds. Every value is
  encoded explicitly, so the format no longer depends on the client's codec registry; this also
  fixes the lock lease's `expiresAt`, which was written through the registry's `Instant` codec.
- There is no migration: a MongoDB store written by 1.0.0-rc1 cannot be read. Drop its
  collections and let the jobs be re-created. Code that reads these collections directly must
  switch to `getSchedulerInstances()` / `getClusterExecutingJobs()`.
- New `JobExecutionContext.getRecoveryInfo()`: `Optional<RecoveryInfo>` with the original
  trigger, fire instance id, scheduler instance id, fire time and scheduled fire time of the
  execution a recovery run replaces (empty unless recovering); every component is non-null.
  `RecoveryInfo.of(JobDataMap)` reads the same from job data (empty unless all entries are
  there). `getRecoveringTriggerKey()` reads the same entries (through `getRecoveryInfo()`). The `FAILED_JOB_ORIGINAL_*` job data entries stay strings.

### Trigger and job queries

- New `Scheduler.getTriggerKeys(GroupMatcher<TriggerKey>, Set<TriggerState>)`: the keys of the
  triggers in the given states (empty set = all), ordered by key, filtered by the store, as an
  unmodifiable list.
- New `Scheduler.getTriggers(TriggerQuery)`: one page of triggers with their states
  (`TriggerPage` of `TriggerWithState`, plus the total), filtered by groups and states, sorted by
  `TriggerSort` `KEY`, `NEXT_FIRE_TIME`, `PREVIOUS_FIRE_TIME` or `PRIORITY` (ties by key; missing
  times first), optionally descending. States map exactly as `getTriggerState` reports them. The
  MongoDB store answers with one `find` (sort, skip, limit) and one `countDocuments`. The Redis
  store reads each matching trigger's state once, then loads only the page's triggers when
  ordering by key (every matching trigger otherwise, to sort them), with the same results.
- New `Scheduler.getJobDetails(GroupMatcher<JobKey>, int offset, int limit)`: one page of jobs
  ordered by key (`JobDetailPage`, plus the total).
- New MongoDB indexes on `qrtz_triggers`: `(schedName, state, group, name)` and
  `(schedName, nextFireTime, group, name)`, created at startup like the others.
- SPI: `JobStore.getTriggerKeys(GroupMatcher, Set)`, `getTriggers(TriggerQuery)`,
  `getJobDetails(GroupMatcher, int, int)`; `StoreDelegate.selectTriggerKeys(GroupMatcher,
  Collection)`, `selectTriggers(...)` and `selectJobDetails(...)` with generic defaults.
