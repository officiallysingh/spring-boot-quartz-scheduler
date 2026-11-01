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
