# INVENTORY — Phase 0

Read-only inventory of the original Quartz repository, produced before any refactoring.
All paths are relative to the repo root. Verdicts: **delete**, **keep**, **evaluate**, **delete (Phase 9)** (= kept as a test harness until RAMJobStore is removed).

## 1. Environment

| Item | Value |
|---|---|
| `java -version` | OpenJDK 21.0.12.1 LTS (Temurin-21.0.12.1+1), pinned via `.sdkmanrc` (`java=21.0.12+1.1-tem`). The shell default is Temurin 26.0.2; builds must use JDK 21 (`sdk env` or `JAVA_HOME=~/.sdkman/candidates/java/21.0.12+1.1-tem`) |
| `docker info` | Docker Desktop, server 29.8.2 — reachable ✔ |
| Git branch | `refactor/spring-boot` |
| HEAD | `741ccc3c` (upstream `main` merge of PR 1470) |
| Original build | Gradle multi-project, toolchain Java 11, version `2.5.1-SNAPSHOT` |

## 2. Root tree

| Path | Verdict | Notes |
|---|---|---|
| `quartz/` (`src/`, `build.gradle`) | **keep `src/`** | Moved to root `src/` in Phase 1; `quartz/build.gradle` deleted |
| `quartz-jobs/` | delete | `DirectoryScanJob`, `FileScanJob`, `NoOpJob`, `ee/{ejb,jms,jmx,mail}` jobs — not part of core |
| `quartz-stubs/` | delete | Compile-only stubs (`oracle.sql.BLOB`, `weblogic.jdbc.*`, `org.quartz.jobs.*ScanListener`) for JDBC/WebLogic code |
| `examples/` | delete | Standalone examples, Gradle-built |
| `docs/` | delete | `index.md` only |
| `checkstyle/` | delete | Also `quartz/src/main/resources/checkstyle.xml` |
| `gradle/`, `gradlew`, `gradlew.bat`, `build.gradle`, `settings.gradle`, `gradle.properties` | delete | Replaced by Maven in Phase 2 |
| `azure-pipelines.yml` | delete | Gradle CI |
| `.github/` | evaluate → delete | Contains only `dependabot.yml` (Gradle ecosystem), `pull_request_template.md`, `stale.yml`; no Gradle workflows. Conservative: delete `dependabot.yml` (Gradle-specific), keep the other two unless you say otherwise |
| `readme.adoc` | delete | Replaced by stub `README.md` (Phase 1) |
| `CONTRIBUTING.md` | delete | Upstream-specific |
| `LICENSE.txt`, `.gitignore`, `.gitattributes` | keep | |
| `REFACTOR_PLAN.md`, `.claude/` (`CLAUDE.md`) | keep | |
| `.gradle/`, `.idea/`, `.DS_Store` | n/a | Untracked local artifacts (not in git); `.gradle/` can be removed locally in Phase 1 |

## 3. Package map — `quartz/src/main/java`

