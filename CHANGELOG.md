# Changelog

Changes to the public API, behaviour and storage format, by release. Breaking changes are marked
**Breaking**.

## 1.0.0-rc5 (2026-11-14)

The library is cleaned up as a product of its own: one set of configuration properties, and no
API or code kept only because Quartz or Spring had it. Every removal below is **Breaking** for
code that used the removed member; the replacement is named where there is one.

### Configuration

- **Breaking**: `spring.quartz.scheduler.instance-id: SYS_PROP` is removed, with
  `SystemPropertyInstanceIdGenerator`, `QuartzProperties.SYSTEM_PROPERTY_INSTANCE_ID` and
  `QuartzProperties.Scheduler.isSystemPropertyInstanceId()`. Set the id itself, for example
  `instance-id: ${HOSTNAME}` or the `SPRING_QUARTZ_SCHEDULER_INSTANCEID` environment variable, or
  use `AUTO` with an `InstanceIdGenerator` bean. `SYS_PROP` is now taken as a literal instance id.
- **Breaking**: the 1.0.0-rc1 properties that later versions rejected (`spring.quartz.plugins.*`,
  `spring.quartz.job-store.mongodb.database`, thread priority and daemon flags, store locking
  options) no longer stop the application: `RemovedPropertiesException`, its failure analyzer and
  the error-level metadata entries are removed. Such a key is ignored like any unknown key.
  `QuartzProperties` is the whole configuration.
- Configuration metadata for `management.health.quartz.enabled` and
  `management.metrics.quartz.enabled` (both switches existed).
- **Breaking** (settings records): options without a property are fixed in code at their former
  defaults and are no longer settings. `ThreadPoolSettings` is `(threadCount, threadNamePrefix,
  virtualThreads)`; `SchedulerSettings.makeSchedulerThreadDaemon` is removed; `JobStoreSettings`
  loses `acquireTriggersWithinLock`, `useProperties`, `doubleCheckLockMisfireHandler` and
  `makeThreadsDaemons`, and `acquireTriggersWithinLockRetry` is renamed `lockRetryInterval`.
- Removed the unused `HostnameInstanceIdGenerator`.

### Spring integration

- **Breaking**: a `SchedulerFactoryBean` without `setJobFactory` uses `SpringBeanJobFactory`, so
  jobs of an application-defined factory bean get dependency injection and job data applied to
  their setters (it used `AdaptableJobFactory`, which did neither). `AdaptableJobFactory` is merged
  into `SpringBeanJobFactory`, whose `createJobInstance` returns `Job`. The auto-configured
  scheduler already used `SpringBeanJobFactory`.
- Removed `DelegatingJob` and `Runnable` job classes: a job class always had to implement `Job`.
- `SchedulerAccessor` is merged into `SchedulerFactoryBean`, its only subclass; the setters are
  unchanged. Its protected `logger`, `registerJobsAndTriggers()` and `registerListeners()` are
  private now.
- Removed `SchedulerAccessor.setTransactionManager` (the stores run their own transactions),
  `SchedulerFactoryBean.setSchedulerContextAsMap` and `setApplicationContextSchedulerContextKey`
  (inject beans into jobs; `spring.quartz.scheduler.context` and `Scheduler.getContext()` remain),
  and `SpringBeanJobFactory.setIgnoredUnknownProperties`.

### Scheduler behaviour

- `SchedulerListener.jobDeleted` is reported for every job that is deleted, exactly once, by the
  job store: also for a job that is not durable and goes with its last trigger (a trigger that
  completes, `unscheduleJob`, `unscheduleJobs`), which was never reported before. `deleteJobs` no
  longer reports keys that were not stored. The listeners are told after the store operation has
  committed and released its lock, so a listener may call the scheduler; `unscheduleJob` and
  `unscheduleJobs` report `jobUnscheduled` first, then `jobDeleted`.
- **Breaking**: a scheduler on a clustered job store does not start with an instance id every
  instance would share: `NON_CLUSTERED` (the default, when only `clustered: true` is set) or
  `SYS_PROP`. It fails with the new `ClusteredInstanceIdException`, explained by a failure
  analyzer. Instances with the same id recover each other's running jobs and run them twice.
- `LocalTaskExecutorThreadPool` treats an unlimited `SimpleAsyncTaskExecutor`, a
  `VirtualThreadTaskExecutor` and the JDK's thread-per-task executors as executors without a
  limit (an INFO line) instead of warning that their limit cannot be read; `describe()` tells the
  two cases apart.
