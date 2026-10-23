# CLAUDE.md

## Standing Rules
- **Goal:** turn `quartz/` (core scheduler module) into a self-contained Maven library `spring-boot-quartz-scheduler` for Spring Boot 4.x only, Java 25 (was 21 until D-021).
- **Public API compatibility:** keep package `org.quartz` and the usage model unchanged — `JobBuilder`, `TriggerBuilder`, `SimpleScheduleBuilder`, `CronScheduleBuilder`, `CalendarIntervalScheduleBuilder`, `DailyTimeIntervalScheduleBuilder`, `Scheduler`, `Job`, `JobDetail`, `Trigger`, `JobExecutionContext`, listeners, `Calendar` impls, `JobKey`/`TriggerKey`. Only types change: `java.util.Date` → `java.time.Instant`, intervals/millis → `java.time.Duration`, `TimeZone` → `ZoneId`, `java.util.Calendar` → `ZonedDateTime`.
- **Persistence:** only MongoDB and Redis `JobStore`s. No JDBC, no `RAMJobStore` in the final result.
- **Configuration:** no `quartz.properties`, no `Properties`-driven reflection wiring. Everything via `@ConfigurationProperties` bound from `application.yml`; all durations typed as `Duration`.
- **Runtime:** Spring Boot only. No servlet listeners, JTA/JNDI, RMI, JBoss/EE, standalone `main`/shutdown-hook bootstrapping.
- **Build:** Maven only. `./mvnw verify` must pass at the end of every phase (except where a phase explicitly says otherwise). `verify` runs unit tests (surefire, `*Test`) and container tests (failsafe, `*IT`).
- **Build plugins approved by the user** (beyond Maven's own): `fmt-maven-plugin` (google-java-format, rewrites sources on every build — commit its output; D-024), Error Prone + NullAway on the main compile (D-025), and the `publish-artefacts` profile's release / gpg / `central-publishing-maven-plugin` (D-024). Use JDK 25 and run `clean verify` after changing the Java release or plugin set.
- **Nullness:** every package is `@NullMarked` (D-026) and NullAway fails the build on a nullness error. Annotate `@Nullable` (JSpecify, type-use) wherever a value may be null; fields set after construction take `@SuppressWarnings("NullAway.Init")`; a `@SuppressWarnings("NullAway")` needs a comment saying why.
- **Testing — Testcontainers:**
  - Docker is running on the host. Before any test run, check `docker info`; if it fails, **stop and tell me** — never skip, `@Disabled`, or mock out container tests to get green.
  - Anything touching MongoDB or Redis is an `*IT` using real containers: `MongoDBContainer("mongo:8.0")` (single-node replica set → transactions work) and `RedisContainer`/`GenericContainer("redis:7.4")` with port 6379. Pin image tags; never `latest`.
  - Use `spring-boot-testcontainers` + `@ServiceConnection` for Spring context tests; plain `@Testcontainers`/`@Container` for non-Spring store tests.
  - Share containers per JVM via abstract base classes (`AbstractMongoIT`, `AbstractRedisIT`) with `static` containers started once — no container per test method.
  - Clean state between tests by dropping collections / `FLUSHDB`, not by restarting containers.
  - Clustered tests start ≥2 scheduler instances against the **same** container.
  - Use Awaitility for async assertions; no `Thread.sleep` in tests.
  - Long test runs: run `./mvnw verify` in the background and poll, rather than hitting the shell timeout.
- **Spring Boot code embedded in this repo** goes under `org.quartz.spring.boot.*` (never `org.springframework.*`) to avoid split packages/clashes with the official `spring-boot-quartz`.
- **Licensing:** keep Apache 2.0 headers on retained/ported files; add `NOTICE` crediting Quartz and Spring Boot.
- **Working style:** small commits per logical step; never delete a test without saying why; when a decision is ambiguous, record it in `DECISIONS.md` and continue with the conservative choice.
- **Do not** add Lombok, or any other dependency or build plugin without the user's approval (record approvals in `DECISIONS.md`).