# spring-boot-quartz-scheduler

A Spring Boot 4 (Java 25) library derived from the core of the [Quartz Scheduler](https://github.com/quartz-scheduler/quartz).
It keeps the familiar `org.quartz` API (`JobBuilder`, `TriggerBuilder`, `Scheduler`, `Job`, …) with `java.time`
types, is configured entirely through `application.yml`, and persists jobs in MongoDB or Redis.
The history of the refactor is in [`REFACTOR_PLAN.md`](REFACTOR_PLAN.md) and [`DECISIONS.md`](DECISIONS.md).
How the library is built inside, for contributors: [`DEVELOPER_GUIDE.md`](DEVELOPER_GUIDE.md).

## Maven coordinates

```xml
<dependency>
    <groupId>com.neolama</groupId>
    <artifactId>spring-boot-quartz-scheduler</artifactId>
    <version>1.0.0-rc1</version>
</dependency>
<!-- plus the store you use: -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId> <!-- or spring-boot-starter-data-redis -->
</dependency>
```

Requires Java 25 and Spring Boot 4.x (built against 4.1.1). Do not add Spring Boot's own
`spring-boot-starter-quartz` next to it: this library replaces it (its Boot code lives under
`org.quartz.spring.boot`, so the two do not clash on packages, but both would configure a scheduler).

## Quick start

```java
@SpringBootApplication
public class Application {

    @Bean
    JobDetail reportJob() {
        return JobBuilder.newJob(ReportJob.class).withIdentity("report").storeDurably().build();
    }

    @Bean
    Trigger reportTrigger(JobDetail reportJob) {
        return TriggerBuilder.newTrigger().forJob(reportJob).withIdentity("report")
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(6, 30).inTimeZone(ZoneId.of("Europe/Berlin")))
            .build();
    }
}

public class ReportJob implements Job {

    @Autowired
    private ReportService reportService; // jobs are created by SpringBeanJobFactory and can be autowired

    @Override
    public void execute(JobExecutionContext context) {
        reportService.run(context.getScheduledFireTime()); // an Instant
    }
}
```

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/app?replicaSet=rs0
  quartz:
    job-store:
      type: mongodb            # or redis; may be omitted when only one of them is configured
      clustered: true
    scheduler:
      name: app
      instance-id: AUTO
```

`JobDetail`, `Trigger`, `Calendar` (by bean name), `JobListener`, `TriggerListener`, `SchedulerListener` and
`SchedulerPlugin` beans are registered with the scheduler; inject `Scheduler` to schedule jobs at run time.
`SchedulerFactoryBeanCustomizer` beans can adjust the `SchedulerFactoryBean` before the scheduler is created.

### Plugins

Plugins are beans; the bean name is the plugin name. The built-in ones are off until you define them:

```java
@Bean // logs every job execution (MessageFormat templates; unset ones keep their defaults)
LoggingJobHistoryPlugin jobHistory() {
    LoggingJobHistoryPlugin plugin = new LoggingJobHistoryPlugin();
    plugin.setJobFailedMessage("Job {1}.{0} failed at {2, date, HH:mm:ss}: {8}");
    return plugin;
}

@Bean // logs every trigger firing, misfire and completion
LoggingTriggerHistoryPlugin triggerHistory() {
    return new LoggingTriggerHistoryPlugin();
}

@Bean // interrupts jobs that run longer than allowed ("MaxRunTime" job data entry, in ms, overrides it per job)
JobInterruptMonitorPlugin jobInterruptMonitor() {
    JobInterruptMonitorPlugin plugin = new JobInterruptMonitorPlugin();
    plugin.setDefaultMaxRunTime(Duration.ofMinutes(10)); // default 5 minutes
    return plugin;
}
```

Your own `SchedulerPlugin` implementations are registered the same way; use `@Profile` or
`@ConditionalOnProperty` on the bean method to switch one on per environment.

Jobs run on a pool of `thread-count` platform threads (`SimpleThreadPool`) unless virtual threads are switched on
for the scheduler:

```yaml
spring.quartz.thread-pool:
  virtual-threads: true
  thread-count: 50        # the maximum number of jobs running at once
  thread-name-prefix: jobs
```

Each job then runs on a new virtual thread (`VirtualThreadPool`), and the scheduler acquires no more triggers than
`thread-count` allows. Size it to what the systems your jobs call accept (database connection pool, mail server,
API rate limits) rather than to CPU cores. Thread priority, daemon status and thread groups do not apply to virtual
threads. The switch is independent of Spring Boot's `spring.threads.virtual.enabled`: that one does not change the
job threads.

To run jobs on an executor of your own, set it with a customizer:

```java
@Bean
SchedulerFactoryBeanCustomizer quartzExecutor(ThreadPoolTaskExecutor quartzJobsExecutor) {
    return (schedulerFactoryBean) -> schedulerFactoryBean.setTaskExecutor(quartzJobsExecutor);
}
```

The scheduler only takes triggers when the executor has room to run them, so they never wait in its queue (where
no other cluster node could run them) or get rejected. Its capacity is the core pool size of a
`ThreadPoolTaskExecutor` / `ThreadPoolExecutor` (the maximum pool size if it has no queue) or the concurrency limit
of a `SimpleAsyncTaskExecutor`. An executor that sets no limit (an unlimited `SimpleAsyncTaskExecutor`,
`Executors.newCachedThreadPool()`, a virtual-thread-per-task executor, any other `Executor`) runs at most
`thread-count` jobs at a time, as the startup log says; when the limit of an executor cannot be read at all (a
JDK `Executors.newSingleThreadExecutor()`, a decorator), a warning says so. An explicit capacity, set with
`setThreadPool(new LocalTaskExecutorThreadPool(executor, capacity))`, is used as given.
`wait-for-jobs-to-complete-on-shutdown` waits for the jobs handed to the executor; the executor itself is not shut
down.

## Job stores

Jobs are always persisted: there is no in-memory store. Set `spring.quartz.job-store.type` to `mongodb` or
`redis`, or leave it unset when the application has only one of a `MongoClient` / `RedisConnectionFactory` bean
and that store is used. Startup fails with an explanation (Spring Boot's failure analysis) when there is
neither, when there are both and no type is set, or when the configured type's connection bean is missing.
Both stores support clustering (`spring.quartz.job-store.clustered: true`, unique instance ids, e.g.
`spring.quartz.scheduler.instance-id: AUTO`). Outside the auto-configuration, `StdSchedulerFactory` and
`SchedulerFactoryBean` need a `JobStore` instance (`MongoJobStore` / `RedisJobStore`).

- **MongoDB** (`spring-boot-starter-data-mongodb`) uses the application's `MongoClient`. Collections are
  `{collection-prefix}jobs`, `triggers`, `calendars`, `paused_groups`, `fired_triggers`, `schedulers`, `locks`
  (default prefix `qrtz_`) in the application's MongoDB database: the one of the `MongoDatabaseFactory` (Spring
  Data MongoDB's, or your own), otherwise `spring.mongodb.database`, otherwise the database of `spring.mongodb.uri`;
  indexes are created on startup. An application with its own `MongoClient` and without Spring Boot's MongoDB
  module defines a `MongoDatabaseFactory` bean to name the database. On a replica set every store operation also runs in a transaction.
- **Redis** (`spring-boot-starter-data-redis`) uses the application's `RedisConnectionFactory`. All keys of a
  scheduler are `{key-prefix}{schedulerName}:...` (default prefix `quartz:`), one hash slot, so Redis Cluster
  works; multi-key changes are Lua scripts.

  **Durability:** the store is only as durable as Redis. Run Redis with AOF persistence
  (`appendonly yes`, `appendfsync everysec`): with RDB snapshots only, every schedule change made since the
  last snapshot (new jobs, fired one-shot triggers, paused groups) is lost when Redis restarts, and with
  `appendfsync everysec` up to one second of changes can be lost on a crash.

## Store requirements

- **MongoDB:** tested with 8.0. Use a **replica set** (a single-node replica set is enough) or a sharded cluster.
  Every store primitive is a single atomic operation, so the store also works on a standalone server, but only
  with transactions (detected on startup and logged) is each store operation all-or-nothing when a node crashes
  halfway. Jobs annotated with `@ExecuteInTransaction` that write to MongoDB need transactions as well. If you build
  your own `MongoClient`, keep the driver's default codec registry (it encodes `java.time.Instant`, used for lock
  expiry).
- **Redis:** tested with 7.4. All keys of a scheduler share one hash slot, so Redis Cluster works. Enable **AOF
  persistence** (`appendonly yes`), see *Durability* above.
- **Clustering:** every node uses the same `spring.quartz.scheduler.name` and store, a unique instance id
  (`instance-id: AUTO`) and `job-store.clustered: true`. Keep clocks in sync. A node that stops checking in for
  `cluster-checkin-interval` + `cluster-checkin-misfire-threshold` is considered failed and its in-progress
  jobs that request recovery are re-run elsewhere; a lock held by a crashed node is freed after
  `job-store.{mongodb,redis}.lock-timeout`.

## Configuration reference (`application.yml`)

All properties, generated from `META-INF/spring-configuration-metadata.json` (the configuration processor's
output for this library, merged with `META-INF/additional-spring-configuration-metadata.json`; your IDE completes
them from the same file and flags the removed ones). Thread priorities, daemon flags, class-loader inheritance and
store-internal locking options are not properties: they keep defaults chosen for a Spring Boot application.

Properties removed in this version (`spring.quartz.plugins.*`, `spring.quartz.job-store.mongodb.database`, the
thread and locking options above) fail startup with a list of what to use instead, rather than being ignored:
ignoring `mongodb.database`, for instance, would silently point the scheduler at a different database. Durations accept `500ms`, `30s`, `10m` or
ISO-8601 (`PT30S`).

| Property | Type | Default | Description |
|---|---|---|---|
| `management.endpoint.quartz.access` | Access | `unrestricted` | Permitted level of access for the quartz endpoint. |
| `management.endpoint.quartz.cache.time-to-live` | Duration | `0ms` | Maximum time that a response can be cached. |
| `management.endpoint.quartz.roles` | Set |  | Roles used to determine whether a user is authorized to be shown unsanitized job or trigger values. When empty, all authenticated users are authorized. |
| `management.endpoint.quartz.show-values` | Show | `never` | When to show unsanitized job or trigger values. |
| `spring.quartz.auto-startup` | Boolean | `true` | Whether to automatically start the scheduler after initialization. |
| `spring.quartz.job-store.cluster-checkin-interval` | Duration | `7500ms` | How often a clustered instance checks in with the others. |
| `spring.quartz.job-store.cluster-checkin-misfire-threshold` | Duration | `7500ms` | Grace period after a missed check-in before an instance is considered failed and its work is recovered. |
| `spring.quartz.job-store.clustered` | Boolean | `false` | Whether several scheduler instances share the store. |
| `spring.quartz.job-store.max-misfires-to-handle-at-a-time` | Integer | `20` | Maximum number of misfired triggers handled per pass. |
| `spring.quartz.job-store.misfire-threshold` | Duration | `60s` | How late a trigger may fire before it counts as misfired. |
| `spring.quartz.job-store.mongodb.collection-prefix` | String | `qrtz_` | Prefix of the collections the store uses. |
| `spring.quartz.job-store.mongodb.lock-timeout` | Duration | `10m` | How long a lock is held before it is considered abandoned. |
| `spring.quartz.job-store.redis.key-prefix` | String | `quartz:` | Prefix of the keys the store uses. |
| `spring.quartz.job-store.redis.lock-timeout` | Duration | `30s` | How long a lock is held before it is considered abandoned. |
| `spring.quartz.job-store.retry-interval` | Duration | `15s` | Delay before retrying after the store could not be reached. |
| `spring.quartz.job-store.type` | JobStoreType |  | Job store type; when unset, the store whose connection bean is present. |
| `spring.quartz.overwrite-existing-jobs` | Boolean | `false` | Whether configured jobs should overwrite existing job definitions. |
| `spring.quartz.scheduler.batch-trigger-acquisition-fire-ahead-time-window` | Duration | `0s` | How far ahead of their fire time triggers may be acquired. |
| `spring.quartz.scheduler.batch-trigger-acquisition-max-count` | Integer | `1` | Maximum number of triggers acquired at once. |
| `spring.quartz.scheduler.context` | Map |  | Initial entries of the scheduler context. |
| `spring.quartz.scheduler.idle-wait-time` | Duration | `30s` | How long the scheduler thread waits for new triggers when idle; at least 1s. |
| `spring.quartz.scheduler.instance-id` | String | `NON_CLUSTERED` | Instance id of this node; "AUTO" generates one and "SYS_PROP" reads the "org.quartz.scheduler.instanceId" system property (both only when the job store is clustered). |
| `spring.quartz.scheduler.interrupt-jobs-on-shutdown` | Boolean | `false` | Whether to interrupt running interruptable jobs on shutdown without waiting. |
| `spring.quartz.scheduler.interrupt-jobs-on-shutdown-with-wait` | Boolean | `false` | Whether to interrupt running interruptable jobs on shutdown with waiting. |
| `spring.quartz.scheduler.name` | String | `quartzScheduler` | Name of the scheduler, shared by all nodes of a cluster. |
| `spring.quartz.scheduler.thread-name` | String |  | Name of the scheduler thread; defaults to the scheduler name followed by "_QuartzSchedulerThread". |
| `spring.quartz.startup-delay` | Duration | `0s` | Delay after which the scheduler is started once initialization completes. Setting this property makes sense if no jobs should be run before the entire application has started up. |
| `spring.quartz.thread-pool.thread-count` | Integer | `10` | Number of worker threads, or, with virtual threads, the maximum number of concurrently running jobs. |
| `spring.quartz.thread-pool.thread-name-prefix` | String |  | Prefix of the worker thread names; defaults to the scheduler name followed by "_Worker". |
| `spring.quartz.thread-pool.virtual-threads` | Boolean | `false` | Whether to run each job on a new virtual thread, at most thread-count at a time, instead of a pool of platform threads; independent of "spring.threads.virtual.enabled". |
| `spring.quartz.transaction.default-propagation` | Propagation | `REQUIRED` | Propagation of the transactions of jobs that are not annotated, when enabled. |
| `spring.quartz.transaction.enabled` | Boolean | `false` | Whether every job runs in a transaction of the unique or primary transaction manager, not only annotated jobs. |
| `spring.quartz.wait-for-jobs-to-complete-on-shutdown` | Boolean | `false` | Whether to wait for running jobs to complete on shutdown. |

Connection settings are Spring Boot's own: `spring.mongodb.*` (MongoDB) and `spring.data.redis.*` (Redis).

## Actuator

With `spring-boot-starter-actuator` on the classpath:

- **`/actuator/quartz`** (expose it with `management.endpoints.web.exposure.include=quartz`): job and trigger groups,
  jobs/triggers by group, job and trigger details, and `POST /actuator/quartz/jobs/{group}/{name}` with
  `{"state":"running"}` to run a job now. Same JSON as Spring Boot's endpoint: intervals in milliseconds, times
  ISO-8601. Job data values are sanitized (`management.endpoint.quartz.show-values`, `roles`).
- **Health `quartz`**: `DOWN` when the scheduler is shut down, its store cannot be read, or (clustered) its own
  check-in is older than `cluster-checkin-interval` + `cluster-checkin-misfire-threshold`; `UP` otherwise, standby
  included. `management.health.quartz.enabled=false` turns it off.
- **Metrics** (when a `MeterRegistry` bean exists): `quartz.jobs.executed`, `quartz.jobs.failed`,
  `quartz.jobs.duration`, `quartz.triggers.misfired`, tagged with `scheduler` and the job/trigger group.
  `management.metrics.quartz.enabled=false` turns them off.

## Logging

While a job runs, the SLF4J MDC holds `quartz.jobKey` and `quartz.triggerKey` (as `group.name`) and
`quartz.fireInstanceId` (unique to that firing). Everything logged during the execution, the job's own code and the
job / trigger listeners included, can therefore be traced back to it. Add them to the log pattern, for example:

```yaml
logging.pattern.level: "%5p [%X{quartz.jobKey:-}] [%X{quartz.fireInstanceId:-}]"
```

JSON encoders that write the whole MDC pick them up without configuration. The keys are removed when the execution
ends. Threads a job starts itself do not inherit them (MDC is per thread); pass them on if those threads log.

## Transactions

A job class annotated with `@org.quartz.ExecuteInTransaction` runs its `execute` method in a Spring-managed
transaction (the replacement for Quartz's `@ExecuteInJTATransaction`):

```java
@ExecuteInTransaction(transactionManager = "ordersTransactionManager", timeout = 30)
public class SettleOrdersJob implements Job { ... }
```

Attributes: `transactionManager` (bean name; empty = the unique or `@Primary` `PlatformTransactionManager`),
`propagation`, `isolation`, `timeout` (seconds), `readOnly`, `rollbackFor`, as on `@Transactional`.
`JobExecutionException`, runtime exceptions and errors roll back. Vetoed executions open no transaction,
and the transaction is completed before the job store is told that the job finished. To run every job in a
transaction:

```yaml
spring:
  quartz:
    transaction:
      enabled: true              # default false: only annotated jobs
      default-propagation: REQUIRED
```

The business transaction and the job store are independent. One exception: when the store is MongoDB and
the job's transaction manager is a `MongoTransactionManager` on the same `MongoClient`, the job data of a
`@PersistJobDataAfterExecution` job is written in the job's transaction (it is discarded if the job rolls back).
MongoDB transactions need a replica set or sharded cluster. The annotation applies when the scheduler is
created by `SchedulerFactoryBean` in an application context (as the auto-configuration does).

## API changes compared with Quartz 2.x

Package names and the usage model are unchanged (`JobBuilder`, `TriggerBuilder`, the schedule builders, `Scheduler`,
`Job`, `JobDetail`, `Trigger`, `JobExecutionContext`, listeners, `Calendar` implementations, `JobKey`/`TriggerKey`).
Only the types changed:

| Quartz | This library | Examples |
|---|---|---|
| `java.util.Date` | `java.time.Instant` | `TriggerBuilder.startAt(Instant)`, `Trigger.getNextFireTime()`, `JobExecutionContext.getFireTime()`, `CronExpression.getNextValidTimeAfter(Instant)`, every `DateBuilder` factory |
| `long` milliseconds (intervals, thresholds) | `java.time.Duration` | `SimpleScheduleBuilder.withInterval(Duration)` (the `withIntervalIn…` helpers remain), `SimpleTrigger.getRepeatInterval()`, `JobExecutionContext.getJobRunTime()` |
| `java.util.TimeZone` | `java.time.ZoneId` | `CronScheduleBuilder.inTimeZone(ZoneId)`, `CalendarIntervalScheduleBuilder.inTimeZone(ZoneId)`, `BaseCalendar(ZoneId)` |
| `java.util.Calendar` | `java.time.ZonedDateTime` | `AnnualCalendar.setDayExcluded(ZonedDateTime, boolean)`, `DailyCalendar(ZonedDateTime, ZonedDateTime)` |
| `TimeBroker` SPI | `java.time.Clock` (a `Clock` bean) | |
| `@ExecuteInJTATransaction` | `@ExecuteInTransaction` (Spring transactions) | see *Transactions* |

Details: `org.quartz.Calendar#getNextIncludedTime` returns `Instant.EPOCH` where Quartz returned `0`;
`DailyCalendar#getTimeRangeStartingTimeInMillis(long)` became `getTimeRangeStartingTime(Instant)` (same for the
ending time); week days and calendar-interval units stay `int` + `IntervalUnit`. Times are kept at millisecond
precision as before. Scheduling behaviour (cron, DST handling, calendars) is unchanged and pinned by tests recorded
against Quartz, apart from two cron bug fixes listed in `DECISIONS.md` (D-010, D-011).

## Migrating from Quartz / `spring-boot-starter-quartz`

- **No `quartz.properties`.** Every setting is a `spring.quartz.*` property (table above), durations as `Duration`:
  e.g. `org.quartz.scheduler.instanceName` → `spring.quartz.scheduler.name`,
  `org.quartz.threadPool.threadCount` → `spring.quartz.thread-pool.thread-count`,
  `org.quartz.jobStore.misfireThreshold=60000` → `spring.quartz.job-store.misfire-threshold: 60s`,
  `org.quartz.jobStore.isClustered` → `spring.quartz.job-store.clustered`. Spring Boot's flat
  `spring.quartz.scheduler-name`, `job-store-type` and free-form `spring.quartz.properties.*` are replaced by the
  `scheduler`, `job-store`, `thread-pool` and `transaction` groups. Plugins (`org.quartz.plugin.*`) are beans.
- **Stores:** only MongoDB and Redis. There is no JDBC store (`spring.quartz.jdbc.*`, table scripts, `DataSource`)
  and no in-memory `RAMJobStore`; jobs are always persisted, so existing data has to be re-scheduled in the new
  store (re-creating the `JobDetail` / `Trigger` beans does that on startup).
- **Removed:** JTA/`UserTransaction`, RMI, JMX MBeans (use the actuator endpoint, health and metrics instead), JNDI,
  servlet listeners/plug-ins, the XML scheduling-data plug-in, `quartz.properties`-driven reflection and
  `DirectSchedulerFactory`, the standalone shutdown-hook bootstrap.
- **Outside Spring Boot** (or for a second scheduler): `StdSchedulerFactory.builder()` with typed settings and a
  store instance, e.g. `.jobStore(new MongoJobStore(...))`, or `SchedulerFactoryBean#setJobStore`.
- **Java code:** replace `Date`, millisecond `long`s, `TimeZone` and `java.util.Calendar` as listed in
  *API changes*; replace `@ExecuteInJTATransaction` with `@ExecuteInTransaction`.
- **Instance ids:** `AUTO` and `SYS_PROP` keep their meaning (`spring.quartz.scheduler.instance-id`).

## Building and testing

Docker must be running: the integration tests start real MongoDB (`mongo:8.0`, single-node replica set) and Redis
(`redis:7.4`) containers with Testcontainers, shared per JVM.

```bash
./mvnw verify
```

`verify` runs the unit tests (Surefire, `*Test`) and the container tests (Failsafe, `*IT`), including
`SampleApplicationIT`, which runs a sample application on {MongoDB, Redis} × {single node, two-node cluster} ×
{jobs in transactions, without}. Use JDK 25. `./mvnw javadoc:javadoc` builds the API docs.

The sources and javadoc jars Maven Central needs are only built by the `publish-artefacts` profile, which also
signs the artefacts and uploads them. Before a release, check them without signing or uploading (the javadoc step
fails on broken Javadoc):

```bash
./mvnw -Ppublish-artefacts -Dgpg.skip -DskipTests package
```

## License

Apache License 2.0, see [`LICENSE.txt`](LICENSE.txt) and [`NOTICE`](NOTICE). Based on Quartz Scheduler and on Spring
Framework / Spring Boot's Quartz support (all Apache 2.0).
