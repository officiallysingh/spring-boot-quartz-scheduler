## Phase 0 — Inventory (read-only, no code changes)

- Confirm environment: `java -version` (21+), `docker info`, current git branch. Record in `INVENTORY.md`.
- Produce `INVENTORY.md` containing:
    - Module/folder tree at root with a keep/delete verdict.
    - Package-level map of `quartz/src/main/java` and `quartz/src/test/java` with verdicts:
        - **delete:** `org.quartz.impl.jdbcjobstore.*`, `org.quartz.utils` connection providers (`DBConnectionManager`, `*PoolingConnectionProvider`, `JNDIConnectionProvider`, `ConnectionProvider`), `org.quartz.ee.*` (servlet, jta, jmx/jboss), RMI (`RemoteScheduler`, `RemoteMBeanScheduler`, `QuartzSchedulerResources` RMI fields, `*_Stub`), `JTAJobRunShell`, `JTAAnnotationAwareJobRunShellFactory`, `UserTransactionHelper`, `ExecuteInJTATransaction`, `org.quartz.xml.*`, `org.quartz.plugins.xml.*`, `ShutdownHookPlugin`, `org.quartz.simpl.RAMJobStore` (later — see Phase 9).
        - **keep:** `org.quartz.spi.SchedulerPlugin`, `org.quartz.plugins.history.*` (logging plugins), `JobInterruptMonitorPlugin` (decide in `DECISIONS.md`), core scheduler, thread pool, listeners, calendars, builders, `CronExpression`.
        - **evaluate:** JMX (`QuartzSchedulerMBean*`) — replaced by actuator; mark delete unless something core depends on it.
    - Existing tests: which use JDBC/Derby/H2/HSQL or embedded DBs (to be deleted), which are store-agnostic (to be reused in the contract kit).
    - Every usage of `java.util.Date`, `java.util.Calendar`, `TimeZone`, `long` millis/seconds config fields (grep counts per file).
    - Every key read from `quartz.properties` (`StdSchedulerFactory`, `PropertiesParser`) with its target type.
    - Files referenced only by soon-to-be-deleted code (orphan candidates).
    - External dependencies in the Gradle build and what uses each.

**Exit:** `INVENTORY.md` reviewed by me. No source changes.

---

## Phase 1 — Prune repository

- Delete: `checkstyle/`, `docs/`, `examples/`, `quartz-jobs/`, `quartz-stubs/`, `gradle/`, `gradlew*`, `*.gradle`, `gradle.properties`, `azure-pipelines.yml`, `.github/` workflows that build with Gradle.
- Move `quartz/src` to repo root `src/`; delete the now-empty `quartz/`.
- Rename the root project name from `quartz` to `spring-boot-quartz-scheduler`
- Keep `LICENSE.txt`, `.gitignore`, `.gitattributes`; replace `readme.adoc` with a stub `README.md`.

**Exit:** tree contains only `src/`, license/readme/git files, `CLAUDE.md`, plan files. (No build yet — expected.)

---

## Phase 2 — Maven conversion (baseline, behavior unchanged)

- Add Maven wrapper and `pom.xml`:
    - `groupId` = `com.neolama`, `artifactId` = `spring-boot-quartz-scheduler`, `version` = `0.0.1-SNAPSHOT`.
    - Import `org.springframework.boot:spring-boot-dependencies` (4.x) as BOM in `dependencyManagement` (library, not parent). Testcontainers versions come from this BOM — don't pin them separately.
    - `maven.compiler.release` = 21, `-parameters`.
    - Port Gradle dependencies still needed (slf4j-api). Drop c3p0/HikariCP/JTA/servlet/JDBC driver/embedded-DB deps.
    - Test deps: JUnit 5, AssertJ, Mockito, Awaitility, Testcontainers (`testcontainers-junit-jupiter`, `testcontainers-mongodb`; use the artifact ids the BOM manages), `spring-boot-testcontainers`, `spring-boot-test`.
    - Plugins: compiler, surefire (`*Test`), failsafe (`*IT`, bound to `integration-test`/`verify`), source, javadoc, `maven-enforcer-plugin` (Java 21, Maven 3.9+, dependency convergence).
- Migrate JUnit 4 tests to JUnit 5 if present.
- Delete tests that need an embedded RDBMS (they go with JDBC in Phase 3); note them in `DECISIONS.md`.
- Add `src/test/java/.../support/ContainersSmokeIT` that starts Mongo and Redis containers and pings both — proves Docker wiring now, before anything depends on it.
- Temporarily exclude from compilation (via `<excludes>`) only what cannot compile without removed deps; list them in `DECISIONS.md` — they get deleted in Phase 3.