| Package | Files | Verdict | Reason / class-level notes |
|---|---:|---|---|
| `org.quartz` | 43 | **keep** (except below) | Public API. **delete:** `ExecuteInJTATransaction` (replaced Phase 8). **evaluate:** `StatefulJob` (deprecated since 2.0; no internal users) — keep for API compatibility, revisit Phase 11; `TriggerUtils` (no internal users, public helper) — keep |
| `org.quartz.commonj` | 1 | **delete** | `WorkManagerThreadExecutor` — CommonJ/JNDI (EE) |
| `org.quartz.core` | 13 | **keep** (except below) | **delete:** `RemotableQuartzScheduler` (RMI), `QuartzSchedulerMBeanImpl`, `SampledStatistics`, `SampledStatisticsImpl`, `NullSampledStatisticsImpl` (JMX stats). `QuartzScheduler` / `QuartzSchedulerResources` keep, but strip RMI (`bind`/`unBind`, `RMI*` fields) and JMX (`registerJMX`/`unregisterJMX`, `jmx*` fields) branches |
| `org.quartz.core.jmx` | 7 | **delete** (evaluated) | `QuartzSchedulerMBean` + `*Support` CompositeData converters — only used by JMX bean & `RemoteMBeanScheduler`. See §3a |
| `org.quartz.ee.jmx.jboss` | 3 | **delete** | JBoss service MBean |
| `org.quartz.ee.jta` | 4 | **delete** | `JTAJobRunShell`, `JTAJobRunShellFactory`, `JTAAnnotationAwareJobRunShellFactory`, `UserTransactionHelper` |
| `org.quartz.ee.servlet` | 2 | **delete** | `QuartzInitializerListener`, `QuartzInitializerServlet` |
| `org/quartz/ee/jboss/doc-files/quartz-service.xml` | (non-java) | **delete** | JBoss doc file under `src/main/java` |
| `org.quartz.helpers` | 1 | **delete** | `VersionPrinter` — standalone `main()` |
| `org.quartz.impl` | 12 | **keep** (except below) | **delete:** `RemoteScheduler`, `RemoteMBeanScheduler` (RMI/JMX), `QuartzServer` (standalone `main`). **Phase 5 rework:** `StdSchedulerFactory`, `DirectSchedulerFactory`, `SchedulerDetailsSetter` (reflection-based property setter). Keep `StdScheduler`, `JobDetailImpl`, `JobExecutionContextImpl`, `StdJobRunShellFactory`, `DefaultThreadExecutor`, `SchedulerRepository` |
| `org.quartz.impl.calendar` | 7 | **keep** | Calendars; java.time rewrite Phase 4 |
| `org.quartz.impl.jdbcjobstore` (+ `oracle`, `oracle.weblogic`) | 42 + 2 | **delete** | Entire JDBC store, delegates, semaphores |
| `org.quartz.impl.matchers` | 8 | **keep** | `GroupMatcher` etc. — public API |
| `org.quartz.impl.triggers` | 6 | **keep** | Trigger impls; java.time rewrite Phase 4 |
| `org.quartz.listeners` | 7 | **keep** | |
| `org.quartz.management` | 2 | **delete** | `ManagementRESTServiceConfiguration`, `ManagementServer` — unused (references in `QuartzScheduler` are commented out) |
| `org.quartz.plugins` | 1 | **delete** | `SchedulerPluginWithUserTransactionSupport` — JTA; only subclass is `XMLSchedulingDataProcessorPlugin` |
| `org.quartz.plugins.history` | 2 | **keep** | `LoggingJobHistoryPlugin`, `LoggingTriggerHistoryPlugin` |
| `org.quartz.plugins.interrupt` | 1 | **keep** | `JobInterruptMonitorPlugin` — see `DECISIONS.md` D-001 |
| `org.quartz.plugins.management` | 1 | **delete** | `ShutdownHookPlugin` |
| `org.quartz.plugins.xml` | 3 | **delete** | `XMLSchedulingDataProcessorPlugin`, `FileScanJob`, `FileScanListener` |
| `org.quartz.simpl` | 14 | **keep** (except below) | **delete (Phase 9):** `RAMJobStore`. **evaluate (Phase 5/6):** `CascadingClassLoadHelper` & other `*ClassLoadHelper`s (Spring supplies the class loader), `PropertySettingJobFactory` (replaced by `SpringBeanJobFactory`), `SystemPropertyInstanceIdGenerator`, `ZeroSizeThreadPool` (only selectable by class name; no code references). Keep `SimpleThreadPool`, `SimpleJobFactory`, `SimpleInstanceIdGenerator`, `HostnameInstanceIdGenerator`, `SimpleTimeBroker` |
| `org.quartz.spi` | 13 | **keep** | Incl. `SchedulerPlugin`, `JobStore`, `ThreadPool`, `TimeBroker` (existing "now" abstraction — candidate to back the Phase 4 `Clock` injection), `ClassLoadHelper` |
| `org.quartz.utils` | 13 | **keep** (except below) | **delete:** `ConnectionProvider`, `DBConnectionManager`, `PoolingConnectionProvider`, `C3p0PoolingConnectionProvider`, `HikariCpPoolingConnectionProvider`, `JNDIConnectionProvider`. **Phase 5:** `PropertiesParser`. **Orphan candidates:** `CircularLossyQueue`, `FindbugsSuppressWarnings` (§8). Keep `ClassUtils`, `DirtyFlagMap`, `StringKeyDirtyFlagMap`, `Key` |
| `org.quartz.utils.counter`, `.sampled` | 5 + 7 | **delete** | Only used by JMX sampled statistics |
| `org.quartz.utils.weblogic` | 1 | **delete** | `WeblogicConnectionProvider` |
| `org.quartz.xml` | 2 | **delete** | `XMLSchedulingDataProcessor`, `ValidationException` |
| `overview.html`, `*/package.html` (8 files) | — | keep | Javadoc; `package.html` for deleted packages goes with them; review JDBC/JTA mentions Phase 11 |
| `org/quartz/core/mbeans-descriptors.xml` | — | **delete** | JMX descriptor |

### 3a. JMX evaluation
`javax.management` is imported by `core.QuartzScheduler`, `core.QuartzSchedulerMBeanImpl`, `core.jmx.*`, `impl.RemoteMBeanScheduler`, `ee.jmx.jboss.*`. In `QuartzScheduler` the only uses are `registerJMX()` / `unregisterJMX()` gated by `resources.getJMXExport()`, plus the `jmxBean` field used for sampled statistics. No scheduling, store, or listener logic depends on it. **Verdict: delete** (actuator replaces it in Phase 10). Same for RMI: `QuartzScheduler.bind()/unBind()` gated by `resources.getRMIExport()`.

## 4. Resources

| Resource | Verdict | Notes |
|---|---|---|
| `main/resources/org/quartz/impl/jdbcjobstore/tables_*.sql` (27), `liquibase.quartz.init.xml` | delete | JDBC schema |
| `main/resources/org/quartz/xml/job_scheduling_data_{1_8,2_0}.xsd` | delete | XML plugin |
| `main/resources/org/quartz/quartz.properties` | delete (Phase 5) | Default config; values recorded in §7 |
| `main/resources/org/quartz/core/quartz-build.properties` | evaluate | Version info filtered by Gradle `processResources`, read by `QuartzScheduler.getVersion*()` / `VersionTest`. Phase 2: Maven resource filtering (or read from `Package#getImplementationVersion`) |
| `main/resources/checkstyle.xml` | delete | |
| `test/resources/tables_derby_drop.sql`, `container-license-acceptance.txt` (MSSQL) | delete | JDBC tests |
| `test/resources/org/quartz/properties/quartzCustomConnectionProvider.properties` | delete | Connection provider test |
| `test/resources/org/quartz/xml/*` (12 files) | delete | XML tests |
| `test/resources/log4j.properties` | delete | slf4j-log4j12 binding dropped; Phase 2 uses Logback via `spring-boot-test`'s logging, or `slf4j-simple` if needed (record then) |
| `test/resources/org/quartz/*.ser`, `impl/calendar/DailyCalendar-1.5.2.ser` (11 files) | keep → **evaluate Phase 4** | Java-serialization backward-compatibility fixtures for `Date`-based classes; will not deserialize after the java.time change — Phase 4 must delete or regenerate them (record in `DECISIONS.md` then) |

