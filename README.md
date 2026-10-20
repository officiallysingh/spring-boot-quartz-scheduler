# spring-boot-quartz-scheduler

A Spring Boot 4 (Java 21) library derived from the core of the [Quartz Scheduler](https://github.com/quartz-scheduler/quartz).
It keeps the familiar `org.quartz` API (`JobBuilder`, `TriggerBuilder`, `Scheduler`, `Job`, …) with `java.time`
types, is configured entirely through `application.yml`, and persists jobs in MongoDB or Redis.

> **Work in progress.** The refactor is happening in phases; see [`REFACTOR_PLAN.md`](REFACTOR_PLAN.md)
> and [`DECISIONS.md`](DECISIONS.md). There is no build yet: the Maven build arrives in Phase 2.

## License

Apache License 2.0, see [`LICENSE.txt`](LICENSE.txt). Based on Quartz Scheduler (Apache 2.0).