- `Scheduler.startDelayed(null)` throws at once; the scheduler silently never started.
- `@DisallowConcurrentExecution` and `@PersistJobDataAfterExecution` are also found as
  meta-annotations of an application's own annotation.
- `VirtualThreadPool` logs a runnable that throws, as `SimpleThreadPool` does; the exception went
  to the thread's uncaught exception handler.
- The scheduler thread and the worker threads are no longer in thread groups of their own
  (`QuartzScheduler:<name>`); thread names are unchanged.
- `JobInterruptMonitorPlugin` no longer puts itself into the `SchedulerContext`, so two instances
  of the plugin do not replace each other.
- `SchedulerMetaData.getSummary()` no longer says "running locally" and "supports persistence".

### Scheduler API

- **Breaking**: `Scheduler.startDelayed(Duration)` replaces `startDelayed(int seconds)`.
- **Breaking**: `CronExpression.getTimeAfter(Instant)` is removed; it was a second name of
  `getNextValidTimeAfter(Instant)`.
- **Breaking** (trigger implementations): an `AbstractTrigger` holds a `TriggerKey` and a `JobKey`.
  `setName`, `setGroup`, `setJobName`, `setJobGroup` and their getters are removed (`setKey`,
  `setJobKey`, `getKey`, `getJobKey`), as are the constructors of `CalendarIntervalTriggerImpl`
  and `DailyTimeIntervalTriggerImpl` that take arguments: build triggers with `TriggerBuilder`.
  A blank key name or group is rejected by `setKey` / `setJobKey`.
- **Breaking**: removed the string-mode methods of `JobDataMap` (`putAsString`, `get*FromString`,
  `getIntValue`, `getLongValue`, `getFloatValue`, `getDoubleValue`, `getBooleanValue`). `put` and
  the typed getters (`getInt`, `getLong`, `getBoolean`, …, which accept strings) remain.
- Removed, with their replacement:
  - `JobExecutionContext.getRecoveringTriggerKey()`: `getRecoveryInfo()` (`originalTrigger()`).
  - `SimpleScheduleBuilder.withIntervalInMilliseconds(long)`: `withInterval(Duration)`.
  - `CronScheduleBuilder.cronScheduleNonvalidatedExpression(String)`:
    `cronSchedule(new CronExpression(String))`.
  - `JobBuilder.setJobData(JobDataMap)`: `usingJobData(JobDataMap)`.
  - `TimeOfDay.hourAndMinuteFromDate` and `hourAndMinuteAndSecondFromDate`: the constructors, from
    `instant.atZone(zone)`.
  - `CronExpression.validateExpression(String)`: `new CronExpression(String)` or
    `isValidExpression(String)`.
  - `SchedulerException.getUnderlyingException()`: `getCause()`.
  - `Broadcast{Job,Trigger,Scheduler}Listener`: register each listener with the `ListenerManager`.
  - `Calendar.clone()` (`Calendar` is no longer `Cloneable`): the scheduler hands out its own copy
    of a stored calendar.
- Removed without replacement: `DateBuilder.translateTime` and the `DateBuilder` millisecond
  constants, `CronExpression.getFinalFireTime()` (it always returned `null`),
  `CronTriggerImpl.willFireOn`, `TriggerUtils`, `Scheduler.DEFAULT_FAIL_OVER_GROUP`,
  `Calendar.MONTH`, `org.quartz.utils.ClassUtils`, `SchedulerMetaData.isSchedulerRemote()` and
  `isJobStoreSupportsPersistence()` (constants) with its 15-argument constructor,
  `AbstractTrigger.getFullName()` / `getFullJobName()`, the `CoreTrigger` interface,
  `StringKeyDirtyFlagMap.getKeys()` and the unused constructors of the typed maps, unused
  `LenientDateTime` constants, and the `serialVersionUID` constants declared on `Trigger`,
  `SimpleTrigger` and `CronTrigger`. `Matcher` and `SchedulerMetaData` are no longer
  `Serializable`.

### SPI and internals

- `ThreadPool.setInstanceId` and `setInstanceName` are removed: a pool is given its thread-name
  prefix when it is created. `SimpleThreadPool(int threadCount, String threadNamePrefix)` and
  `VirtualThreadPool(int maxConcurrency, String threadNamePrefix)` are the only constructors, and
  `setThreadNamePrefix` is removed. `SchedulerPlugin.start()` and `shutdown()` are default methods
  that do nothing.
- `OperableTrigger.updateAfterMisfire(Calendar, Instant)` and `updateWithNewCalendar(Calendar,
  Duration, Instant)` are the only misfire updates; the overloads without the scheduler's time
  are removed.