**Exit:** `./mvnw verify` green: RAMJobStore unit tests pass, `ContainersSmokeIT` passes.

---

## Phase 3 — Remove non-Spring-Boot runtime, JDBC and XML

Delete in this order, compiling after each group:
1. `org.quartz.ee.*`, RMI classes and RMI config fields/branches in `QuartzScheduler`, `QuartzSchedulerResources`, `StdSchedulerFactory`.
2. JTA: `JTAJobRunShell`, `JTAAnnotationAwareJobRunShellFactory`, `UserTransactionHelper`, `@ExecuteInJTATransaction` (replacement comes in Phase 8).
3. JMX MBean export (actuator replaces it).
4. `ShutdownHookPlugin` and any `main()` / standalone bootstrap utilities.
5. `org.quartz.impl.jdbcjobstore.*`, all connection-provider utilities, SQL scripts under `src/main/resources`.
6. `org.quartz.xml.*`, `org.quartz.plugins.xml.*`, XSD resources, `FileScanJob*`, and their tests/test XML fixtures.
7. Remove the Phase 2 `<excludes>`.

Keep `SchedulerPlugin` SPI and `LoggingJobHistoryPlugin`/`LoggingTriggerHistoryPlugin`.

**Exit:** `./mvnw verify` green; `grep -r "java.sql\|javax.transaction\|jakarta.transaction\|java.rmi\|javax.servlet\|jakarta.servlet\|javax.naming"` returns nothing.

---

## Phase 4 — java.time migration

RAMJobStore stays as the test harness for this phase (smaller surface now that JDBC is gone). These are pure unit tests — no containers needed.

- Public API: `Date` → `Instant` everywhere (`Trigger#getStartTime/getEndTime/getNextFireTime/getPreviousFireTime/getFireTimeAfter/getFinalFireTime`, `JobExecutionContext#getFireTime/getScheduledFireTime/...`, `Scheduler#scheduleJob` return, `TriggerBuilder#startAt/endAt`, `Calendar#isTimeIncluded/getNextIncludedTime`, `SchedulerMetaData#getRunningSince`).
- `long` intervals/repeat intervals/misfire thresholds → `Duration` (`SimpleScheduleBuilder.withInterval(Duration)`; keep convenience methods like `withIntervalInSeconds(int)` delegating to `Duration`).
- `TimeZone` → `ZoneId` (`CronScheduleBuilder.inTimeZone(ZoneId)`, `CronExpression`, `DailyTimeIntervalTrigger`, `CalendarIntervalTrigger`).
- Internals: rewrite `CronExpression`, `org.quartz.impl.calendar.*` (`AnnualCalendar`, `HolidayCalendar`, `MonthlyCalendar`, `WeeklyCalendar`, `DailyCalendar`, `CronCalendar`), `DateBuilder`, trigger impls to use `ZonedDateTime`/`LocalDate`/`LocalTime`. `DateBuilder` → keep name, return `Instant`.
- Inject a `java.time.Clock` into `QuartzScheduler`/`QuartzSchedulerThread`/triggers' "now" computation to make tests deterministic.
- Keep millisecond truncation semantics identical to original (fire times truncated to seconds for cron).
- Port all affected tests; add DST-transition tests for cron and calendar-interval triggers.

**Exit:** `./mvnw verify` green; `grep -rn "java.util.Date\|java.util.Calendar\|java.util.TimeZone\|GregorianCalendar" src/main` returns nothing.

---

## Phase 5 — Typed configuration model (replace quartz.properties)

- Create immutable config records in `org.quartz.impl.config` mirroring every key from the inventory, e.g.:
  `SchedulerSettings(String name, String instanceId, boolean instanceIdAuto, Duration idleWaitTime, Duration batchTriggerAcquisitionFireAheadTimeWindow, int batchTriggerAcquisitionMaxCount, boolean interruptJobsOnShutdown, ...)`,
  `ThreadPoolSettings(int threadCount, int threadPriority, String threadNamePrefix, boolean daemon)`,
  `JobStoreSettings(Duration misfireThreshold, boolean clustered, Duration clusterCheckinInterval, Duration clusterCheckinMisfireThreshold, int maxMisfiresToHandleAtATime, Duration acquireTriggersWithinLockRetry, ...)`.
