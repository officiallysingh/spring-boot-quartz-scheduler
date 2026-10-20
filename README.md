# spring-boot-quartz-scheduler

A Spring Boot 4 (Java 21) library derived from the core of the [Quartz Scheduler](https://github.com/quartz-scheduler/quartz).
It keeps the familiar `org.quartz` API (`JobBuilder`, `TriggerBuilder`, `Scheduler`, `Job`, …) with `java.time`
types, is configured entirely through `application.yml`, and persists jobs in MongoDB or Redis.

> **Work in progress.** The refactor is happening in phases; see [`REFACTOR_PLAN.md`](REFACTOR_PLAN.md)
> and [`DECISIONS.md`](DECISIONS.md). Build and test with `./mvnw verify` (Docker must be running:
> the integration tests use MongoDB and Redis containers).

## Job stores

Set `spring.quartz.job-store.type` to `mongodb` or `redis`; both support clustering
(`spring.quartz.job-store.clustered: true`, unique instance ids, e.g. `spring.quartz.scheduler.instance-id: AUTO`).

- **MongoDB** (`spring-boot-starter-data-mongodb`) uses the application's `MongoClient`. Collections are
  `{collection-prefix}jobs`, `triggers`, `calendars`, `paused_groups`, `fired_triggers`, `schedulers`, `locks`
  (default prefix `qrtz_`) in the application's database or `spring.quartz.job-store.mongodb.database`;
  indexes are created on startup. On a replica set every store operation also runs in a transaction.
- **Redis** (`spring-boot-starter-data-redis`) uses the application's `RedisConnectionFactory`. All keys of a
  scheduler are `{key-prefix}{schedulerName}:...` (default prefix `quartz:`), one hash slot, so Redis Cluster
  works; multi-key changes are Lua scripts.

  **Durability:** the store is only as durable as Redis. Run Redis with AOF persistence
  (`appendonly yes`, `appendfsync everysec`): with RDB snapshots only, every schedule change made since the
  last snapshot (new jobs, fired one-shot triggers, paused groups) is lost when Redis restarts, and with
  `appendfsync everysec` up to one second of changes can be lost on a crash.

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

## License

Apache License 2.0, see [`LICENSE.txt`](LICENSE.txt). Based on Quartz Scheduler (Apache 2.0).