- `JobStore.supportsPersistence()`, `getEstimatedTimeToReleaseAndAcquireTrigger()`,
  `setThreadPoolSize(int)`, `getNumberOfJobs()`, `getNumberOfTriggers()`, `getNumberOfCalendars()`
  and the three `getTriggersBy…Group` lookups are removed, with the `StoreDelegate` counts behind
  them. `StoreDelegate.selectInterruptRequestedFireInstanceIds` and
  `selectFiredTriggerRecordsInState` are abstract, and `selectFiredTriggerRecords()` is removed.
  `PersistentJobStore.getSchedulerStates()` is removed (`getSchedulerInstances()`).
- The priority, daemon and thread-group accessors of `SimpleThreadPool` are removed, as is
  `VirtualThreadPool.getMaxConcurrency()`.
- `JobRunShell` no longer has `begin()`, `complete(boolean)` and `requestShutdown()`, and is not a
  `SchedulerListener`; `executeJob` remains the hook for subclasses.
- Removed `StdSchedulerFactory.getSettings()`, `getLog()` and the `instantiate(resources,
  scheduler)` hook; `QuartzScheduler.getSchedulerThreadGroup()`, `supportsPersistence()` and
  `removeInternalSchedulerListener`; the daemon flag of `QuartzSchedulerResources`;
  `PersistentJobStore.getLastCheckin()`; `LeaseStoreLock.getOwner()` / `getLeaseTime()`;
  `JobDataCodec.checkUseProperties`; `JobDetailCodec.PERSIST_JOB_DATA`.

### Storage format

- Job records in MongoDB and Redis no longer carry `persistJobData` and `nonConcurrent`: both were
  written and never read, the job class's annotations decide.
- The Java-serialized form of a custom trigger class changes: `AbstractTrigger` serializes its
  `TriggerKey` and `JobKey` instead of four name fields. This needs no conversion: the store reads
  a trigger's keys from the record's own key fields, which are unchanged, also for a trigger
  serialized by 1.0.0-rc4.

## 1.0.0-rc4 (2026-11-12)

### Time zone and clock

- New `spring.quartz.time-zone` (`ZoneId`, default `UTC`; `SchedulerSettings.timeZone()`,
  `Scheduler.getTimeZone()`): the zone of a cron, calendar-interval or daily-time-interval trigger
  scheduled without `inTimeZone(...)`. The scheduler applies it when the trigger is scheduled and
  the store keeps it with the trigger, so changing the property later does not move existing
  triggers. Until it is scheduled such a trigger resolves in UTC (`CronExpression.getTimeZone()`
  too; new `CronExpression.hasTimeZone()`). **Breaking**: these triggers followed
  `ZoneId.systemDefault()`; nothing in scheduling reads the JVM default zone now.
- Calendars follow the same rule: a `BaseCalendar` added without a zone takes the scheduler's
  default zone in `Scheduler.addCalendar()`, base calendars included, and is stored with it (new
  `BaseCalendar.bind(ZoneId)`; the store applies the default to a stored calendar without one). Until
  it is added a calendar resolves in UTC. **Breaking**: `BaseCalendar` fell back to
  `ZoneId.systemDefault()`, so a `DailyCalendar` or `CronCalendar` built without a zone excluded
  different hours on each machine; `CronCalendar.getTimeZone()` is `null` while no zone is set.
- `DailyTimeIntervalScheduleBuilder.inTimeZone(ZoneId)` and `DailyTimeIntervalTrigger.getTimeZone()`;
  the zone is stored (`timeZone` field of `DAILY_I` trigger records in MongoDB and Redis). A record
  without one, written by 1.0.0-rc3, takes the scheduler's default zone when loaded.
- Every "now" is the scheduler's `Clock` (new `Scheduler.getClock()`). A trigger built without
  `startAt()`, or with `startNow()`, has no start time until it is scheduled, when it becomes now by
  that clock (**Breaking**: `Trigger.getStartTime()` is `@Nullable` until then; computing fire times
  of an unscheduled trigger without a start time throws `IllegalStateException`;
  `TriggerBuilder.startAt(null)` means the same as `startNow()`). `getFireTimeAfter(null)`, the
  no-argument `updateAfterMisfire` and the two-argument `updateWithNewCalendar` use the clock of
  the scheduler the trigger was scheduled with or loaded from; before that, the system clock. The
  history plugins stamp their messages with the scheduler's clock and render them in its zone; the
  actuator's "job triggered" time is the scheduler's clock.
