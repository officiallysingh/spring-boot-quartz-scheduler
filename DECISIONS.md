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
