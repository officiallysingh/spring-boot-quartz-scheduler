# DECISIONS

Ambiguous choices made during the refactor, with the conservative option taken. Newest last.

## D-001 — Keep `JobInterruptMonitorPlugin` (Phase 0)

**Context:** `REFACTOR_PLAN.md` lists `org.quartz.plugins.interrupt.JobInterruptMonitorPlugin` as "keep (decide in DECISIONS.md)".

**Findings:** it depends only on `java.util.concurrent`, `org.quartz` API types, `TriggerListenerSupport`, `SchedulerPlugin` and slf4j — no JDBC, JTA, JMX, RMI or XML. It has a store-agnostic test (`JobInterruptMonitorPluginTest`, Mockito). Upstream recently fixed a memory leak in it (PR 1470, current HEAD), so it is maintained. Its single setting `defaultMaxRunTime` is a `long` in milliseconds (default 300 000 ms).

**Decision:** keep it. In Phase 4/5 change `defaultMaxRunTime` to `Duration` (default 5 min) and the per-job `JobDataMap` key `MaxRunTime` keeps its millisecond `long` value for compatibility. In Phase 6 expose it as an opt-in plugin (`spring.quartz.plugins.job-interrupt-monitor.enabled`, default `false`, `default-max-run-time`).

## D-002 — Delete JMX (`QuartzSchedulerMBean*`, `org.quartz.core.jmx`, sampled statistics) (Phase 0)

**Context:** the plan says "evaluate: JMX — mark delete unless something core depends on it".

**Findings:** in core, `QuartzScheduler` only uses JMX in `registerJMX()` / `unregisterJMX()` (gated by `QuartzSchedulerResources#getJMXExport`) and the `jmxBean` field for sampled statistics. `org.quartz.utils.counter.*` and `CircularLossyQueue` exist only to feed those statistics. No scheduling, store, listener, or plugin logic depends on it.

**Decision:** delete in Phase 3 together with `RemoteMBeanScheduler`, `mbeans-descriptors.xml`, `org.quartz.management`, `utils.counter.*`, `CircularLossyQueue` (+ test). Actuator endpoint and metrics (Phase 10) replace it.

## D-003 — Spring Boot version 4.1.1 (Phase 0)

**Context:** you asked for Spring Boot 4.4.4. That version is not published on Maven Central (`spring-boot-dependencies/4.4.4` returns 404). On 2026-10-20 the latest GA is 4.1.1 and the latest pre-release is 4.2.0-M2.

**Decision (confirmed by you):** import `org.springframework.boot:spring-boot-dependencies:4.1.1` as the BOM in Phase 2, through a single `spring-boot.version` property so a later upgrade is a one-line change. Testcontainers, JUnit, Mockito, AssertJ, Awaitility and slf4j versions all come from this BOM.

## D-004 — Delete the whole `.github/` folder (Phase 1)

**Context:** the plan says to delete "`.github/` workflows that build with Gradle". There are no workflows; the folder holds `dependabot.yml` (Gradle ecosystem), `pull_request_template.md` and `stale.yml`. INVENTORY §2 suggested keeping the last two, but the Phase 1 exit criterion allows only `src/`, license/readme/git files, `CLAUDE.md` and plan files.

**Decision:** delete all three. They are upstream-project configuration (Terracotta PR template, stale-bot rules) with no use for this library. A Maven CI workflow / Dependabot config can be added later if wanted.

## D-005 — Project name `spring-boot-quartz-scheduler` (Phase 1)

**Context:** the plan says to rename the root project from `quartz` to `spring-boot-quartz-scheduler`. The only place the root project name was declared was `settings.gradle` (`rootProject.name = 'quartz-scheduler'`), which Phase 1 deletes.

**Decision:** the name now lives in `README.md` and becomes the Maven `artifactId` in Phase 2. The local checkout folder (`…/spring-boot-quartz-refactor/quartz`) is outside the repository and is not renamed by this refactor; rename it yourself if wanted.

## D-006 — Phase 2 temporary compile excludes and the `StdSchedulerFactory` JTA/c3p0 edit (Phase 2)

**Context:** the Maven build drops c3p0, HikariCP, JBoss, commonj, `quartz-stubs`, `jakarta.transaction`, `jakarta.servlet`, `jakarta.xml.bind` and Hamcrest. Phase 2 may exclude only what cannot compile without them.

**Excluded from main compilation and javadoc** (`maven-compiler-plugin` `<excludes>`, `maven-javadoc-plugin` `<sourceFileExcludes>`), all deleted in Phase 3:
- `org/quartz/ExecuteInJTATransaction.java`, `org/quartz/ee/jta/**` (jakarta.transaction)
- `org/quartz/ee/servlet/**` (jakarta.servlet)
- `org/quartz/ee/jmx/jboss/**` (JBoss)
- `org/quartz/commonj/**` (commonj / Geronimo)
- `org/quartz/xml/**`, `org/quartz/plugins/xml/**`, `org/quartz/plugins/SchedulerPluginWithUserTransactionSupport.java` (jakarta.xml.bind, jakarta.transaction)
- `org/quartz/impl/jdbcjobstore/{CUBRIDDelegate,JTANonClusteredSemaphore,WebLogicDelegate}.java`, `org/quartz/impl/jdbcjobstore/oracle/**`, `org/quartz/utils/weblogic/**` (c3p0, jakarta.transaction, quartz-stubs)
- `org/quartz/utils/{C3p0,HikariCp}PoolingConnectionProvider.java` (c3p0, HikariCP)

JDK-only code (the rest of JDBC, RMI, JMX) still compiles and is removed in Phase 3.

**Excluded from test compilation** (`<testExcludes>`): `org/quartz/xml/**` (needs the excluded XML classes) and `org/quartz/impl/RemoteMBeanSchedulerTest.java` (46 Hamcrest assertions on the JMX/RMI proxy, which Phase 3 deletes; converting it would be thrown away).

**`StdSchedulerFactory` edit:** it is core and cannot be excluded, but it imported `ee.jta.*` and `C3p0PoolingConnectionProvider`. Changes:
- The default run-shell factory was `JTAAnnotationAwareJobRunShellFactory`, which returns a plain `JobRunShell` unless the job has `@ExecuteInJTATransaction`. The annotation is excluded, so it is replaced by the existing `org.quartz.impl.StdJobRunShellFactory` (same `JobRunShell`); behaviour is unchanged.
- `org.quartz.scheduler.wrapJobExecutionInUserTransaction=true` or a `userTransactionURL` now fails fast with `SchedulerConfigException` ("JTA is not supported") instead of wiring JTA.
- The c3p0-specific property removal before `setBeanProps` is dropped (the c3p0 provider no longer exists).
- `Job` javadoc `@see ExecuteInJTATransaction` removed.

**Build details:** `quartz-build.properties` now uses Maven filtering (`${project.version}` etc.; only that file is filtered). Surefire runs one JVM per test class (`reuseForks=false`), matching Gradle's `forkEvery 1`: tests share static scheduler state (`SchedulerRepository`).

## D-007 — Tests and test resources deleted in Phase 2

**Context:** REFACTOR_PLAN Phase 2 says to delete tests that need an embedded RDBMS (they go with JDBC). The Maven build has no Derby, JDBC drivers, RDBMS Testcontainers modules, c3p0, HikariCP, or log4j binding.

**Deleted** (INVENTORY §5a):
- `org.quartz`: `JdbcSchedulerTest`, `FlakyJdbcSchedulerTest` (Derby)
- `org.quartz.core`: `QTZ385Test`, `RecoverJobsTest` (Derby; recovery behaviour becomes a contract-kit case in Phase 7)
- `org.quartz.impl`: `MockConnectionProvider`, `StdSchedulerFactoryCustomConnectionProviderTest` (JDBC connection-provider wiring)
- `org.quartz.impl.jdbcjobstore` (all 11): Derby, MSSQL/MariaDB/Postgres Testcontainers, Liquibase, Mockito-based JDBC delegate tests
- `org.quartz.integrations.tests`: `JdbcQuartzDerbyUtilities`, `JobClassNotFoundExceptionErrorsTriggersTest`, `JobDataMapStorageTest`, `QTZ179_TriggerLostAfterDbRestart_Test`, `QTZ283_IgnoreMisfirePolicyJdbcStore_Test`, `QuartzDatabasePauseAndResumeTest`, `QuartzDatabaseSimplePropertiesTest`, `QuartzDerbyCronTriggerTest`, `QuartzDerbyTestSupport`, `QuartzMSSQLDatabaseCronTriggerTest`, `QuartzMSSQLTestSupport`, `StdRowLockSemaphoreTest` (Derby/MSSQL). Their in-memory twins (`QuartzMemory*`) are kept.
- `org.quartz.simpl.SystemPropertyInstanceIdGeneratorTest`: uses Derby only to build a scheduler. The generator logic must be re-tested store-agnostically when instance-id config moves to `@ConfigurationProperties` (Phase 5).
- `org.quartz.utils`: `C3p0PoolingConnectionProviderTest`, `HikariCpPoolingConnectionProviderTest`
- Resources: `tables_derby_drop.sql`, `container-license-acceptance.txt` (MSSQL image licence), `org/quartz/properties/quartzCustomConnectionProvider.properties`, `log4j.properties` (the slf4j-log4j12 binding is dropped; tests run without an slf4j binding for now).