- The actuator `quartz` endpoint reports the scheduler's `timeZone`, and daily-time-interval
  trigger summaries and details carry their `timeZone`.
- `DateBuilder` **Breaking**: the helpers take the `Clock` and the `ZoneId` explicitly:
  `futureDate(interval, unit, clock, zone)`, `tomorrowAt`/`todayAt`/`dateOf(h, m, s, clock, zone)`,
  `dateOf(h, m, s, d, M, clock, zone)`, `dateOf(h, m, s, d, M, y, zone)`,
  `evenHourDateAfterNow(clock, zone)`, `evenHourDate`/`evenHourDateBefore(instant, zone)`,
  `nextGivenMinuteDate(instant, base, zone)`; minute and second rounding needs no zone:
  `evenMinuteDateAfterNow(clock)`, `evenMinuteDate(instant)` and so on, with a non-null instant.
  `newDate(clock, zone)` replaces `newDate()`, `newDateInTimezone()`, `newDateInLocale()` and
  `newDateInTimeZoneAndLocale()` (the locale had no effect). `build()` fills only the fields that
  were not set (it reset the time of day whenever a date field was missing). A local time in a
  daylight-saving gap or overlap resolves as fire times do (`LenientDateTime`: forward by the gap,
  the later occurrence).
- `TimeOfDay` **Breaking**: `getTimeOfDayForDate(Instant)`, `hourAndMinuteAndSecondFromDate(Instant)`
  and `hourAndMinuteFromDate(Instant)` are gone; the overloads with a zone need a non-null one.
- `CronScheduleBuilder.build()` gives each trigger its own copy of the expression, so a builder can
  be reused after one of its triggers was scheduled.
  `DailyTimeIntervalScheduleBuilder.endingDailyAfterCount()` is time-of-day arithmetic (it used the
  system clock and zone).
- Cluster check-ins, liveness and lock leases stay on the system clock, because instances compare
  them with each other; said on `Scheduler.getClock()`.
- SPI: `OperableTrigger.bind(Clock, ZoneId)`; `SchedulerSignaler.getClock()` and `getTimeZone()`
  (with defaults for third-party signalers); `TriggerCodec.decode(fields, data, classLoader, clock,
  zone)`; `InstantMessageFormat.format(zone, pattern, args...)`; `Instants.now(InstantSource)`
  replaces `Instants.now()`; `PersistentJobStore.getClock()` and `getTimeZone()`;
  `QuartzSchedulerResources.getTimeZone()`; `QuartzProperties.schedulerSettings()`.

## 1.0.0-rc3 (2026-11-11)

### Manual fires

- New `Scheduler.triggerNow(TriggerKey, JobDataMap)`: like `triggerNow(TriggerKey)`, with the
  given entries added to the one-off trigger's job data over the source trigger's.
  `MANUAL_FIRE_OF_TRIGGER` always holds the source trigger's key, even if the given data has that
  key. The source trigger's job data is not changed. `triggerNow(TriggerKey)` is
  `triggerNow(triggerKey, null)`.
- Documented on `Scheduler.MANUAL_TRIGGER_GROUP`: manual fires follow the pause state of
  `MANUAL_TRIGGER` (and of the job's group), not of `DEFAULT`; pause `MANUAL_TRIGGER` to hold them.
- Removed `QuartzScheduler.triggerJob(OperableTrigger)` (internal class, unused; it still moved
  colliding one-off triggers into `DEFAULT`).

### Interrupting jobs

- The instance running an execution flagged by `interruptAnywhere(...)` now calls `interrupt()` on
  a virtual thread of its own after its check-in. In 1.0.0-rc2 it ran on the check-in thread, so a
  slow `interrupt()` delayed check-ins (other instances could recover jobs still running there) and
  an `Error` stopped check-ins for good.
- `interruptAnywhere(...)` looks up only that execution's fired-trigger record (1.0.0-rc2 read
  every record of the cluster), and records the request even when this instance cannot load the
  job class to check it is interruptible: the instance running the job decides.
- `getExecutingJobs()` filters `EXECUTING` records in the store, and a check-in finds its interrupt
  requests with one store query. Redis keeps an `interrupts_by_instance:<id>` set for this, filled
  when a request is made: requests made by a 1.0.0-rc2 instance are not in it.
- SPI: `JobStore.getExecutingJob(String)`, `StoreDelegate.selectFiredTriggerRecord(String)`,
  `StoreDelegate.selectFiredTriggerRecordsInState(String)` and
  `StoreDelegate.selectInterruptRequestedFireInstanceIds(String)`.