## 5. Tests (`quartz/src/test/java`, 87 files)

No JUnit 4 left — all tests are JUnit 5 (Jupiter); 4 subclasses (`RAMSchedulerTest`, `RAMJobStoreTest`, `JdbcSchedulerTest`, `JdbcJobStoreTest`) have no imports of their own. 16 files use `Thread.sleep` (to be replaced by Awaitility when ported).

### 5a. Delete — JDBC / embedded RDBMS / RDBMS Testcontainers (Phase 2/3)
- `org.quartz`: `JdbcSchedulerTest`, `FlakyJdbcSchedulerTest`
- `org.quartz.core`: `QTZ385Test`, `RecoverJobsTest` (Derby; recovery behaviour → contract kit idea in Phase 7)
- `org.quartz.impl`: `MockConnectionProvider`, `StdSchedulerFactoryCustomConnectionProviderTest`
- `org.quartz.impl.jdbcjobstore` (all 11): `DeleteNonExistsJobTest`, `GaussDBDelegateTest`, `JdbcJobStoreTest`, `JdbcQuartzTestUtilities`, `LiquibaseQuartzInitXmlTest`, `MSSQLDelegateTest`, `MSSQLJdbcStoreTest`, `MariaDBJdbcStoreTest`, `PostgresJdbcStoreTest`, `StdJDBCDelegateTest`, `UpdateLockRowSemaphoreTest`
- `org.quartz.integrations.tests`: `JdbcQuartzDerbyUtilities`, `JobClassNotFoundExceptionErrorsTriggersTest`, `JobDataMapStorageTest`, `QTZ179_TriggerLostAfterDbRestart_Test`, `QTZ283_IgnoreMisfirePolicyJdbcStore_Test`, `QuartzDatabasePauseAndResumeTest`, `QuartzDatabaseSimplePropertiesTest`, `QuartzDerbyCronTriggerTest`, `QuartzDerbyTestSupport`, `QuartzMSSQLDatabaseCronTriggerTest`, `QuartzMSSQLTestSupport`, `StdRowLockSemaphoreTest`
- `org.quartz.simpl`: `SystemPropertyInstanceIdGeneratorTest` (uses Derby via `JdbcQuartzTestUtilities` only to build a scheduler — logic could be re-tested store-agnostically; note when deleting)
- `org.quartz.utils`: `C3p0PoolingConnectionProviderTest`, `HikariCpPoolingConnectionProviderTest`

### 5b. Delete — XML / RMI / JMX (Phase 3)
- `org.quartz.xml`: `XMLSchedulingDataProcessorTest`, `XMLSchedulingDataProcessorNeverDeleteTest`, `XMLSchedulingDataProcessorPluginTest`
- `org.quartz.impl.RemoteMBeanSchedulerTest` (JMX/RMI proxy)

### 5c. Store-agnostic scheduler tests (reuse → contract kit / scheduler ITs; run on RAMJobStore until Phase 9)
- `AbstractJobStoreTest` + `simpl.RAMJobStoreTest` → seed of `AbstractJobStoreContractTest` (Phase 7a)
- `AbstractSchedulerTest` + `RAMSchedulerTest` (contains a Derby-thread check to drop) → scheduler-behaviour suite
- `DefaultSchedulerTest`, `DisallowConcurrentExecutionJobTest`, `InterruptableJobTest`, `PriorityTest`, `Qtz205SchedulerListenerTest`, `DailyTimeIntervalScheduleBuilderTest` (uses scheduler)
- `core`: `QTZ212_SchedulerListener_Test`, `QuartzSchedulerTimeBrokerTest`, `FakeTimeBroker`
- `integrations.tests`: `QTZ336_MissSchedulingChangeSignalTest`, `QuartzMemoryCronTriggerTest`, `QuartzMemoryPauseAndResumeTest`, `QuartzMemoryTestSupport`, `HelloJob`, `TrackingJob`
- `plugins.interrupt.JobInterruptMonitorPluginTest`
- `impl`: `StdSchedulerFactoryTest`, `DirectSchedulerFactoryTest`, `SchedulerDetailsSetterTest` → rewrite/delete with Phase 5 factory rework