## D-008 — Phase 2 test-support choices

- **Hamcrest → AssertJ** in the 7 kept tests that used it (`CalendarIntervalTriggerTest`, `CronTriggerTest`, `DefaultSchedulerTest`, `DisallowConcurrentExecutionJobTest`, `impl.JobDetailImplTest`, `QuartzMemoryCronTriggerTest`, `QuartzMemoryPauseAndResumeTest`). Same assertions, AssertJ syntax; Hamcrest is not added.
- **ASM:** `SchedulerDetailsSetterTest` generated a class with `asm:asm:3.3.1`. It now imports `org.springframework.asm` (the copy repackaged in `spring-core`, already on the test classpath through `spring-boot-test`), so no ASM dependency is added and the test still runs.
- **`ContainersSmokeIT` pings without client libraries:** no MongoDB driver or Redis client is in the Phase 2 dependency list. MongoDB is pinged with `mongosh` inside the container (`ping` and `rs.status()` both `ok: 1`) plus a host TCP connect to the mapped port. Redis is pinged from the host over a raw socket (`PING` → `+PONG`). The client libraries come with the stores in Phase 7.
- **Redis container:** `GenericContainer("redis:7.4")` on port 6379. `com.redis:testcontainers-redis` is not in the dependency list.
- **MongoDB replica set:** Testcontainers 2's `org.testcontainers.mongodb.MongoDBContainer` no longer starts a replica set by default (the smoke IT caught `not running with --replSet`), so `SharedContainers` calls `.withReplicaSet()` explicitly.
- **Shared containers:** `org.quartz.support.SharedContainers` starts each container once per JVM (lazy holder). `AbstractMongoIT` / `AbstractRedisIT` will be built on it when the first store ITs arrive.

## D-009 — Phase 3 removals and the choices made while removing them

**Deleted (main):** `org.quartz.ee.*` (JBoss, JTA, servlet, incl. `ee/jboss/doc-files`), `org.quartz.commonj`, `ExecuteInJTATransaction`, `plugins.SchedulerPluginWithUserTransactionSupport`, `core.RemotableQuartzScheduler`, `impl.RemoteScheduler`, `impl.QuartzServer`, `core.jmx.*`, `core.QuartzSchedulerMBeanImpl`, `core.{,Null}SampledStatistics{,Impl}`, `core/mbeans-descriptors.xml`, `impl.RemoteMBeanScheduler`, `org.quartz.management`, `utils.counter.*`, `utils.CircularLossyQueue`, `plugins.management.ShutdownHookPlugin`, `helpers.VersionPrinter`, `impl.jdbcjobstore.*` (incl. `oracle`, `oracle.weblogic`), `utils.{ConnectionProvider,DBConnectionManager,PoolingConnectionProvider,JNDIConnectionProvider,C3p0PoolingConnectionProvider,HikariCpPoolingConnectionProvider}`, `utils.weblogic`, `org.quartz.xml`, `plugins.xml.*`; resources `impl/jdbcjobstore/*` (SQL + Liquibase) and `xml/*.xsd`. The Phase 2 compiler/javadoc excludes (D-006) are gone.

**Deleted tests** (their production code no longer exists): `impl.RemoteMBeanSchedulerTest` (JMX/RMI proxy), `utils.CircularLossyQueueTest`, `xml.XMLSchedulingDataProcessorTest`, `xml.XMLSchedulingDataProcessorNeverDeleteTest`, `xml.XMLSchedulingDataProcessorPluginTest`, and the 12 fixtures under `test/resources/org/quartz/xml/`. In `AbstractJobStoreTest` the `JobStoreSupport` guards (enhanced statements reset, QTZ-208 skip) were removed, so `testPauseJobGroupPausesNewJob` now always runs; in `AbstractSchedulerTest` the Derby-thread filter was removed.

**Choices:**
- `DirectSchedulerFactory`: the `createRemoteScheduler(..)` overloads are deleted, and the `rmiRegistryHost/rmiRegistryPort` and `jmxExport/jmxObjectName` parameters are dropped from the `createScheduler` overloads (each overload stays, minus those parameters; no signatures collide). This factory is not part of the protected public API and is reworked in Phase 5.
- `SchedulerMetaData` keeps its constructor and `isSchedulerRemote()` for API compatibility; `QuartzScheduler` always passes `false`.
- JTA: `StdSchedulerFactory` still fails fast with `SchedulerConfigException` when `wrapJobExecutionInUserTransaction=true` or `userTransactionURL` is set, rather than silently running jobs outside a transaction. Phase 8 replaces it with Spring transactions.
- The Terracotta branch in `StdSchedulerFactory` (instance id / JMX name for `org.terracotta.quartz` stores) is removed: no such store exists here and it only fed JMX.
- `dbFailureRetryInterval` (property, `DirectSchedulerFactory` parameter, deprecated `QuartzScheduler` constructor argument) is now dead: its only consumer was `JobStoreSupport`. It is kept to avoid widening this phase's signature changes; Phase 5 drops it with the factory rework (store retry delay comes from `JobStore#getAcquireRetryDelay`).
- The `org.quartz.scheduler.rmi.*` lines are removed from the default `quartz.properties` (the file itself goes in Phase 5).
- Javadoc prose in kept API types that still mentions RMI/JDBC (e.g. `Scheduler`, `SchedulerContext`, `JobExecutionContext`) is left for Phase 11.

## D-010 — Phase 4 java.time migration: semantics and choices

**Behaviour is pinned by golden fixtures.** Before any change, `src/test/resources/org/quartz/golden/*.tsv.gz` were recorded from the original `Date`/`GregorianCalendar` code: about 37 000 cases of cron, calendar-interval, daily-time-interval and simple trigger fire times plus the six calendars, in UTC, America/New_York, Europe/London, Australia/Lord_Howe (30-minute DST) and Asia/Kolkata, around the 2024 transitions. `GoldenBehaviourTest` replays them against the java.time API (with `user.timezone=UTC` and `en_US` pinned, as when recording). The only deviations allowed are the two bug fixes below.

**`LenientDateTime` instead of rewriting algorithms.** The scheduling algorithms (cron, calendar-interval, daily-time-interval triggers, calendars) rely on lenient `GregorianCalendar` behaviour: fields that overflow and normalize on read, the standard offset for wall times inside a DST gap or overlap, and `add(DAY)` keeping the wall-clock time. `org.quartz.utils.LenientDateTime` reproduces exactly that on top of `java.time`, so the ports are line-for-line. `LenientDateTimeTest` checks it differentially against `GregorianCalendar`: 30 000 random set/add/get sequences in 7 zones, including Lord Howe, São Paulo (midnight DST) and Samoa (skipped day). Consequences:
- An ambiguous fall-back wall time resolves to the **later** instant, as `GregorianCalendar` did (plain `ZonedDateTime.of` would pick the earlier one).
- A wall time in a gap moves forward by the gap length.
- Field numbering in `LenientDateTime` mirrors the legacy calendar (0-based `MONTH`, `DAY_OF_WEEK` 1 = Sunday). The class is internal.

**Bug fixes (the only intended behaviour changes):**
1. `CronExpression.getTimeAfter` looped forever when the requested minute fell in a DST gap that does not start on the hour, e.g. `0 0 2 * * ?` or `0 0 * * * ?` in Australia/Lord_Howe on 2024-10-06. It now advances the hour when setting the minute makes no progress. The 3 golden rows that hung are recorded as `HANG`; `CronExpressionTest#testGetTimeAfterTerminatesInThirtyMinuteDstGap` pins the new result (the missing 02:00 is skipped).
2. The `W`/`LW` handling compared against an uncleared `Calendar.getInstance()`, so the wall-clock milliseconds (and the default locale) at call time leaked into the result. `getTimeBefore` could come out one second early depending on when it ran. The comparison is now deterministic and at second precision. The golden replay accepts recorded or recorded + 1 s for that column only; `testNearestWeekdayGetTimeBeforeIsExact` pins the exact value.

**Known limitation kept:** in a zone whose DST gap is not a whole hour (Australia/Lord_Howe, 30 minutes), a cron schedule that fires on the half hour skips the first full hour after the gap (`0 */30 * * * ?` fires 01:30, 02:30, 03:30 on 2024-10-06, with no 03:00). The original algorithm does exactly the same (golden fixtures). Fixing it changes fire times, so it is left for a deliberate follow-up. *Fixed in D-011.*