- Replace `StdSchedulerFactory` reflection/`PropertiesParser` wiring with a programmatic `SchedulerFactory` implementation that takes these records plus collaborator instances (`JobStore`, `ThreadPool`, `JobFactory`, plugins, listeners). Delete `PropertiesParser`, `quartz.properties` resource and `StdSchedulerFactory` (or reduce `StdSchedulerFactory` to the programmatic builder — record choice in `DECISIONS.md`).
- `DirectSchedulerFactory` → delete or merge into the new factory.

**Exit:** `./mvnw verify` green; no `java.util.Properties` usage in `src/main` except where unavoidable (document).

---

## Phase 6 — Spring integration + embedded Spring Boot auto-configuration

Note: `spring-context-support`'s `org.springframework.scheduling.quartz` is compiled against the original `Date`-based Quartz API and `LocalDataSourceJobStore` (JDBC) — it **cannot** be used. Port what is needed.

1. Port into `org.quartz.spring` (adapted to java.time + typed config, no `Properties`, no DataSource/JDBC):
   `SchedulerFactoryBean` (SmartLifecycle, startup delay as `Duration`, `waitForJobsToCompleteOnShutdown`, overwrite existing jobs), `SpringBeanJobFactory` (+ `AdaptableJobFactory`), `SchedulerAccessor`, `JobDetailFactoryBean`, `CronTriggerFactoryBean`, `SimpleTriggerFactoryBean`, `SchedulerContextAware`, `QuartzJobBean`, `SimpleThreadPoolTaskExecutor` / `LocalTaskExecutorThreadPool` (allow `ThreadPoolTaskExecutor`/virtual threads as thread pool).
2. Port Spring Boot 4 `spring-boot-quartz` auto-configuration from commit `57a1ca37c36f3cf355211aa8dea8c500b2dd3a09` (`module/spring-boot-quartz/src/main/java/org/springframework/boot/quartz`) into `org.quartz.spring.boot.autoconfigure`:
   `QuartzAutoConfiguration`, `QuartzProperties`, `SchedulerFactoryBeanCustomizer`, `JobStoreType` (values: `MONGODB`, `REDIS`). Drop `QuartzDataSource`, `QuartzTransactionManager`-for-JDBC, `QuartzDataSourceScriptDatabaseInitializer`, `QuartzJdbcProperties`, SQL init.
   Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
3. `QuartzProperties` (prefix `spring.quartz`, record-style, `@DefaultValue`) binds all Phase 5 records. Example target YAML:
   ```yaml
   spring:
     quartz:
       auto-startup: true
       startup-delay: 0s
       wait-for-jobs-to-complete-on-shutdown: true
       overwrite-existing-jobs: false
       scheduler:
         name: app-scheduler
         instance-id: AUTO
         idle-wait-time: 30s
         batch-trigger-acquisition-max-count: 1
         batch-trigger-acquisition-fire-ahead-time-window: 0s
       thread-pool:
         thread-count: 10
         virtual-threads: false
       job-store:
         type: mongodb          # mongodb | redis
         misfire-threshold: 60s
         clustered: true
         cluster-checkin-interval: 15s
         cluster-checkin-misfire-threshold: 20s
         mongodb:
           collection-prefix: qrtz_
           lock-timeout: 10m
         redis:
           key-prefix: "quartz:"
           lock-timeout: 30s
       plugins:
         job-history: { enabled: true }
         trigger-history: { enabled: true }
       transaction:
         enabled: false
         default-propagation: REQUIRED
   ```
4. Add `spring-boot-configuration-processor` (optional) for metadata.
5. Auto-register all `JobDetail`, `Trigger`, `Calendar`, `JobListener`, `TriggerListener`, `SchedulerListener`, `SchedulerPlugin` beans (as Spring Boot does).

Dependencies: `spring-boot-autoconfigure`, `spring-context`, `spring-tx`; optional `spring-boot-starter-data-mongodb`, `spring-boot-starter-data-redis`, `spring-boot-actuator-autoconfigure`.

Temporary: allow `job-store.type: memory` bound to RAMJobStore for tests only (removed in Phase 9).

Tests:
- `ApplicationContextRunner` unit tests (`*Test`, `memory` store): beans created, properties bound with `Duration`, customizers applied, jobs autowired via `SpringBeanJobFactory`, lifecycle start/stop, conditions back off when user defines own `Scheduler`.
- Add `AbstractMongoIT` / `AbstractRedisIT` base classes (static shared containers, `@ServiceConnection`, cleanup hooks) for use from Phase 7 on.

**Exit:** `./mvnw verify` green.