### 5d. Pure logic (stay unit tests, no store)
`AnnualCalendarTest`, `CalendarIntervalTriggerTest`, `CronExpressionTest`, `CronScheduleBuilderTest`, `CronTriggerTest`, `DateBuilderTest`, `JobBuilderTest`, `JobDataMapTest`, `JobDetailTest`, `MonthlyCalendarTest`, `Quartz601Test`, `SimpleTriggerTest`, `TriggerBuilderTest`, `TriggerComparatorTest`, `VersionTest`, `SerializationTestSupport`, `core.ListenerManagerTest`, `impl.JobDetailImplTest`, `impl.calendar.BaseCalendarTest`, `impl.calendar.DailyCalendarTest`, `impl.matchers.GroupMatcherTest`, `impl.triggers.DailyTimeIntervalTriggerImplTest`, `impl.triggers.TimeBrokerMisfireTest`, `simpl.PropertySettingJobFactoryTest`, `utils.ClassUtilsTest`, `utils.DirtyFlagMapTest`, `utils.PropertiesParserTest` (dies with `PropertiesParser`, Phase 5), `utils.CircularLossyQueueTest` (dies with `CircularLossyQueue`)

### 5e. Test library usage
- **Hamcrest** (not in the Phase 2 test-dependency list): kept tests `CalendarIntervalTriggerTest`, `CronTriggerTest`, `DisallowConcurrentExecutionJobTest`, `DefaultSchedulerTest`, `impl.JobDetailImplTest`, `QuartzMemoryPauseAndResumeTest`, `QuartzMemoryCronTriggerTest` (+ 13 tests being deleted). Phase 2 must either convert these assertions to AssertJ or add Hamcrest — conservative choice: convert to AssertJ (no unlisted dependency).
- **Mockito:** `JobInterruptMonitorPluginTest` (kept) + 4 JDBC delegate tests (deleted).
- **ASM** (`asm:asm:3.3.1`): only `SchedulerDetailsSetterTest`.

## 6. Legacy time types (`java.util.Date`, `java.util.Calendar`, `TimeZone`, `long` millis/seconds)

Method: regex counts per file — `Date` = `\bDate\b`; util.Calendar = `java.util.Calendar`, `Calendar.<field/getInstance>`, `GregorianCalendar` (excludes `org.quartz.Calendar`); `TimeZone` = `\bTimeZone\b`; long time = `long <name containing Millis|Time|Interval|Threshold|Timeout|Delay|Seconds|Ms>` (fields, params, locals). Counts are occurrences, not distinct declarations.