**API mapping** (CLAUDE.md rule applied literally): `Date` → `Instant`, `TimeZone` → `ZoneId`, `java.util.Calendar` parameters/returns → `ZonedDateTime`. Examples: `AnnualCalendar#setDayExcluded(ZonedDateTime, boolean)` and `getDaysExcluded()` → `ArrayList<ZonedDateTime>`, `DailyCalendar(ZonedDateTime, ZonedDateTime)`, `CronTriggerImpl#willFireOn(ZonedDateTime)`, `HolidayCalendar` excluded dates → `SortedSet<Instant>` (start-of-day instants). `org.quartz.Calendar#isTimeIncluded/getNextIncludedTime` take and return `Instant`; "no next time" is `Instant.EPOCH` where it was `0`. Exceptions:
- `DailyCalendar#getTimeRangeStartingTimeInMillis(long)` / `getTimeRangeEndingTimeInMillis(long)` became `getTimeRangeStartingTime(Instant)` / `getTimeRangeEndingTime(Instant)`, because a method named `…InMillis` returning an `Instant` would mislead. The `(long, long)` epoch-millis constructors and `setTimeRange(long, long)` take `Instant`s.
- `DailyCalendar#setTimeRange(int…)` validates start < end on `LocalTime`, no longer on today's date. This only differs on a DST day for ranges inside the transition, where the old check was date-dependent.
- Week-day numbers stay `int` (1 = Sunday … 7 = Saturday, `DateBuilder.SUNDAY…SATURDAY`), as do `CalendarIntervalTrigger`/`DailyTimeIntervalTrigger` intervals (`int` + `IntervalUnit`): they count calendar units, not durations.
- `TimeOfDay#getTimeOfDayForDate(Instant, ZoneId)` and `toLocalTime()`/`from(LocalTime)` were added; the one-argument form keeps using the system default zone.
- `DateBuilder` keeps its name and API shape; every factory returns `Instant`, and `TimeZone` parameters are `ZoneId`. `Locale` no longer influences the zone (it only ever selected the default zone).

**Millisecond precision.** `Date` had millisecond precision and `Instant.now()` has microseconds on JDK 21. Trigger setters (`setStartTime/EndTime/NextFireTime/PreviousFireTime`) truncate to milliseconds, and "now" in triggers, builders and the scheduler comes from `org.quartz.utils.Instants.now()`, so fire-time arithmetic and equality are unchanged. Cron still works at second precision.

**`Clock` replaces `TimeBroker`.** `spi.TimeBroker` and `simpl.SimpleTimeBroker` are deleted; `QuartzSchedulerResources` carries a `java.time.Clock` (default `Clock.systemUTC()`) that feeds the scheduler thread, `SchedulerSignaler#getCurrentTime()` (now `Instant`) and RAMJobStore misfire handling. `StdSchedulerFactory#setClock(Clock)` supplies it until Phase 5/6 expose it as a bean; `org.quartz.scheduler.timeBroker.class` now fails fast with `SchedulerConfigException`. A clock that throws is reported once to scheduler listeners and retried, as a failing `TimeBroker` was. The trigger-level "no now" overloads (`updateAfterMisfire(cal)`, `updateWithNewCalendar(cal, threshold)`) stay as conveniences using the system time; the scheduler and RAMJobStore always pass clock time.

**History plugins.** `LoggingJobHistoryPlugin`/`LoggingTriggerHistoryPlugin` message templates keep the `{n, date, pattern}` syntax: `org.quartz.utils.InstantMessageFormat` swaps each date/time element's `SimpleDateFormat` for a `DateTimeFormatter` with the same pattern in the system zone, so existing and custom templates keep working with `Instant` arguments. (This replaces the pre-formatting idea in the Phase 4 plan, which would have broken custom templates.)

**Serialization tests.** `SerializationTestSupport#testSerialization` had no `@Test` annotation after the JUnit 5 migration, so none of the `.ser` fixtures were ever checked. It is now a test: an in-memory round trip for every subclass, plus the fixtures that can still be read (`JobDataMap-1.4.5/1.5.1/2.1`). The 8 fixtures of `Date`-based classes (`SimpleTrigger(Impl)`, `CronTrigger(Impl)`, `CronExpression`, `CalendarIntervalTriggerImpl`, `AnnualCalendar`, `DailyCalendar`) cannot be deserialized after the type change and were deleted; their tests keep their assertions on the round trip. Java-serialization compatibility with upstream Quartz is not a goal (the stores in Phase 7 are MongoDB/Redis).

**Tests.** `FakeTimeBroker` → `support.MutableClock`; `QuartzSchedulerTimeBrokerTest` → `QuartzSchedulerClockTest` (same cases plus "timeBroker property is rejected", Awaitility instead of `Thread.sleep`); `TimeBrokerMisfireTest` → `ClockMisfireTest`. Tests may still build test data with `java.util.Calendar`/`TimeZone` where that is the clearest port; the exit grep covers `src/main` only. Short zone IDs that `ZoneId.of` rejects keep their old meaning (`ZoneId.of("EST", ZoneId.SHORT_IDS)` = −05:00; `GMT-2:00` → `GMT-02:00`).

**Durations (Phase 4 step 5).** Millisecond `long` durations in the public API and SPIs are `java.time.Duration`, used with millisecond precision:
- `SimpleTrigger#getRepeatInterval()`, `SimpleTriggerImpl` constructors and `setRepeatInterval(Duration)`. `SimpleScheduleBuilder#withInterval(Duration)` is new; `withIntervalInMilliseconds/Seconds/Minutes/Hours` and the `repeat…Forever/ForTotalCount` helpers delegate to it.
- `OperableTrigger#updateWithNewCalendar(Calendar, Duration misfireThreshold[, Instant now])`; `RAMJobStore#get/setMisfireThreshold(Duration)` (default 5 s).
- `JobStore#getEstimatedTimeToReleaseAndAcquireTrigger()` and `getAcquireRetryDelay(int)` return `Duration`. `acquireNextTriggers(Instant noLaterThan, int maxCount, Duration timeWindow)`.
- `JobExecutionContext#getJobRunTime()` returns `Duration`. It stays negative (−1 ms) until the job completes, keeping the old sentinel rather than switching to `null`, so listeners doing arithmetic do not start throwing.
- `QuartzSchedulerResources#batchTimeWindow`, the `QuartzScheduler` constructor (`idleWaitTime`, the dead `dbRetryInterval`) and the `DirectSchedulerFactory#createScheduler` overloads take `Duration`; `null` replaces the old `-1` "use default".
- `JobInterruptMonitorPlugin#defaultMaxRunTime` is a `Duration` (default 5 min), as D-001 planned; the per-job `MaxRunTime` job-data value stays a millisecond `long`.
- `SchedulerSignaler#signalSchedulingChange(Instant)`: `null` replaces the old `0` "no particular fire time". The scheduler thread keeps its internal millisecond bookkeeping.
- Until Phase 5 removes properties wiring, `StdSchedulerFactory#setBeanProps` accepts `Duration` setters: a plain number is read as milliseconds (existing `quartz.properties` values keep working) and an ISO-8601 value (`PT5M`) is also accepted.

## D-011 — Cron no longer skips the first full hour after a DST gap that is not a whole hour

**Change.** Fixes the known limitation listed in D-010. In `CronExpression.getTimeAfter`, when the minute wraps into the next hour, the minute is set first and the hour second. In a zone whose gap is not a whole hour (Australia/Lord_Howe, 2024-10-06 02:00 → 02:30), setting `MINUTE=0` at 02:3x lands in the gap and normalizes forward to 02:30; `setCalendarHour(cl, 3)` then kept minute 30, so `0 */30 * * * ?` fired 01:30, 02:30, 03:30 with no 03:00. The minute branch now re-applies the requested minute (and second 0) after setting the hour when gap normalization changed it. Schedules now fire 02:30, 03:00, 03:30. Whole-hour gaps never move the minute, so they are unaffected.

**Golden fixtures not regenerated.** They stay the record of the original behaviour. `GoldenBehaviourTest.CORRECTED_NEXT` replaces the recorded `next` column for exactly these rows of `cron.tsv.gz` with the exact corrected values (every other row, including all other Lord Howe rows, still has to match the recording):

| expression | zone | startMillis | change |
|---|---|---|---|
| `0 0/30 * * * ?` | Australia/Lord_Howe | 1728135000000 | gains 1728144000000 (03:00 +11:00) |
| `0 */15 1-3 * * ?` | Australia/Lord_Howe | 1728135000000 | gains 1728144000000, 1728144900000 (03:00, 03:15 +11:00) |

No calendar-interval, daily-time-interval, simple-trigger or calendar fixture changed. `CronDstTest#halfHourlyScheduleAcrossTheThirtyMinuteGapTerminatesAndSkipsTheMissingTimes` now expects 03:00.

## D-012 — Phase 5 typed configuration: factory shape, defaults and removals

**Factory.** `StdSchedulerFactory` keeps its name and becomes a programmatic builder (`StdSchedulerFactory.builder()…build()`, plus `new StdSchedulerFactory()` / `new StdSchedulerFactory(QuartzSettings)` and `getDefaultScheduler()`), so the familiar entry point survives. `DirectSchedulerFactory` is deleted: it was a second programmatic path over the same `QuartzSchedulerResources` wiring and is merged into the builder. `getScheduler()` keeps the `SchedulerRepository` semantics (return the bound scheduler unless it is shut down, otherwise create a new one).