---

## Phase 7 — JobStore contract test kit, then MongoDB and Redis stores

### 7a — Contract tests (TCK)
- Extract an abstract `AbstractJobStoreContractTest` (JUnit 5) from RAMJobStore tests + original JDBC store behaviors: store/retrieve/replace jobs & triggers, pause/resume (groups, `GroupMatcher`), calendars, `acquireNextTriggers` ordering by next fire time then priority, `triggersFired`, `triggeredJobComplete`, `@DisallowConcurrentExecution` blocking, `@PersistJobDataAfterExecution`, misfire handling per instruction, recovery (`requestsRecovery`), durability, clear/shutdown.
- `AbstractClusteredJobStoreContractTest`: two scheduler instances on the same store — no trigger fires twice (count executions under load, e.g. 200 triggers × 2 nodes), failed instance's in-flight jobs recovered (shut one node down without waiting), check-in timeout detection, lock expiry after node crash.
- Abstract hooks only: `createJobStore(String instanceId)`, `cleanStore()`. No container knowledge in the kit.
- Run RAMJobStore (non-clustered kit only) against it to validate the kit.

### 7b — MongoDB store (`org.quartz.impl.mongodb.MongoJobStore`)
- Uses `MongoTemplate`/`MongoClient` bean (Spring Data MongoDB). Collections: `{prefix}jobs`, `{prefix}triggers`, `{prefix}calendars`, `{prefix}paused_groups`, `{prefix}fired_triggers`, `{prefix}schedulers`, `{prefix}locks`.
- Unique compound indexes on `(schedName, group, name)`; index `(schedName, state, nextFireTime, priority)` for acquisition. Create indexes on start.
- Job data map: serialize as BSON document for simple types; fallback Java-serialized binary only if `useProperties=false` (mirror original semantics).
- Atomic state transitions via `findAndModify`/conditional updates (`state: WAITING → ACQUIRED`); no reliance on multi-document transactions for correctness. Use transactions opportunistically when `MongoTransactionManager` present and replica set detected.
- Clustering: `schedulers` check-in docs (`lastCheckin`, `checkinInterval`), lock docs with owner + `expiresAt` (TTL index as safety net), recovery of dead instances' `fired_triggers`.
- `MongoJobStoreIT` + `MongoClusteredJobStoreIT` extend the contract kits via `AbstractMongoIT` (`mongo:8.0`, replica set). Add an IT asserting indexes exist after startup.

### 7c — Redis store (`org.quartz.impl.redis.RedisJobStore`)
- Uses `RedisConnectionFactory` / `StringRedisTemplate` (Lettuce). Keys hash-tagged per scheduler (`quartz:{schedName}:...`) so all keys share one slot (Redis Cluster safe).
- Structures: hashes for jobs/triggers/calendars, sorted set `triggers:waiting` scored by next fire epoch-millis, sets for groups/paused groups/blocked jobs, hash for fired triggers, hash for scheduler check-ins.
- All multi-key mutations (acquire, fire, complete, misfire, recovery) as Lua scripts (`RedisScript`) for atomicity.
- Distributed lock: `SET key owner NX PX` + Lua compare-and-delete release; lock timeout from config.
- Document durability caveat (AOF `everysec` recommended) in README.
- `RedisJobStoreIT` + `RedisClusteredJobStoreIT` extend the contract kits via `AbstractRedisIT` (`redis:7.4`, started with `--appendonly yes`). Add a unit test per Lua script using the same container.

**Exit:** `./mvnw verify` green — both stores pass standalone + clustered contract suites against real containers.

---

## Phase 8 — Job execution in Spring transactions

- Replace JTA run-shell with `SpringTransactionJobRunShellFactory` / `SpringTransactionJobRunShell` using `PlatformTransactionManager` + `TransactionTemplate`.
- New annotation `org.quartz.ExecuteInTransaction` (replacement for `@ExecuteInJTATransaction`): `transactionManager` (bean name), `propagation`, `isolation`, `timeout` (seconds, as Spring), `readOnly`, `rollbackFor`.
- Global switch `spring.quartz.transaction.enabled` = wrap every job; otherwise only annotated jobs.
- Semantics: `JobExecutionException` / runtime exceptions roll back; `vetoed` executions don't open a transaction; transaction completes before `triggeredJobComplete` is reported to the store.
- When the active store is Mongo and the job's transaction manager is `MongoTransactionManager`, `@PersistJobDataAfterExecution` update joins that transaction (document in `DECISIONS.md` if not feasible).
- Tests (`*IT`, Mongo container with `MongoTransactionManager`): job writes a document then succeeds → committed; job writes then throws → rolled back (assert collection empty); per-job manager selection by bean name; `REQUIRES_NEW`; timeout; non-annotated job runs without a transaction when global switch is off. Run the same scheduler on Redis store with Mongo as the business TX manager to prove store and business TX are independent.