### main (`quartz/src/main/java`)
| File | Date | util.Calendar/Gregorian | TimeZone | long time fields/params | slated delete |
|---|---:|---:|---:|---:|:-:|
| `src/main/java/org/quartz/Calendar.java` | 0 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/CalendarIntervalScheduleBuilder.java` | 1 | 0 | 5 | 0 |  |
| `src/main/java/org/quartz/CalendarIntervalTrigger.java` | 0 | 1 | 3 | 0 |  |
| `src/main/java/org/quartz/CronExpression.java` | 21 | 110 | 6 | 0 |  |
| `src/main/java/org/quartz/CronScheduleBuilder.java` | 0 | 0 | 3 | 0 |  |
| `src/main/java/org/quartz/CronTrigger.java` | 0 | 1 | 2 | 0 |  |
| `src/main/java/org/quartz/DailyTimeIntervalScheduleBuilder.java` | 7 | 18 | 0 | 3 |  |
| `src/main/java/org/quartz/DailyTimeIntervalTrigger.java` | 0 | 3 | 0 | 0 |  |
| `src/main/java/org/quartz/DateBuilder.java` | 97 | 4 | 10 | 0 |  |
| `src/main/java/org/quartz/JobExecutionContext.java` | 5 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/Scheduler.java` | 4 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/SchedulerMetaData.java` | 5 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/SimpleScheduleBuilder.java` | 0 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/SimpleTrigger.java` | 0 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/TimeOfDay.java` | 11 | 13 | 11 | 0 |  |
| `src/main/java/org/quartz/Trigger.java` | 12 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/TriggerBuilder.java` | 9 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/TriggerUtils.java` | 17 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/commonj/WorkManagerThreadExecutor.java` | 1 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/core/JobRunShell.java` | 0 | 0 | 0 | 2 |  |
| `src/main/java/org/quartz/core/QuartzScheduler.java` | 12 | 0 | 0 | 3 |  |
| `src/main/java/org/quartz/core/QuartzSchedulerMBeanImpl.java` | 8 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/core/QuartzSchedulerResources.java` | 0 | 0 | 0 | 3 |  |
| `src/main/java/org/quartz/core/QuartzSchedulerThread.java` | 2 | 0 | 0 | 12 |  |
| `src/main/java/org/quartz/core/RemotableQuartzScheduler.java` | 5 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/core/SchedulerSignalerImpl.java` | 4 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/core/jmx/CronTriggerSupport.java` | 0 | 0 | 3 | 0 | yes |
| `src/main/java/org/quartz/core/jmx/QuartzSchedulerMBean.java` | 5 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/core/jmx/TriggerSupport.java` | 9 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/impl/DefaultThreadExecutor.java` | 1 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/impl/DirectSchedulerFactory.java` | 0 | 0 | 0 | 12 |  |
| `src/main/java/org/quartz/impl/JobExecutionContextImpl.java` | 9 | 0 | 0 | 3 |  |
| `src/main/java/org/quartz/impl/RemoteMBeanScheduler.java` | 4 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/impl/RemoteScheduler.java` | 4 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/impl/StdScheduler.java` | 4 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/impl/StdSchedulerFactory.java` | 0 | 0 | 0 | 2 |  |
| `src/main/java/org/quartz/impl/calendar/AnnualCalendar.java` | 0 | 29 | 3 | 2 |  |
| `src/main/java/org/quartz/impl/calendar/BaseCalendar.java` | 2 | 23 | 11 | 3 |  |
| `src/main/java/org/quartz/impl/calendar/CronCalendar.java` | 4 | 0 | 8 | 4 |  |
| `src/main/java/org/quartz/impl/calendar/DailyCalendar.java` | 0 | 32 | 21 | 23 |  |
| `src/main/java/org/quartz/impl/calendar/HolidayCalendar.java` | 9 | 2 | 3 | 2 |  |
| `src/main/java/org/quartz/impl/calendar/MonthlyCalendar.java` | 0 | 7 | 3 | 2 |  |
| `src/main/java/org/quartz/impl/calendar/WeeklyCalendar.java` | 0 | 18 | 3 | 2 |  |
| `src/main/java/org/quartz/impl/jdbcjobstore/CalendarIntervalTriggerPersistenceDelegate.java` | 0 | 0 | 3 | 0 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/CronTriggerPersistenceDelegate.java` | 0 | 0 | 2 | 0 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/DriverDelegate.java` | 0 | 0 | 0 | 4 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/FiredTriggerRecord.java` | 0 | 0 | 0 | 4 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/JobStoreSupport.java` | 7 | 0 | 0 | 22 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/MSSQLDelegate.java` | 0 | 0 | 0 | 1 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/PointbaseDelegate.java` | 0 | 0 | 0 | 5 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/SchedulerStateRecord.java` | 0 | 0 | 0 | 4 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/SimpleTriggerPersistenceDelegate.java` | 0 | 0 | 0 | 1 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/StdJDBCDelegate.java` | 20 | 0 | 0 | 20 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/TriggerStatus.java` | 4 | 0 | 0 | 0 | yes |
| `src/main/java/org/quartz/impl/jdbcjobstore/oracle/OracleDelegate.java` | 0 | 0 | 0 | 5 | yes |
| `src/main/java/org/quartz/impl/triggers/CalendarIntervalTriggerImpl.java` | 46 | 47 | 6 | 5 |  |
| `src/main/java/org/quartz/impl/triggers/CronTriggerImpl.java` | 41 | 18 | 8 | 2 |  |
| `src/main/java/org/quartz/impl/triggers/DailyTimeIntervalTriggerImpl.java` | 53 | 23 | 0 | 4 |  |
| `src/main/java/org/quartz/impl/triggers/SimpleTriggerImpl.java` | 55 | 17 | 0 | 14 |  |
| `src/main/java/org/quartz/plugins/history/LoggingJobHistoryPlugin.java` | 17 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/plugins/history/LoggingTriggerHistoryPlugin.java` | 13 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/plugins/interrupt/JobInterruptMonitorPlugin.java` | 0 | 0 | 0 | 3 |  |
| `src/main/java/org/quartz/plugins/xml/XMLSchedulingDataProcessorPlugin.java` | 0 | 0 | 0 | 3 | yes |
| `src/main/java/org/quartz/simpl/RAMJobStore.java` | 7 | 0 | 0 | 6 |  |
| `src/main/java/org/quartz/simpl/SimpleTimeBroker.java` | 4 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/spi/JobStore.java` | 0 | 0 | 0 | 2 |  |
| `src/main/java/org/quartz/spi/MutableTrigger.java` | 3 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/spi/OperableTrigger.java` | 6 | 0 | 0 | 2 |  |
| `src/main/java/org/quartz/spi/SchedulerSignaler.java` | 3 | 0 | 0 | 1 |  |
| `src/main/java/org/quartz/spi/ThreadExecutor.java` | 1 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/spi/TimeBroker.java` | 2 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/spi/TriggerFiredBundle.java` | 13 | 0 | 0 | 0 |  |
| `src/main/java/org/quartz/utils/counter/sampled/SampledCounterImpl.java` | 0 | 0 | 0 | 2 | yes |
| `src/main/java/org/quartz/utils/counter/sampled/TimeStampedCounterValue.java` | 0 | 0 | 0 | 1 | yes |
| `src/main/java/org/quartz/xml/XMLSchedulingDataProcessor.java` | 7 | 0 | 3 | 1 | yes |

**Totals (kept files):** {'Date': 500, 'Calendar(util)': 366, 'TimeZone': 106, 'long-time': 122}  
**Totals (files slated for delete):** {'Date': 74, 'Calendar(util)': 0, 'TimeZone': 11, 'long-time': 73}

### test (`quartz/src/test/java`)
| File | Date | util.Calendar/Gregorian | TimeZone | long time fields/params | slated delete |
|---|---:|---:|---:|---:|:-:|
| `src/test/java/org/quartz/AbstractJobStoreTest.java` | 56 | 0 | 0 | 7 |  |
| `src/test/java/org/quartz/AbstractSchedulerTest.java` | 0 | 0 | 0 | 6 |  |
| `src/test/java/org/quartz/AnnualCalendarTest.java` | 0 | 28 | 3 | 0 |  |
| `src/test/java/org/quartz/CalendarIntervalTriggerTest.java` | 25 | 87 | 14 | 0 |  |
| `src/test/java/org/quartz/CronExpressionTest.java` | 37 | 26 | 17 | 0 |  |
| `src/test/java/org/quartz/DailyTimeIntervalScheduleBuilderTest.java` | 15 | 3 | 0 | 0 |  |
| `src/test/java/org/quartz/DateBuilderTest.java` | 14 | 19 | 13 | 0 |  |
| `src/test/java/org/quartz/DisallowConcurrentExecutionJobTest.java` | 12 | 0 | 0 | 4 |  |
| `src/test/java/org/quartz/MonthlyCalendarTest.java` | 0 | 2 | 0 | 0 |  |
| `src/test/java/org/quartz/PriorityTest.java` | 0 | 5 | 0 | 0 |  |
| `src/test/java/org/quartz/SimpleTriggerTest.java` | 4 | 5 | 3 | 0 |  |
| `src/test/java/org/quartz/TriggerBuilderTest.java` | 6 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/core/FakeTimeBroker.java` | 3 | 0 | 0 | 1 |  |
| `src/test/java/org/quartz/core/QuartzSchedulerTimeBrokerTest.java` | 6 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/impl/RemoteMBeanSchedulerTest.java` | 2 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/impl/triggers/DailyTimeIntervalTriggerImplTest.java` | 55 | 9 | 0 | 0 |  |
| `src/test/java/org/quartz/impl/triggers/TimeBrokerMisfireTest.java` | 12 | 0 | 2 | 0 |  |
| `src/test/java/org/quartz/integrations/tests/HelloJob.java` | 2 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/integrations/tests/JobClassNotFoundExceptionErrorsTriggersTest.java` | 3 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/integrations/tests/QTZ283_IgnoreMisfirePolicyJdbcStore_Test.java` | 2 | 0 | 0 | 0 |  |
| `src/test/java/org/quartz/integrations/tests/QTZ336_MissSchedulingChangeSignalTest.java` | 4 | 0 | 0 | 1 |  |
| `src/test/java/org/quartz/plugins/interrupt/JobInterruptMonitorPluginTest.java` | 0 | 0 | 0 | 1 |  |
| `src/test/java/org/quartz/xml/XMLSchedulingDataProcessorTest.java` | 3 | 12 | 2 | 0 |  |