**Settings vs collaborators.** `org.quartz.impl.config` holds immutable records — `SchedulerSettings`, `ThreadPoolSettings`, `JobStoreSettings` and the `QuartzSettings` aggregate — with validation, `defaults()` and builders; every duration is a `Duration`. Former class-name keys are collaborator instances on the builder: `ThreadPool`, `JobStore`, `JobFactory`, `InstanceIdGenerator`, `ClassLoadHelper`, `ThreadExecutor`, named `SchedulerPlugin`s (initialized in insertion order), `JobListener`s / `TriggerListener`s (registered for everything, as before) and the `Clock` (replacing `setClock`). `ThreadPoolSettings` / `JobStoreSettings` configure only the `SimpleThreadPool` / in-memory store the factory creates itself; a supplied instance is used as-is (the Phase 7 stores take `JobStoreSettings` in their constructors). A clustered `JobStoreSettings` with the in-memory store fails fast (`SchedulerConfigException`), as `isClustered` on `RAMJobStore` used to fail in `setBeanProps`. Plugin and listener options are set on the instances by the caller; their `application.yml` binding comes in Phase 6 (D-001).

**Defaults = what an unconfigured scheduler effectively had**, i.e. the code defaults overlaid with the shipped `quartz.properties` that `getDefaultScheduler()` loaded: name `DefaultQuartzScheduler`, instance id `NON_CLUSTERED`, 10 threads at priority 5 inheriting the context class loader, misfire threshold 60 s, idle wait 30 s, batch max 1 / window 0, cluster check-in 7.5 s, max misfires per pass 20, store retry 15 s. New store settings without a predecessor (`clusterCheckinMisfireThreshold` 7.5 s, `acquireTriggersWithinLockRetry` 1 s) are provisional; Phase 7 owns their semantics. `instanceId=AUTO` becomes `instanceIdAuto=true` (generator still used only when the store is clustered); `SYS_PROP` becomes `instanceIdAuto=true` plus a `SystemPropertyInstanceIdGenerator` instance.

**Removed with no replacement:** `PropertiesParser`, the `quartz.properties` resource, the `org.quartz.properties` file lookup, system-property overrides, `$@` property references, reflection `setBeanProps` for SPI classes, and the JTA / `timeBroker.class` fail-fast checks (those keys no longer exist anywhere). `idleWaitTime` < 1 s and negative values are now rejected by the record instead of by the parser.

**`dbFailureRetryInterval` dropped** (dead since Phase 3, D-009): the deprecated `QuartzScheduler` constructor argument is gone (`QuartzScheduler(resources, idleWaitTime)`). The store retry delay is `JobStoreSettings.retryInterval` (the generalised `jobStore.dbRetryInterval`).

**Not mirrored:** JDBC-only keys (`tablePrefix`, `dataSource`, delegates, lock handler, …), RMI/JMX/management REST (already removed), `skipUpdateCheck` (never read). `job-store.type` and Mongo/Redis-specific settings (lock timeouts, collection/key prefixes) come with Phase 6/7.

**Remaining `java.util.Properties` in `src/main`: none.** `QuartzScheduler` reads `version=` from the Maven-filtered `quartz-build.properties` with a line reader. `PropertySettingJobFactory#setBeanProps` stays: it copies `JobDataMap` values onto job instances (job data, not scheduler configuration).

**Tests.** Every `new StdSchedulerFactory(Properties)` use is converted with the *same effective values* the properties produced; for keys a test did not set, `support.TestSchedulers` reproduces the old code defaults (name `QuartzScheduler`, 5 s in-memory misfire threshold, workers not inheriting the context class loader). Deleted: `utils.PropertiesParserTest` (class removed), the old `impl.StdSchedulerFactoryTest` (only tested `overrideWithSysProps`, removed), `impl.DirectSchedulerFactoryTest` (class removed — its two cases had lost their `@Test` annotations in the JUnit 5 migration and never ran; both are ported, with `@Test`, to the new `impl.StdSchedulerFactoryTest`), `QuartzSchedulerClockTest#timeBrokerPropertyIsRejected` (the key no longer exists). `SchedulerDetailsSetterTest#testSetter` drops its `DirectSchedulerFactory` half. The new `StdSchedulerFactoryTest` also restores the instance-id coverage lost with `SystemPropertyInstanceIdGeneratorTest` in D-007 (AUTO on non-clustered and clustered stores, default generator, `SystemPropertyInstanceIdGenerator` with prepend/postpend and missing property). `impl.config.SettingsTest` covers defaults, validation and immutability.

## D-013 — Phase 6 Spring integration and auto-configuration

**Sources.** `org.quartz.spring` is ported from Spring Framework 7.0.9 `spring-context-support` (`org.springframework.scheduling.quartz`); `org.quartz.spring.boot.autoconfigure` from Spring Boot commit `57a1ca37c36f3cf355211aa8dea8c500b2dd3a09` (`module/spring-boot-quartz`), which differs from the 4.1.1 sources jar only in license headers. Ported files keep their Apache 2.0 headers and authors; `NOTICE` credits Quartz, Spring Framework and Spring Boot. Package split as in the plan: the plain Spring integration lives in `org.quartz.spring`, Boot code in `org.quartz.spring.boot.*` (CLAUDE.md), never `org.springframework.*`.

**Ported:** `SchedulerFactoryBean`, `SchedulerAccessor`, `SpringBeanJobFactory`, `AdaptableJobFactory`, `DelegatingJob` (needed by `AdaptableJobFactory`), `SchedulerContextAware`, `QuartzJobBean`, `JobDetailFactoryBean`, `CronTriggerFactoryBean`, `SimpleTriggerFactoryBean`, `LocalTaskExecutorThreadPool`, `SimpleThreadPoolTaskExecutor`, `ResourceLoaderClassLoadHelper` (used as the class-load helper inside an application context, as in Spring); `QuartzAutoConfiguration`, `QuartzProperties`, `SchedulerFactoryBeanCustomizer`, `JobStoreType`.
**Not ported:** `LocalDataSourceJobStore`, `QuartzDataSource`, `QuartzTransactionManager`, `QuartzJdbcProperties`, `QuartzDataSourceScriptDatabaseInitializer`, `SchedulerDependsOnDatabaseInitializationDetector` (+ its `spring.factories`) — JDBC; `MethodInvokingJobDetailFactoryBean`, `JobMethodInvocationFailedException`, `SchedulerAccessorBean`, `SchedulerFactoryBeanRuntimeHints` — not in the phase list; endpoint classes — Phase 10.

