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