**Totals (kept files):** {'Date': 261, 'Calendar(util)': 196, 'TimeZone': 54, 'long-time': 20}  
**Totals (files slated for delete):** {}

## 7. Keys read from `quartz.properties`

Source: `StdSchedulerFactory.instantiate()` via `PropertiesParser`, plus bean-style properties applied by reflection (`setBeanProps`) to the thread pool, job store, plugins, listeners, instance-ID generator, job factory, thread executor.
"Fate": **map** = becomes a typed `@ConfigurationProperties` field (Phase 5/6); **drop** = feature removed; **bean** = replaced by Spring bean injection.

### 7a. Scheduler (`org.quartz.scheduler.*`, `org.quartz.*`)

| Key | Target type | Default | Fate |
|---|---|---|---|
| `org.quartz.properties` (system prop: file location) | String | `quartz.properties` | drop |
| `org.quartz.scheduler.instanceName` | String | `QuartzScheduler` | map `scheduler.name` |
| `org.quartz.scheduler.instanceId` | String (`AUTO`, `SYS_PROP`) | `NON_CLUSTERED` | map `scheduler.instance-id` |
| `org.quartz.scheduler.instanceIdGenerator.class` (+ `.*` bean props: `prepend`, `postpend`, `systemPropertyName`) | class name | `SimpleInstanceIdGenerator` | bean (`InstanceIdGenerator`) |
| `org.quartz.scheduler.threadName` | String | `<name>_QuartzSchedulerThread` | map |
| `org.quartz.scheduler.makeSchedulerThreadDaemon` | boolean | false | map |
| `org.quartz.scheduler.threadsInheritContextClassLoaderOfInitializer` | boolean | false | map |
| `org.quartz.scheduler.idleWaitTime` | long ms (≥1000 or -1) | -1 → 30 s in `QuartzSchedulerThread` | map → `Duration` |
| `org.quartz.scheduler.dbFailureRetryInterval` | long ms | 15000 | map → `Duration` (store retry; name generalised) |
| `org.quartz.scheduler.batchTriggerAcquisitionFireAheadTimeWindow` | long ms | 0 | map → `Duration` |
| `org.quartz.scheduler.batchTriggerAcquisitionMaxCount` | int | 1 | map |
| `org.quartz.scheduler.interruptJobsOnShutdown` | boolean | false | map |
| `org.quartz.scheduler.interruptJobsOnShutdownWithWait` | boolean | false | map |
| `org.quartz.scheduler.classLoadHelper.class` | class name | `CascadingClassLoadHelper` | bean / drop (Spring class loader) |
| `org.quartz.scheduler.jobFactory.class` (+ `.*` props: `throwIfPropertyNotFound`, `warnIfPropertyNotFound`) | class name | null → `PropertySettingJobFactory` | bean (`SpringBeanJobFactory`) |
| `org.quartz.scheduler.timeBroker.class` | class name | `SimpleTimeBroker` | bean (`Clock`, Phase 4) |
| `org.quartz.context.key.*` | String map | — | map (`Map<String,String>` scheduler context) |
| `org.quartz.scheduler.wrapJobExecutionInUserTransaction` | boolean | false | drop (Phase 8 `transaction.enabled`) |
| `org.quartz.scheduler.userTransactionURL` | String | null | drop |
| `org.quartz.scheduler.jmx.export` / `.objectName` / `.proxy` / `.proxy.class` (+ `.proxy.*`) | boolean / String | false / generated | drop |
| `org.quartz.scheduler.rmi.export` / `.proxy` / `.registryHost` / `.registryPort` / `.serverPort` / `.createRegistry` / `.bindName` | boolean/String/int | false/false/`localhost`/1099/-1/`never`/generated | drop |
| `org.quartz.managementRESTService.enabled` / `.bind` | boolean / String | false / `0.0.0.0:9889` | drop |
| `org.quartz.threadExecutor.class` (+ `.*`) | class name | `DefaultThreadExecutor` | bean (virtual threads / Spring executor, Phase 6) |

