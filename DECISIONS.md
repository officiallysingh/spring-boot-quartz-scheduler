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
