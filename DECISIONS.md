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