### 7b. Thread pool (`org.quartz.threadPool.*`, applied to `SimpleThreadPool`)

| Key | Type | Default | Fate |
|---|---|---|---|
| `org.quartz.threadPool.class` | class name | `SimpleThreadPool` | bean |
| `.threadCount` | int | -1 (required; `quartz.properties` = 10) | map |
| `.threadPriority` | int | 5 | map |
| `.threadNamePrefix` | String | `<name>_Worker` | map |
| `.makeThreadsDaemons` | boolean | false | map (`daemon`) |
| `.threadsInheritContextClassLoaderOfInitializingThread` | boolean | false | map |
| `.threadsInheritGroupOfInitializingThread` | boolean | true | map |

### 7c. Job store (`org.quartz.jobStore.*`)

| Key | Type | Default | Fate |
|---|---|---|---|
| `org.quartz.jobStore.class` | class name | `RAMJobStore` | map `job-store.type` (`mongodb`/`redis`) |
| `.misfireThreshold` | long ms | RAM 5000 / JDBC 60000 (`quartz.properties` 60000) | map → `Duration` |
| `.isClustered` | boolean | false | map |
| `.clusterCheckinInterval` | long ms | 7500 | map → `Duration` |
| `.maxMisfiresToHandleAtATime` | int | 20 | map |
| `.acquireTriggersWithinLock` | boolean | false | map (store-specific semantics) |
| `.dbRetryInterval` | long ms | 15000 | map → `Duration` (store retry) |
| `.useProperties` | String→boolean | false | map (Mongo job-data encoding, Phase 7b) |
| `.doubleCheckLockMisfireHandler` | boolean | true | evaluate (Phase 7) |
| `.makeThreadsDaemons`, `.threadsInheritInitializersClassLoadContext` | boolean | false | map (store maintenance threads) |
| `.tablePrefix` | String | `QRTZ_` | drop → replaced by `mongodb.collection-prefix` / `redis.key-prefix` |
| `.dataSource`, `.driverDelegateClass`, `.driverDelegateInitString`, `.selectWithLockSQL`, `.useDBLocks`, `.lockOnInsert`, `.dontSetAutoCommitFalse`, `.txIsolationLevelSerializable`, `.useEnhancedStatements`, `.nonManagedTXDataSource`, `.dontSetNonManagedTXConnectionAutoCommitFalse`, `.txIsolationLevelReadCommitted`, `.lockHandler.class` (+ `.lockHandler.*`) | various | — | drop (JDBC) |
| `org.quartz.dataSource.<name>.*` (`driver`, `URL`, `user`, `password`, `maxConnections`, `validationQuery`, `jndiURL`, `jndiAlwaysLookup`, `java.naming.*`, `connectionProvider.class`, pool-specific) | various | — | drop (JDBC) |

Not present in the original: `clusterCheckinMisfireThreshold`, `acquireTriggersWithinLockRetry`, lock timeouts — these are new settings for the Mongo/Redis stores (Phase 5/7).

### 7d. Plugins & listeners

| Key | Type | Fate |
|---|---|---|
| `org.quartz.plugin.<name>.class` + bean props | class name | bean (`SchedulerPlugin` beans) / map for built-ins |
| — `LoggingJobHistoryPlugin`: `jobToBeFiredMessage`, `jobSuccessMessage`, `jobFailedMessage`, `jobWasVetoedMessage` | String (MessageFormat) | map `plugins.job-history.*` |
| — `LoggingTriggerHistoryPlugin`: `triggerFiredMessage`, `triggerMisfiredMessage`, `triggerCompleteMessage` | String | map `plugins.trigger-history.*` |
| — `JobInterruptMonitorPlugin`: `defaultMaxRunTime` | long ms (300000) | map → `Duration` (see D-001) |
| — `ShutdownHookPlugin`: `cleanShutdown`; `XMLSchedulingDataProcessorPlugin`: `fileNames`, `scanInterval`, `failOnFileNotFound`, `failOnSchedulingError` | — | drop |
| `org.quartz.jobListener.<name>.class` (+ props) | class name | bean (`JobListener` beans) |
| `org.quartz.triggerListener.<name>.class` (+ props) | class name | bean (`TriggerListener` beans) |

Default `quartz.properties` content: `instanceName=DefaultQuartzScheduler`, `rmi.export=false`, `rmi.proxy=false`, `wrapJobExecutionInUserTransaction=false`, `threadPool.class=SimpleThreadPool`, `threadCount=10`, `threadPriority=5`, `threadsInheritContextClassLoaderOfInitializingThread=true`, `misfireThreshold=60000`, `jobStore.class=RAMJobStore`.

