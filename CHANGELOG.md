# Changelog

Changes to the public API, behaviour and storage format, by release. Breaking changes are marked
**Breaking**.

## 1.0.0-rc2 (unreleased)

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
  interrupts it at its next cluster check-in (within `cluster-checkin-interval`).
- New `JobDetail.isInterruptible()`: whether the job class implements `InterruptableJob`.
- SPI: `JobStore.getExecutingJobs()`, `JobStore.requestInterrupt(String)`,
  `SchedulerSignaler.interruptRequested(String)` (default: does nothing),
  `StoreDelegate.selectFiredTriggerRecords()` and
  `StoreDelegate.updateFiredTriggerInterruptRequested(String)`; `FiredTriggerRecord` has a new
  `interruptRequested` component. Stores record the flag as `interruptRequested` on the
  fired-trigger document (MongoDB) or hash (Redis).

### Cluster instances

- New `Scheduler.getSchedulerInstances()`: the scheduler instances known to the job store, as
  `SchedulerInstance` (instance id, last check-in, check-in interval, `alive`, `current`).
  `alive` uses the rule the store applies before recovering an instance (last check-in +
  interval + `cluster-checkin-misfire-threshold`), and `current` marks the calling instance. A
  store that is not clustered lists only the current instance, without check-in times. Use this
  rather than reading `qrtz_schedulers` yourself: it ships in the same release as the switch of
  stored instants to BSON dates (below), which breaks readers of the raw documents.
- SPI: `JobStore.getSchedulerInstances()`.
- **Breaking:** `QuartzHealthIndicator` now takes only the `Scheduler` (the `JobStore` argument
  is gone) and judges the instance with `getSchedulerInstances()`. It also reports `instances` and
  `aliveInstances`, and is `DOWN` when another instance has already recovered this one.

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