**Adaptations.**
- `SchedulerFactoryBean` builds the scheduler with `StdSchedulerFactory.builder()`: typed `setSettings(QuartzSettings)`, `setJobStore`, `setThreadPool`, `setPlugins`, `setInstanceIdGenerator`, `setClock`; `startupDelay` is a `Duration`. Removed: `configLocation`, `quartzProperties`, `dataSource`/`nonTransactionalDataSource`, `schedulerFactoryClass`, and the static config-time `ThreadLocal` holders (collaborators are passed as instances, so `LocalTaskExecutorThreadPool` and `ResourceLoaderClassLoadHelper` take the executor / resource loader in their constructors). Name precedence keeps Spring's order: explicit name, then the name in explicitly given settings (was `org.quartz.scheduler.instanceName`), then the bean name. `taskExecutor` and `threadPool` together fail fast.
- `SchedulerAccessor` loses the XML `jobSchedulingDataLocations` (the XML processor was removed in Phase 3); the optional `PlatformTransactionManager` around job registration stays.
- Trigger factory beans: `startTime` is an `Instant`, `startDelay` / `repeatInterval` are `Duration`s, `timeZone` is a `ZoneId`.
- jspecify `@Nullable` annotations were dropped here, then restored in the post-Phase 12 nullness pass (D-020).
- A `Runnable` job class cannot reach `AdaptableJobFactory` through a `JobDetail` (Quartz's `JobDetailImpl#setJobClass` accepts only `Job` classes, as upstream); the adapter is kept and unit-tested directly.

**`spring.quartz` properties.** `QuartzProperties` is an immutable record bound by constructor with `@DefaultValue`; nested `scheduler`, `thread-pool`, `job-store` (with `mongodb` / `redis` groups), `plugins`, each with `toSettings()` onto the Phase 5 records. `QuartzPropertiesTest` asserts that the bound defaults equal the Phase 5 defaults, except `scheduler.name`, which defaults to `quartzScheduler` (Spring Boot's default) instead of `DefaultQuartzScheduler`. Spring Boot's flat `scheduler-name` / `job-store-type` / free-form `properties` map are replaced by these groups. `wait-for-jobs-to-complete-on-shutdown` keeps Spring Boot's default `false` (the plan's YAML example shows `true` as a sample value). The `mongodb` / `redis` groups are bound now and consumed by the Phase 7 stores.

**Job store type has no default.** An unset `spring.quartz.job-store.type` fails startup with a message naming the property, instead of silently falling back to memory (the plan allows `memory` "for tests only"). `mongodb` and `redis` fail with "not available yet" until Phase 7 adds the stores. Consequence: until Phase 7, applications must set `memory`. Phase 9 replaces this with a `FailureAnalyzer`.

**`transaction.*` deferred to Phase 8.** Binding `transaction.enabled` / `default-propagation` now would accept settings that do nothing.

**Auto-registration.** Beyond Spring Boot's `JobDetail` / `Trigger` / `Calendar`, every `JobListener` and `TriggerListener` bean is registered globally, every `SchedulerListener` bean is added, and `SchedulerPlugin` beans are registered under their bean names. A unique `InstanceIdGenerator` or `Clock` bean is used. The auto-configuration backs off when a `Scheduler` **or** `SchedulerFactoryBean` bean exists (Spring Boot only checked `SchedulerFactoryBean`).

**Built-in plugins** are opt-in (`spring.quartz.plugins.{job-history,trigger-history,job-interrupt-monitor}.enabled`, default `false`) and registered as `jobHistory`, `triggerHistory`, `jobInterruptMonitor`; a `SchedulerPlugin` bean with one of those names while the built-in is enabled fails fast rather than silently replacing one of them. Unset history messages keep the plugins' defaults. `job-interrupt-monitor.default-max-run-time` defaults to 5 min (D-001).

**Instance id.** `AUTO` → `instanceIdAuto` (an `InstanceIdGenerator` bean if unique, else `SimpleInstanceIdGenerator`); `SYS_PROP` → `instanceIdAuto` with `SystemPropertyInstanceIdGenerator`; anything else is used literally. As in Quartz, generators run only when the store is clustered.

**Virtual threads.** `thread-pool.virtual-threads: true` runs jobs on a virtual-thread `SimpleAsyncTaskExecutor` behind `LocalTaskExecutorThreadPool`, limited to `thread-count` concurrent jobs. When the limit is reached, handing over the next job blocks the scheduler thread until one ends (the adapter reports one available thread, as Spring's does); `SchedulerMetaData#getThreadPoolSize()` reports `-1` in this mode.

**Dependencies.** Added `spring-context`, `spring-tx`, `spring-boot-autoconfigure`, `spring-boot-configuration-processor` (optional, also on `annotationProcessorPaths`), and optional `spring-boot-starter-data-mongodb` / `spring-boot-starter-data-redis` (needed already by the IT base classes' `@ServiceConnection`). `spring-boot-actuator-autoconfigure` is deferred to Phase 10, its first user, so no unused dependency is added now.

**IT base classes.** `org.quartz.support.AbstractMongoIT` / `AbstractRedisIT` expose the `SharedContainers` instances with `@ServiceConnection` (Redis: `name = "redis"` on the `GenericContainer`), plus shared plain clients (`mongoClient()`, `redisConnection()`) for non-Spring store tests. Cleanup after each test drops every collection outside `admin`/`config`/`local`, or runs `FLUSHDB`. `MongoServiceConnectionIT` / `RedisServiceConnectionIT` prove the wiring and the cleanup.

## D-014 — Phase 7 persistent stores: one engine, two delegates

**Architecture.** The scheduling semantics of a persistent, cluster-capable store are ported once from Quartz's JDBC `JobStoreSupport` (git history, `284587ea~1`) into `org.quartz.impl.store.PersistentJobStore`, over a `StoreDelegate` SPI of atomic primitives (a port of the useful part of `DriverDelegate`) and a `StoreLock`. `MongoJobStore` and `RedisJobStore` only implement the delegate and a lease lock. This keeps one state machine (trigger states, blocking, misfires, fired-trigger records, recovery, clustering) instead of two, and inherits the JDBC store's proven cluster behaviour. Locks: non-clustered stores use in-JVM locks; clustered stores use a lease (owner + expiry = `lock-timeout`) polled every `acquireTriggersWithinLockRetry`, so a dead node blocks the others for at most the lease. Reads take no lock; single-trigger acquisition without `acquireTriggersWithinLock` relies on the delegate's conditional `WAITING -> ACQUIRED` update. "Now" for scheduling comes from the `SchedulerSignaler` (the Phase 4 `Clock`); check-ins and leases use the wall clock (as the JDBC store did; clock skew between nodes shifts failure detection by the skew).

**Deviations from the JDBC store.**
- Paused **job** groups are persisted, so `pauseJobs(groupEquals(g))` also pauses jobs added to `g` later (the JDBC store never did; `testPauseJobGroupPausesNewJob` was skipped for it upstream; `RAMJobStore` does). `resumeAll` clears them, as `RAMJobStore` does.
- `getPausedTriggerGroups()` hides the internal `pauseAll` marker group.
- No calendar cache (the JDBC store cached calendars when not clustered).
- `getAcquireRetryDelay` returns `retryInterval`.

**Encoding.** Triggers of the four built-in types are stored as structured fields (type, schedule, times as epoch ms, `ZoneId` ids); other `OperableTrigger` classes Java-serialized. Job data is stored natively (MongoDB document / tagged Redis hash fields) when every value is a `String`, `Integer`, `Long`, `Double` or `Boolean` and keys are plain field names, Java-serialized otherwise; `useProperties` restricts values to `String`s (as JDBC). Calendars are Java-serialized (as JDBC).

**MongoDB.** `MongoJobStore(MongoClient, database, JobStoreSettings, MongoJobStoreSettings)`: the plan said `MongoDatabase`, but transactions need the client for sessions. Every primitive is one MongoDB operation, so correctness never depends on transactions; on a replica set / sharded cluster every store operation additionally runs in a transaction (manual commit; an operation is never re-run, since the engine mutates trigger objects as it goes; a commit with unknown outcome is retried) and acquisition then always takes the trigger lock to avoid write conflicts with locked transactions. The "transaction when `MongoTransactionManager` is present" idea of the plan is replaced by detecting replica-set support (Phase 8 handles Spring-managed transactions). Lock documents carry a TTL index on `expiresAt` as a safety net. New optional property `spring.quartz.job-store.mongodb.database` (default: Spring Data's database).

**Redis.** Keys `{keyPrefix}{schedName}:...`; every multi-key change is a Lua script (13 scripts, each tested in `RedisLuaScriptsIT`). Scripts derive the other keys of the scheduler from the prefix instead of declaring them all in `KEYS`: they all share the `{schedName}` hash tag (one slot), so this works on Redis Cluster, but strictly it is outside the Redis Cluster scripting contract. Acquisition reads the `waiting` sorted set bounded by `misfireTime..noLaterThan` plus a second sorted set of ignore-misfire triggers, so long-misfired triggers never crowd out due ones; `clearAllSchedulingData` uses `KEYS` on the scheduler's prefix (O(keys), rare). Durability caveat documented in the README.

**Defaults.** `acquireTriggersWithinLockRetry` gets its meaning (pause between attempts to take a lock held by another instance) and default 50 ms (was a provisional 1 s, D-012). Lock leases: MongoDB 10 min, Redis 30 s (Phase 6 properties).

**Tests.** `org.quartz.store.AbstractJobStoreContractTest` (50 cases: the 15 of the former `AbstractJobStoreTest`, which is deleted because its cases moved, plus 35 new) and `AbstractClusteredJobStoreContractTest` (4 cases; two real schedulers on one store) only need `createJobStore(instanceId)` / `cleanStore()`. Bindings: `RAMJobStoreTest` (standalone), `PersistentJobStoreEngineTest` / `ClusteredPersistentJobStoreEngineTest` (the engine over a test-only in-memory delegate: container-free, fast), `MongoJobStoreIT` / `MongoClusteredJobStoreIT`, `RedisJobStoreIT` / `RedisClusteredJobStoreIT`. The restart-recovery case is skipped (assumption) for `RAMJobStore`, which does not persist. Kit ITs cannot also extend `AbstractMongoIT` / `AbstractRedisIT` (single inheritance), so those base classes expose their shared client and cleanup as public static helpers. The shared Redis container runs with `--appendonly yes`.

**Upstream bugs found by the kit.** `RAMJobStore.pauseTriggers` with a non-`EQUALS` matcher recorded the matcher text instead of the matching group, and `clearAllSchedulingData` kept paused groups: both fixed (one line each). `testStoreTriggerReplacesTrigger` (QUARTZ-606) had been `@Disabled` upstream; its message and expected-value arguments were swapped by the JUnit 5 migration: fixed and re-enabled.

**jspecify.** A `@Nullable` pass was requested and then deferred, by request, to a separate activity after Phase 12 (see the follow-up section at the end of `REFACTOR_PLAN.md`).

## D-015 — Phase 8 job execution in Spring transactions

**Hook.** `JobRunShell` gets one protected hook, `executeJob(Job, JobExecutionContextImpl)`, called where `job.execute` was: after the trigger/job listeners (so a vetoed execution never reaches it) and before the job listeners' `jobWasExecuted` and the store's `triggeredJobComplete`. `SpringTransactionJobRunShell` overrides it, so the transaction covers only `execute` and is always complete before the store hears of the result. Quartz's JTA shell instead began the transaction in `begin()`, around the listeners too, and committed even when the job threw a `JobExecutionException`. The old `begin()` / `complete()` hooks are no-ops kept for Phase 11. `StdSchedulerFactory.Builder.jobRunShellFactory(...)` makes the factory pluggable.

**Rollback rules.** These follow `@Transactional`, plus `JobExecutionException`, the only checked exception `Job#execute` declares. So `RuntimeException`, `Error` and `JobExecutionException` roll back, and `rollbackFor` adds types (only relevant for checked exceptions thrown sneakily). Any other exception commits, as in Spring, and the job still fails. Implemented with `TransactionTemplate` + `RuleBasedTransactionAttribute`: the callback catches the job's throwable, marks the status rollback-only when a rule matches, and the shell rethrows it after completion, so the existing error reporting of `JobRunShell` is unchanged. Failures to begin (e.g. `MANDATORY` without a transaction) or commit fail the job through the same path.

**Timeout.** It is passed to the transaction manager. Because `MongoTransactionManager` does not enforce it (spring-data-mongodb 5.1.1 only records it on the resource holder), the shell also checks it against the scheduler `Clock` when the job returns. If expired, it rolls back and fails the job with `TransactionTimedOutException`. A running job is never interrupted.

**Transaction managers.** They are looked up by bean name (`@ExecuteInTransaction(transactionManager = ...)`), else as the unique or `@Primary` `PlatformTransactionManager`. No `spring.quartz.transaction.transaction-manager` property was added (not in the plan). With `spring.quartz.transaction.enabled=true` startup fails when there is no default manager. With only annotated jobs, a missing manager fails that job's execution. `default-propagation` (Phase 6 YAML) applies to jobs without the annotation when the switch is on. The annotation is found with `AnnotatedElementUtils` (inherited, meta-annotations).

**Where it applies.** `SchedulerFactoryBean` in an application context installs `SpringTransactionJobRunShellFactory` by default (with its job store and clock), unless `setJobRunShellFactory` is given. A scheduler built without Spring keeps `StdJobRunShellFactory`, which ignores the annotation, as Quartz ignored `@ExecuteInJTATransaction` without the JTA factory.

**MongoDB job data joins the transaction (feasible, implemented).** This needs four conditions: the store is a `MongoJobStore`, the job's manager is a `MongoTransactionManager`, the manager's `MongoDatabaseFactory` is `MongoClusterCapable` with the same `MongoClient` instance as the store, and a transaction is actually active (not `NOT_SUPPORTED` / `SUPPORTS` without one). Then a `@PersistJobDataAfterExecution` job's data is written in `beforeCommit` through `MongoDatabaseUtils.getDatabase(storeDatabase, factory)` (session-bound) and the dirty flag is cleared, so `triggeredJobComplete` does not write it again. If the transaction does not commit, the map is restored from a snapshot taken when the transaction began, and the flag is cleared. **Semantics change in that case only:** a rolled-back execution's job-data changes are discarded together with its business writes, whereas Quartz (and every other combination here, e.g. the Redis store) persists job data even when the job failed. The snapshot is shallow: mutable values changed in place are not restored in memory, but they are not written either. Trigger state and fired-trigger records stay in the store's own operations after the business transaction, so the store's correctness never depends on the business transaction.

**Tests.** `SpringTransactionJobRunShellFactoryTest` (attributes, rules, lookup, fail fast); `QuartzAutoConfigurationTest` (switch on with/without a manager, off); `SpringTransactionJobExecutionIT` (MongoDB store and `MongoTransactionManager`: commit, rollback on `JobExecutionException` / runtime exception, checked exception commits, manager by bean name, `REQUIRES_NEW` + timeout reach the manager, `NOT_SUPPORTED`, `MANDATORY`, timeout, plain job without transaction, veto, commit visible before `triggeredJobComplete`, job-data join); `SpringTransactionGlobalSwitchIT`; `RedisStoreMongoTransactionIT` (Redis store, MongoDB business transaction: independent). The ITs use `@DirtiesContext` per method because the base class drops the store's collections (and indexes) after each test.

## D-016 — Phase 9 RAMJobStore removed

**No default store.** `RAMJobStore` and the `memory` store type are deleted. `StdSchedulerFactory` (and so `SchedulerFactoryBean`) fails with a `SchedulerConfigException` ("No JobStore configured: supply one with `StdSchedulerFactory.Builder.jobStore(JobStore)`") when no `JobStore` instance is given. `getDefaultScheduler()`, the no-arg constructor and `Builder.jobStore(JobStoreSettings)` are kept (public API), although creating a scheduler from default settings can no longer succeed and a supplied store takes its settings from its own constructor; whether to delete them is left to Phase 11.

**Store type inference.** The plan asks to fail when "no store type is configured **and** no Mongo/Redis bean exists", which implies that an unset `spring.quartz.job-store.type` with a connection bean present works. So an unset type uses the only store whose connection bean is present (`MongoClient` / `RedisConnectionFactory`). It fails when there is none, or when both are present (ambiguous: the type must be set). A set type without its connection bean fails too. All three throw `JobStoreUnavailableException` (an `IllegalStateException`), explained by `JobStoreUnavailableFailureAnalyzer` (registered in `META-INF/spring.factories`). D-013's "type has no default" is replaced by this. The check runs before `SchedulerFactoryBeanCustomizer`s, so a customizer that sets its own store still needs a connection bean or a type; such applications should define their own `SchedulerFactoryBean`.

**Tests.** Docker-backed `*IT`s on the shared MongoDB container now hold everything that runs a scheduler. `TestSchedulers` builds a `MongoJobStore` (database `test`); callers extend `AbstractMongoIT` or drop its collections in `@AfterEach`.
- Renamed to Mongo ITs (behaviour unchanged): `RAMSchedulerTest` → `MongoSchedulerIT` (binds `AbstractSchedulerTest`), `DefaultSchedulerIT`, `DisallowConcurrentExecutionJobIT`, `InterruptableJobIT`, `PriorityIT`, `Qtz205SchedulerListenerIT`, `QTZ212_SchedulerListener_IT`, `QuartzSchedulerClockIT`, `QuartzMongo{CronTrigger,PauseAndResume}IT` (+ `QuartzMongoTestSupport`), `SchedulerFactoryBeanIT`, `StdSchedulerFactoryIT`. `QTZ336_MissSchedulingChangeSignalIT` stays `@Disabled`, as upstream; its slow store now extends `MongoJobStore` and its 50 s wait is an Awaitility poll delay. The 10 ms sleep inside that store simulates a slow store; it is not a test wait.
- No Redis copies. Scheduler-level behaviour is the same on both stores (one `PersistentJobStore` engine, D-014), and the Redis store already runs the full contract kits plus its auto-configuration IT.
- Kept as unit tests, with no store: `DailyTimeIntervalScheduleBuilderTest` (the scheduler round trip is replaced by `computeFirstFireTime`, which is what `scheduleJob` computes; DTI persistence is covered by `CodecsTest` and the kit). `SchedulerDetailsSetterTest` uses a Mockito `JobStore` (the store is told twice, the thread pool once, as before: 3 + 3 calls in total). `StdSchedulerFactoryTest` keeps the no-store failure and duplicate plugin names.
- **Deleted:** `RAMJobStoreTest`, the kit binding for the deleted store. The kit stays bound to the engine double, MongoDB and Redis. Its restart-recovery case now asserts `supportsPersistence()` instead of skipping.
- **Ported:** `RAMJobStoreClockTest` → `PersistentJobStoreClockTest`, same three cases on the engine over the in-memory test delegate. The engine handles misfires in its misfire pass, not during acquisition as `RAMJobStore` did, so the test runs `recoverMisfiredTriggers()` before acquiring.
- Phase 6 `ApplicationContextRunner` tests: `QuartzAutoConfigurationTest` keeps the cases that never build a store (back-off on a `Scheduler` bean, the three fail-fast paths, `memory` rejected). Everything else moved to `QuartzAutoConfigurationIT`, which runs against the shared containers. **Deviation:** the runner gets `MongoConnectionDetails` / `DataRedisConnectionDetails` beans pointing at those containers, which are the beans `@ServiceConnection` would register. `@ServiceConnection` itself is not used. Tried first, `@ImportTestcontainers(AbstractMongoIT.class)` hands the static container to the context, and the first context to close stopped the JVM-wide container: every later IT then failed with "connection refused". The `@SpringBootTest` ITs keep `@ServiceConnection`; the test context framework caches their context instead of closing it per test. The `ClusteredRAMJobStore` customizer became `spring.quartz.job-store.clustered=true`. New cases: an explicit type chooses between two stores, and two stores without a type fail.
- Adjusted for persistent stores, the only test changes beyond swapping the store:
  - `DefaultSchedulerIT` gives its `JobDetail` a job class. `RAMJobStore` stored a detail without one, but a persistent store must record the class, as Quartz's JDBC store did.
  - `QuartzSchedulerClockIT.repeatedClockFailuresNotifyListenersOnlyOnce`: the clock starts failing only after `start()`. A persistent store reads the scheduling time during start-up recovery, so an always-failing clock now fails `start()` itself. The case still checks that the scheduler thread reports repeated clock failures once.
- `JobStoreUnavailableFailureAnalyzerTest` covers the three analyses, plus a real `SpringApplication` start that prints the analysis (output capture).

**MongoDB store fix found by the converted tests.** At scheduler shutdown the store's own threads are interrupted. A thread interrupted inside a MongoDB transaction could not abort it: with the interrupt flag set, `abortTransaction()` and the abort in `ClientSession.close()` fail before reaching the server. The transaction then stayed open, holding its write locks, until the server's 60 s transaction lifetime expired, plus up to 30 s for the reaper. In tests, every collection drop after such a shutdown waited about 90 s. In production, other nodes would wait on those locks. `MongoJobStore` now aborts with the interrupt flag cleared and then restores it, and an `Error` thrown inside a transaction aborts it as well. `MongoTransactionInterruptIT` reproduces the bug: it fails without the fix (an "inactive transaction" holding write locks is still open) and passes with it. Phase 7 code, fixed here because the Phase 9 ITs exposed it.

**Exit grep.** `grep -r RAMJobStore src` is empty. `REFACTOR_PLAN.md`, `INVENTORY.md` and `DECISIONS.md` still name it as history.

## D-017 — Phase 10 actuator endpoint, health and metrics

**Sources.** `QuartzEndpoint`, `QuartzEndpointWebExtension`, `QuartzEndpointAutoConfiguration` and `QuartzEndpointProperties` are ported from the Spring Boot 4.1.1 `spring-boot-quartz` sources, which are identical to commit `57a1ca37…` (D-013), into `org.quartz.spring.boot.actuate.endpoint` and `org.quartz.spring.boot.autoconfigure`. Headers and authors are kept. Code changes: the trigger getters return `Instant` / `Duration` / `ZoneId`, and jspecify annotations are dropped (D-013; restored in D-020). The **JSON stays Spring Boot's**: intervals are in milliseconds for all four trigger types (the simple trigger's `Duration` is written with `toMillis()`), times are ISO-8601 instants, zones are IDs. Tools that read Boot's `/actuator/quartz` keep working. Boot's `QuartzEndpointTests`, `QuartzEndpointWebExtensionTests` and `QuartzEndpointAutoConfigurationTests` are ported too: 57 cases, `Date` → `Instant`, `TimeZone` → `ZoneId`. They are renamed `*Test` because surefire only runs `*Test`.

**No pause/resume.** Boot's endpoint has no pause or resume operation. Its only write operation triggers a job (`POST …/jobs/{group}/{name}` with `state=running`). The plan says "pause/resume where supported", so the ITs cover that write operation and no new operations are added.

**Optional classes.** The actuator, health and Micrometer dependencies are `optional`. With no auto-configuration metadata processor, nothing filters an auto-configuration class before its conditions are loaded. So each class only carries `@ConditionalOnClass` (an annotation from `spring-boot-autoconfigure`). The actuator- and health-specific conditions (`@ConditionalOnAvailableEndpoint`, `@ConditionalOnEnabledHealthIndicator`) sit on nested configurations. Upstream's class-level placement made a context without actuator fail ("Could not find class OnAvailableEndpointCondition"). `QuartzActuatorAutoConfigurationTest` checks this with a `FilteredClassLoader`.

**Health (`quartz`).** Details: `scheduler`, `instanceId`, `state` (`started` / `standby` / `shutdown`), `jobStore`, `clustered`, and for a started clustered `PersistentJobStore` also `lastCheckin` and `checkinAge`. The status is `DOWN` when the scheduler is shut down, when a store read (`getJobGroupNames()`) fails, or when a clustered instance's own check-in is older than `cluster-checkin-interval` + `cluster-checkin-misfire-threshold`, the age at which the other nodes start recovering it. Otherwise it is `UP`. **Standby is `UP`**: `auto-startup: false` and a startup delay are deliberate, and `DOWN` would fail readiness probes. Check-in age is judged only once the scheduler has started: check-ins begin at start and continue in standby. The store comes from the new `SchedulerFactoryBean.getJobStore()`; `PersistentJobStore.getLastCheckin()` is new as well. `management.health.quartz.enabled=false` switches it off.

**Metrics.** `QuartzMetrics` is a `SchedulerPlugin` bean (`quartzMetrics`). The trigger listener's `triggerMisfired` gets no scheduler context, and a plugin learns its scheduler at `initialize`. It adds a global job and trigger listener. Meters, all tagged `scheduler`:
- `quartz.jobs.executed` and `quartz.jobs.failed` (counters, tag `job.group`);
- `quartz.jobs.duration` (timer, tags `job.group` and `result` `success|failure`);
- `quartz.triggers.misfired` (counter, tag `trigger.group`).

Job and trigger names are not tags, to keep the number of time series bounded. The plugin is active when a `MeterRegistry` bean exists; `management.metrics.quartz.enabled=false` switches it off.

**Dependencies.** `optional`: `spring-boot-actuator-autoconfigure` (listed in Phase 6), `spring-boot-health` (where Boot 4 keeps `HealthIndicator`) and `micrometer-core` (the plan's "optional Micrometer metrics"). Test scope: `spring-boot-starter-webmvc`, needed for the plan's `RANDOM_PORT` tests. It is the only dependency the plan does not name, and the tests call HTTP with the JDK `HttpClient`, so no test client is added.

**Tests.** `QuartzActuatorIT` runs on the shared MongoDB container via `@ServiceConnection`, with one context per method (the base class drops collections). It covers groups, jobs/triggers by group, group summaries for the four trigger types, sanitized job detail, trigger detail, 404/400 cases, triggering a job (`POST` → runs, counted and timed), and health `UP`. `QuartzHealthDownIT` uses its own `mongo:8.0` container with `@ServiceConnection` and 2 s client timeouts; it stops the container and waits until `/actuator/health/quartz` answers 503 `DOWN`. Unit tests: `QuartzHealthIndicatorTest`, `QuartzMetricsTest`, `QuartzActuatorAutoConfigurationTest`.


## D-018 — Phase 12 final verification

**Sample application IT.** `org.quartz.spring.boot.sample.SampleApplicationIT` runs `SampleApplication` (`@SpringBootApplication`, `@Bean JobDetail` + `@Bean Trigger`, a job autowired with `MongoTemplate`) in 8 cases: {MongoDB, Redis} × {standalone, 2-node cluster} × {`spring.quartz.transaction.enabled` true, false}. Each node is a `SpringApplicationBuilder` context (`WebApplicationType.NONE`), so `@ServiceConnection` does not apply (it only works for test contexts): the shared containers are passed as `spring.mongodb.uri` / `spring.data.redis.*` properties. The business data is always in MongoDB, also with the Redis store, because MongoDB is the transactional resource of the sample (the `MongoTransactionManager` bean); this matches `RedisStoreMongoTransactionIT`. The test creates the business collection first: MongoDB cannot create a collection implicitly from concurrent transactions (`WriteConflict`, "namespace already in use"). The clustered cases also check failover: after the first node is closed the second keeps firing, and no scheduled fire time runs twice.

**Bug found and fixed: lock leases left by interrupted threads.** A shutting-down scheduler interrupts its store thread. If that happened during the lease upsert, MongoDB could grant the lease while the driver call failed (`MongoInterruptedException`), and a release on an interrupted thread failed too, so the other nodes waited `lock-timeout` (10 min by default) before acquiring triggers. `LeaseStoreLock` now gives the lease back (owner-guarded, so harmless if it was not granted) when an acquire attempt fails, and releases with the interrupt flag cleared and restored, as `MongoJobStore` already does when it aborts a transaction. Covered by `LeaseStoreLockTest` and the failover cases.

**Bug found and fixed: scheduler shut down after the store's connection stopped.** `SchedulerFactoryBean` (as in Spring) only goes to standby in `SmartLifecycle#stop()` and shuts the scheduler down in `destroy()`, i.e. after the context has stopped its lifecycle beans. `LettuceConnectionFactory` is one (phase 0), so jobs still running at close could not report completion to the Redis store and retried every `retry-interval` until the store shut down, which only happens after the workers finish: with `wait-for-jobs-to-complete-on-shutdown: true` the context close hung forever. `SchedulerFactoryBean` now also listens for its own context's `ContextClosedEvent`, published before any lifecycle bean stops, and shuts the scheduler down there (`destroy()` then does nothing). Restartable `stop()` / `start()` are unchanged. Covered by `SchedulerFactoryBeanIT#schedulerIsShutDownBeforeTheContextStopsItsLifecycleBeans` and the Redis sample cases.

**Phase 4 exit grep.** `MongoJobStore`'s lease lock still used `java.util.Date` for `expiresAt`. It now uses `Instant`: the driver's default codec registry (`Jsr310CodecProvider`) encodes it as a BSON date, so the stored type, the TTL index and the `$lt` comparison are unchanged. A `MongoClient` built with a registry that lacks the JSR-310 codecs would now fail; README notes it.

**Phase 11 not applied.** The Phase 11 removals in `INVENTORY.md` are still awaiting confirmation; Phase 12 was run on the code as it is, and the README does not describe them as done. (Applied afterwards, on 2026-10-21: D-019.)

**Version.** `1.0.0-rc1`, tagged `v1.0.0-rc1` locally (not pushed).

## D-019 — Phase 11 orphan cleanup applied

Confirmed on 2026-10-21 and applied as proposed in `INVENTORY.md` ("Removed in Phase 11", groups A–E), after the Phase 12 commits; `dependency:analyze` was re-run first and reported the same findings. The tag `v1.0.0-rc1` stays on the Phase 12 commit.
- **API removed:** `StdSchedulerFactory()`, `StdSchedulerFactory(QuartzSettings)` and `StdSchedulerFactory.getDefaultScheduler()` (they could only fail: there is no default store); `QuartzSettings` loses its `jobStore` component (now `QuartzSettings(scheduler, threadPool)`) and `StdSchedulerFactory.Builder.jobStore(JobStoreSettings)` is gone (stores take `JobStoreSettings` in their constructors). `StringKeyDirtyFlagMap`'s deprecated transient-data methods are gone, so `JobDataMap` no longer has them.
- **Behaviour:** `QuartzScheduler` always signals scheduling changes (the switch was always on). `SchedulerMetaData#toString` no longer has an RMI branch; `isSchedulerRemote()` stays, documented as always `false`.
- **Logging:** `LocalTaskExecutorThreadPool`, `SchedulerAccessor` and `ResourceLoaderClassLoadHelper` log through SLF4J; their protected `logger` field is now an `org.slf4j.Logger` (subclasses calling commons-logging-only methods would need a change).
- **Build:** `maven-dependency-plugin:analyze-only` runs in `verify` with `failOnWarning`; `junit-jupiter-engine` and `spring-boot-starter-webmvc` are ignored as runtime-only. The MongoDB / Redis starters are no longer declared (optional) dependencies; applications still add a starter, as the README says.
- **Test changed:** `JobDataMapTest` drops its `getAllowsTransientData()` assertion (the method no longer exists); `StdSchedulerFactoryTest.aJobStoreIsRequired` checks the same failure through `builder().build().getScheduler()`. No test deleted.

## D-020 — jspecify nullness

The post-Phase 12 follow-up in `REFACTOR_PLAN.md`. `org.jspecify:jspecify` (version from the Spring Boot BOM) is a compile dependency, so applications and their tools see the annotations.

**Scope (conservative choice; the plan left it open).** The packages written or ported in this refactor are `@NullMarked`: `org.quartz.impl.config`, `org.quartz.impl.store`, `org.quartz.impl.store.codec`, `org.quartz.impl.mongodb`, `org.quartz.impl.redis`, `org.quartz.spring`, `org.quartz.spring.boot.autoconfigure`, `org.quartz.spring.boot.actuate.{endpoint,health,metrics}`. The legacy Quartz packages (`org.quartz`, `org.quartz.core`, `org.quartz.impl`, `.simpl`, `.spi`, triggers, calendars, matchers, listeners, plugins, utils) stay unannotated, i.e. of unspecified nullness: annotating ~100 upstream files whose contracts often say "may be null" only in prose is a separate, larger change.

**Convention (Spring's).** `@NullMarked` in `package-info.java`, `@Nullable` as a type-use annotation (`private @Nullable String name`, `String @Nullable []`, `<T extends @Nullable Object>`). Fields set once by a lifecycle method rather than the constructor (`PersistentJobStore#initialize`, the stores' collection / key holders, `SpringTransactionJobRunShellFactory#initialize`, `QuartzMetrics#initialize`) stay non-null and carry `@SuppressWarnings("NullAway.Init")`, as Spring does.

**Spring ports.** The `@Nullable`s dropped in Phase 6 / 10 (D-013, D-017) are restored to match Spring Framework 7.0.9 and Spring Boot 4.1.1: setter-backed fields, the `getObject()` of the factory beans, `ResourceLoaderClassLoadHelper`, the endpoint's lookups and the web extension.

**Contract changes made explicit.** `StoreDelegate#selectJob/selectTrigger/selectTriggerState/selectCalendar` and `PersistentJobStore#retrieveJob/retrieveTrigger/retrieveCalendar` return `null` when absent; `SchedulerSettings.threadName`, `ThreadPoolSettings.threadNamePrefix`, `SchedulerStateRecord.lastCheckin/checkinInterval`, `PersistentJobStore.MisfireResult.earliestNewTime` and the unset `spring.quartz.*` properties without defaults (`job-store.type`, `job-store.mongodb.database`, `scheduler.thread-name`, `scheduler.context`, `thread-pool.thread-name-prefix`, the history plugin messages) are `@Nullable`. `SchedulerFactoryBean#getJobStore()` and `QuartzHealthIndicator`'s store are nullable.

**Verification.** Checked with NullAway 0.14.2 (JSpecify mode, `OnlyNullMarked`) on Error Prone 2.50.0 in a throw-away build file, until it reported nothing. It is **not** part of the build: CLAUDE.md allows no dependency or plugin beyond those listed, so adding it is left as a decision. A few code changes were needed for the checker to follow existing invariants, with no behaviour change: local copies before null checks (`SchedulerAccessor`'s transaction manager, `SchedulerFactoryBean`'s class-loader override), `Objects.requireNonNull` where a value is always present (Redis fired-trigger hash fields and sorted-set members, a trigger blob, a simple trigger's repeat interval, a recovering instance's last check-in, `MongoStoreDelegate`'s class-load helper), and `List.of()` instead of `null` as the initial value in `doCheckin`.

## D-021 — Java 25 baseline

Requested by the user after Phase 12 (CLAUDE.md said Java 21). `java.version` / `maven.compiler.release` is 25, the enforcer requires JDK 25+, `.sdkmanrc` pins `25.0.4-tem`; README and CLAUDE.md say Java 25. Applications must run on Java 25+.

**Why 25 and not a later release.** 25 is the current LTS; a non-LTS baseline (26, 27) would tie every application to a short-lived JDK. Preview features (structured concurrency, lazy constants) are not used: a published library cannot ship them.

**What it buys.** Virtual threads no longer pin their carrier inside `synchronized` (JEP 491, JDK 24), so `spring.quartz.thread-pool.virtual-threads` has no Java 21 caveat. `ScopedValue` (JEP 506, final in 25) replaces the stores' `ThreadLocal`s (D-022). Virtual threads stay off by default; whether to change that is a separate step.

## D-022 — `ScopedValue` instead of `ThreadLocal` in the stores

With the Java 25 baseline (D-021), the per-thread state of a store operation is a `ScopedValue` bound for the duration of the operation rather than a `ThreadLocal` set and removed by hand.

- `PersistentJobStore`: the held lock names and the pending scheduling signal (two `ThreadLocal`s) are one `Operation`, bound by the outermost `executeInLock`; nested calls see it bound and reuse it, as they used to see the thread-locals set. Order is unchanged: the recorded signal is sent before the outermost lock is released.
- `MongoJobStore`: the transaction's `ClientSession` is bound around the callback in `inTransaction`; the delegate reads it as `null` when unbound.

Both scoped values are instance fields, not `static`, so two stores in one JVM (clustered tests) keep separate state per thread, as the instance `ThreadLocal`s did. Nothing can now leak to the next operation on a pooled thread when a `remove()` is missed. No behaviour change; covered by the existing store, cluster and transaction ITs.

## D-023 — Executor capacity and shutdown in `LocalTaskExecutorThreadPool`

The adapter that runs jobs on an `Executor` (`SchedulerFactoryBean#setTaskExecutor`, and the virtual-thread option) used to report one free thread at all times and do nothing on shutdown, as in Spring. Consequences: the scheduler acquired triggers whether or not the executor had room, so fired triggers waited in the executor's queue (unrunnable by other cluster nodes) or, with a bounded queue, were rejected and their triggers set to `ERROR`; batch acquisition never applied; and `wait-for-jobs-to-complete-on-shutdown` was ignored.

It now counts the jobs it has handed over that have not ended, against a capacity:
- given in the new constructor `LocalTaskExecutorThreadPool(Executor, int capacity)`, or derived in `initialize()`;
- `ThreadPoolTaskExecutor` / `ThreadPoolExecutor`: the core pool size, or the maximum pool size when the queue holds nothing (`SynchronousQueue`). Conservative choice: a queueing pool only grows past its core size once its queue is full, which the adapter never lets happen, so the core size is what runs at once;
- `SimpleAsyncTaskExecutor` with a concurrency limit: that limit;
- anything else: unknown, and the old behaviour (one trigger at a time) is kept.

`blockForAvailableThreads()` waits until there is room (like `SimpleThreadPool`) and returns how much; it returns `0` when interrupted (the scheduler thread is interrupted when it halts) without keeping the interrupt flag, or when the pool shuts down. `shutdown(true)` waits for the handed-over jobs to end; the executor is the application's and is not shut down. `getPoolSize()` reports the capacity (`-1` if unknown).

The virtual-thread executor built by the auto-configuration no longer has its own concurrency limit; it runs in a `LocalTaskExecutorThreadPool` with capacity `thread-count`, so the limit is applied before triggers are acquired instead of by blocking the scheduler thread in `execute`. Virtual threads stay off by default (to be revisited separately).