## 8. Orphan candidates (referenced only by code being deleted)

Computed by grepping each surviving main class's simple name against all other main sources, excluding files slated for deletion.

| Class | Referenced by | Proposal |
|---|---|---|
| `org.quartz.utils.CircularLossyQueue` | only `utils.counter.sampled.SampledCounterImpl` (deleted) + its own test | delete with JMX (Phase 3) + `CircularLossyQueueTest` |
| `org.quartz.utils.FindbugsSuppressWarnings` | nothing | delete (Phase 11) |
| `org.quartz.simpl.ZeroSizeThreadPool` | nothing (selectable only via `threadPool.class`) | evaluate Phase 5 — delete if no programmatic use |
| `org.quartz.impl.DirectSchedulerFactory` | tests only | Phase 5 (merge/delete per plan) |
| `org.quartz.impl.SchedulerDetailsSetter` | `StdSchedulerFactory`, `DirectSchedulerFactory` (reflection helper) | Phase 5 |
| `org.quartz.utils.PropertiesParser` | `StdSchedulerFactory`, JDBC, XML plugin | Phase 5 |
| `org.quartz.simpl.*ClassLoadHelper`, `PropertySettingJobFactory`, `SystemPropertyInstanceIdGenerator` | instantiated by class-name strings in `StdSchedulerFactory` | Phase 5/6 evaluate |
| `org.quartz.StatefulJob` | nothing (deprecated API) | keep for compatibility; Phase 11 |

Not orphans (no internal users, but public API meant for applications): `impl.calendar.*` (Annual/Cron/Daily/Monthly), `impl.matchers.{And,Or,Not,Key,Name}Matcher`, `listeners.Broadcast*Listener`, `listeners.JobChainingJobListener`, `plugins.history.*`, `plugins.interrupt.JobInterruptMonitorPlugin`, `TriggerUtils`.

## 9. External dependencies (Gradle)

From `quartz/build.gradle` (versions in `gradle.properties`); root build adds only the `nexus-publish` and `bnd` plugins.

| Dependency | Scope | Used by | Maven fate |
|---|---|---|---|
| `org.slf4j:slf4j-api` 2.0.16 | implementation | ~30 kept classes (core, simpl, listeners, plugins) | **keep** (version from Boot BOM) |
| `org.slf4j:slf4j-log4j12` | runtimeOnly | logging binding | drop |
| `com.mchange:c3p0` 0.10.1 | implementation | `C3p0PoolingConnectionProvider`, `CUBRIDDelegate` | drop |
| `com.zaxxer:HikariCP` 5.0.1 | implementation | `HikariCpPoolingConnectionProvider` | drop |
| `jboss:jboss-{common,minimal,system,jmx}` 4.0.2 | compileOnly | `ee.jmx.jboss.*` | drop |
| `org.apache.geronimo.specs:geronimo-commonj_1.1_spec` | compileOnly | `commonj.WorkManagerThreadExecutor` | drop |
| `jakarta.xml.bind:jakarta.xml.bind-api` 4.0.5 | implementation | `xml.XMLSchedulingDataProcessor` | drop |
| `jakarta.transaction:jakarta.transaction-api` 2.0.1 | compileOnly + test | `ExecuteInJTATransaction`, `ee.jta.*`, `JTANonClusteredSemaphore`, `SchedulerPluginWithUserTransactionSupport`, XML plugin | drop |
| `jakarta.servlet:jakarta.servlet-api` 6.1.0 | compileOnly | `ee.servlet.*` | drop |
| `project(':quartz-stubs')` | compileOnly | WebLogic/Oracle delegates, `WeblogicConnectionProvider` | drop |
| `org.apache.derby:derby`, `derbyclient`, `derbynet` 10.15.2.0 | test | JDBC tests | drop |
| `asm:asm` 3.3.1 | test | `SchedulerDetailsSetterTest` | drop |
| `org.hamcrest:hamcrest` 3.0 | test | see §5e | drop (convert to AssertJ) |
| `org.junit:junit-bom` 5.11.4, `junit-jupiter-engine`, `junit-jupiter-params` | test | all tests | keep (Boot BOM version) |
| `org.mockito:mockito-core` 5.14.2 | test | `JobInterruptMonitorPluginTest` (+ deleted JDBC tests) | keep (Boot BOM version) |
| `org.testcontainers:{mssqlserver,mariadb,postgresql}` 1.21.3 | test | JDBC store tests | drop (replaced by mongodb + redis containers) |
| `com.microsoft.sqlserver:mssql-jdbc`, `org.mariadb.jdbc:mariadb-java-client`, `org.postgresql:postgresql` | test | JDBC store tests | drop |
| JDK modules `java.sql`, `java.rmi`, `java.management`, `java.naming` | JDK | JDBC, RMI, JMX, JNDI code | removed with that code (Phase 3) |

Build-time specifics to replicate in Maven: `processResources` filters `quartz-build.properties` (`${version}`, `${fullname}`, `${name}`); JAR manifest sets `Automatic-Module-Name: org.quartz` (keep); test task uses `forkEvery 1` (tests may rely on JVM isolation via `SchedulerRepository` singletons — watch in Phase 2).