### Cluster instances

- `getSchedulerInstances()`, `getClusterExecutingJobs()` and `interruptAnywhere()` throw "The
  Scheduler has been shutdown." after shutdown, like the other `Scheduler` methods. In 1.0.0-rc2
  they used the store, which may be closed by then.
- `getSchedulerInstances()` judges every instance, the calling one included, by one rule: last
  check-in + interval + `cluster-checkin-misfire-threshold`. In 1.0.0-rc2 it stretched the other
  instances' window to the calling instance's own time since its last check-in, so a node that was
  not checking in yet (`spring.quartz.auto-startup: false`, a start-up delay) saw every peer as
  alive, however long dead. Cluster recovery keeps the stretch: a stalled node must not declare the
  others dead.
- `QuartzHealthIndicator`: the `reason` for a missing check-in record says "its check-ins fail, or
  another instance recovered it", not only "recovered by another instance". It is `DOWN` in the
  same cases as before.

### Recovery

- `RecoveryInfo`: `originalFireInstanceId()` and `originalSchedulerInstanceId()` are non-null (they
  were `@Nullable` for recovery triggers created by 1.0.0-rc1). `RecoveryInfo.of(JobDataMap)` is
  empty unless all recovery entries are there.
- `JobExecutionContext.getRecoveringTriggerKey()` reads the original trigger through
  `getRecoveryInfo()`, so the two agree on partial data: with entries missing it throws an
  `IllegalStateException` (1.0.0-rc2: a key from the trigger entries alone, or a
  `NullPointerException` without the name).

### MongoDB storage format

- **Breaking:** removed the startup conversion of 1.0.0-rc1 documents (epoch-millisecond instants)
  to BSON dates; the store only reads and writes dates. A store written by 1.0.0-rc1 and not
  converted by a 1.0.0-rc2 instance cannot be read: drop its collections and let the jobs be
  re-created. Stores written by 1.0.0-rc2 are fine.

### Trigger and job queries

- `Scheduler.getTriggerKeys(GroupMatcher<TriggerKey>, Set<TriggerState>)` returns an unmodifiable
  list from every store (in 1.0.0-rc2 the MongoDB store's list was mutable, the Redis store's not).
- The Redis store's `getTriggers(TriggerQuery)` reads each matching trigger's state once, and when
  ordering by key loads only the page's triggers. Same results.

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
- Existing stores keep working: the store still reads epoch-millisecond values, and converts this
  scheduler's documents to dates at startup. Stop every instance on an older version before
  starting this one; older versions cannot read the dates. Code that reads these collections
  directly must switch to `getSchedulerInstances()` / `getClusterExecutingJobs()`.
- New `JobExecutionContext.getRecoveryInfo()`: `Optional<RecoveryInfo>` with the original
  trigger, fire instance id, scheduler instance id, fire time and scheduled fire time of the
  execution a recovery run replaces (empty unless recovering). `RecoveryInfo.of(JobDataMap)`
  reads the same from job data. The `FAILED_JOB_ORIGINAL_*` job data entries stay strings.

### Trigger and job queries

- New `Scheduler.getTriggerKeys(GroupMatcher<TriggerKey>, Set<TriggerState>)`: the keys of the
  triggers in the given states (empty set = all), ordered by key, filtered by the store.
- New `Scheduler.getTriggers(TriggerQuery)`: one page of triggers with their states
  (`TriggerPage` of `TriggerWithState`, plus the total), filtered by groups and states, sorted by
  `TriggerSort` `KEY`, `NEXT_FIRE_TIME`, `PREVIOUS_FIRE_TIME` or `PRIORITY` (ties by key; missing
  times first), optionally descending. States map exactly as `getTriggerState` reports them. The
  MongoDB store answers with one `find` (sort, skip, limit) and one `countDocuments`; the Redis
  store reads the matching triggers and pages in memory, with the same results.
- New `Scheduler.getJobDetails(GroupMatcher<JobKey>, int offset, int limit)`: one page of jobs
  ordered by key (`JobDetailPage`, plus the total).
- New MongoDB indexes on `qrtz_triggers`: `(schedName, state, group, name)` and
  `(schedName, nextFireTime, group, name)`, created at startup like the others.
- SPI: `JobStore.getTriggerKeys(GroupMatcher, Set)`, `getTriggers(TriggerQuery)`,
  `getJobDetails(GroupMatcher, int, int)`; `StoreDelegate.selectTriggerKeys(GroupMatcher,
  Collection)`, `selectTriggers(...)` and `selectJobDetails(...)` with generic defaults.