**Exit:** `./mvnw verify` green.

---

## Phase 9 — Remove RAMJobStore

- Delete `RAMJobStore`, `memory` store type, and its contract-kit binding.
- Convert remaining tests that used RAMJobStore:
    - Scheduler-behavior tests → `*IT` on `AbstractMongoIT` (and Redis where behavior differs).
    - Pure logic tests (cron, calendars, builders) stay unit tests and must not need a store.
    - Phase 6 `ApplicationContextRunner` tests → `@ServiceConnection` containers.
- If no store type is configured and no Mongo/Redis bean exists, auto-config fails fast with a clear `FailureAnalyzer` message (unit test for the analyzer).

**Exit:** `./mvnw verify` green; `grep -r RAMJobStore` empty.

---

## Phase 10 — Actuator

- Port `QuartzEndpoint`, `QuartzEndpointWebExtension` and their auto-configuration (`QuartzEndpointAutoConfiguration`, `QuartzEndpointProperties` for `show-values`/sanitization) from the same Spring Boot commit into `org.quartz.spring.boot.actuate`, adapted to `Instant`/`Duration`/`ZoneId`.
- Add `HealthIndicator` (`quartz`): scheduler started/standby/shutdown, store reachable, cluster instance check-in age.
- Optional Micrometer metrics: jobs executed/failed (counter), execution duration (timer), misfires.
- All actuator classes `@ConditionalOnClass` on actuator; actuator dependency `optional`.
- Register in `AutoConfiguration.imports`.
- Tests (`*IT`, `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ServiceConnection` Mongo): `/actuator/quartz` jobs, triggers, groups, trigger/job detail, pause/resume where supported; health `UP`, then health `DOWN` after stopping the container (use a dedicated non-shared container for this test).

**Exit:** `./mvnw verify` green.

---

## Phase 11 — Orphan cleanup

- Find and delete: unreferenced classes (use `jdeps`, grep of simple names, and a temporary `maven-dependency-plugin:analyze` run), unused resources, empty packages, dead config fields, unused constants in `StdSchedulerFactory`/`QuartzSchedulerResources`, obsolete `package-info.java`, leftover JDBC/XML/JTA references in Javadoc, unused test fixtures/helpers.
- Remove unused dependencies reported by `dependency:analyze`.
- Report the list in `INVENTORY.md` (section "Removed in Phase 11") before deleting; wait for my confirmation.

**Exit:** `./mvnw verify` green; `dependency:analyze` clean; no unused-import warnings.

---

## Phase 12 — Final verification and docs

- Sample Spring Boot app IT in `src/test` (`@SpringBootApplication` test config, `@Bean JobDetail` + `@Bean Trigger`), parameterized over: {Mongo, Redis} × {standalone, 2-node clustered} × {transaction on, off}. 2-node = two `SpringApplicationBuilder` contexts sharing one container.
- `README.md`: Maven coordinates, `application.yml` reference (generated from configuration metadata), API changes vs original Quartz (Date → Instant, etc.), store requirements (Mongo replica set for transactions, Redis persistence), how to run tests (Docker required, `./mvnw verify`), migration notes.
- Verify `spring-configuration-metadata.json` generated; `./mvnw javadoc:javadoc` without errors.
- Final `grep` checks from Phases 3, 4, 9 all empty.

**Exit:** all green; tag `v1.0.0-rc1`.

---

## Follow-up after Phase 12 — jspecify nullness (separate activity)

Requested on 2026-10-20 and deferred, by request, until the phases above are done.

- Add `org.jspecify:jspecify` (managed by the Spring Boot BOM) and annotate with Spring's convention: `@NullMarked` in `package-info.java`, `@Nullable` on everything that accepts or returns `null`.
- Scope to decide then: the code written or ported in this refactor (`org.quartz.impl.config`, `org.quartz.impl.store`, `org.quartz.impl.mongodb`, `org.quartz.impl.redis`, `org.quartz.spring`, `org.quartz.spring.boot.*`), or also the legacy Quartz core packages.
- Restore the `@Nullable` annotations dropped from the Spring ports (see D-013) and update D-013.

