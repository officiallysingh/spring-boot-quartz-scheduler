[![Java](https://img.shields.io/badge/java-25-blue.svg)](https://www.oracle.com/java/technologies/)
[![Spring Boot](https://img.shields.io/badge/spring_boot-4.1.1-blue.svg)](https://spring.io/projects/spring-boot)
[![Quartz Scheduler](https://img.shields.io/badge/quartz-scheduler-blue.svg)](https://www.quartz-scheduler.org/)
[![Maven](https://img.shields.io/badge/maven-3.9.x-blue.svg)](https://maven.apache.org/)
[![Docker](https://img.shields.io/badge/docker-compose-blue.svg)](https://www.docker.com/)
[![Test Containers](https://img.shields.io/badge/testcontainers-2.0.5-blue.svg)](https://testcontainers.com/)

# Spring Boot Quartz Scheduler

**A persistent, clusterable job scheduler for Spring Boot 4 applications**, with jobs and triggers stored in
**MongoDB** or **Redis**, configured entirely from `application.yml` and Spring beans.
It keeps the well known `org.quartz` programming model ([`JobBuilder`](src/main/java/org/quartz/JobBuilder.java), [`TriggerBuilder`](src/main/java/org/quartz/TriggerBuilder.java), [`Scheduler`](src/main/java/org/quartz/Scheduler.java), [`Job`](src/main/java/org/quartz/Job.java), …)
with `java.time` types throughout. Requires **Java 25+** and **Spring Boot 4.x**.

## Table of Contents
1. [Introduction](#introduction)
2. [Features](#features)
3. [Installation](#installation)
4. [Quick start](#quick-start)
5. [Writing jobs](#writing-jobs)
6. [Triggers and schedules](#triggers-and-schedules)
7. [Calendars](#calendars)
8. [Scheduling at run time](#scheduling-at-run-time)
9. [Listeners](#listeners)
10. [Plugins](#plugins)
11. [Thread pool](#thread-pool)
12. [Job stores](#job-stores)
    - [MongoDB](#mongodb)
    - [Redis](#redis)
13. [Clustering](#clustering)
14. [Transactions](#transactions)
15. [Logging](#logging)
16. [Actuator: endpoint, health and metrics](#actuator)
17. [Customizations](#customizations)
18. [Configuration reference](#configuration-reference)
19. [Testing your application](#testing-your-application)
20. [Coming from Quartz or `spring-boot-starter-quartz`](#coming-from-quartz)
21. [Building from source](#building-from-source)
22. [License](#license)

## Introduction

Most applications need some work done on a schedule: send the morning report, settle payments every five minutes,
retry failed webhooks, purge expired data at night. Doing it with `@Scheduled` is fine on one instance, but as soon
as the application runs on two or more instances, every instance fires every job, a job that was running when an
instance went down is lost, and a restart forgets one-off jobs scheduled at run time.

**spring-boot-quartz-scheduler** solves this by keeping the schedule in the database the application already uses.
Every instance shares the same schedule, each firing runs on exactly one instance, and when an instance dies its
in-flight work is picked up by the others. Jobs are plain Spring-managed classes with dependency injection, and
everything, from the thread pool to the store, is configured through typed `spring.quartz.*` properties.

> [!NOTE]
> There is no in-memory store: the scheduler always persists to MongoDB or Redis, so jobs survive restarts.
> For local development a single container is enough, see [Quick start](#quick-start).

## Features

* **Persistent job stores** for **MongoDB** ([`MongoJobStore`](src/main/java/org/quartz/impl/mongodb/MongoJobStore.java)) and **Redis** ([`RedisJobStore`](src/main/java/org/quartz/impl/redis/RedisJobStore.java)), using the
  application's own Spring Boot connection.
* **Clustering** out of the box: any number of instances share one schedule, with fail-over and recovery of the
  jobs of a failed instance.
* **Spring Boot auto-configuration**: [`JobDetail`](src/main/java/org/quartz/JobDetail.java), [`Trigger`](src/main/java/org/quartz/Trigger.java), [`Calendar`](src/main/java/org/quartz/Calendar.java), listener and plugin beans are picked up
  automatically; inject [`Scheduler`](src/main/java/org/quartz/Scheduler.java) anywhere.
* **Spring-managed jobs**: constructor or field injection, `@Value`, job data applied to setters.
* **Rich scheduling**: simple intervals, cron expressions with time zones, calendar intervals (every month, every
  year), daily time windows (every 15 minutes between 09:00 and 17:00 on weekdays) and exclusion calendars
  (holidays, weekends, maintenance windows).
* **`java.time` API**: `Instant`, `Duration`, `ZoneId`, `ZonedDateTime` instead of `Date`, `long` millis and
  `TimeZone`.
* **Virtual threads** as an option, or your own Spring `TaskExecutor`.
* **Transactions around jobs** with [`@ExecuteInTransaction`](src/main/java/org/quartz/ExecuteInTransaction.java) or for all jobs.
* **Observability**: MDC keys per execution, an actuator endpoint, a health indicator and Micrometer metrics.
* **Fail fast**: wrong or removed configuration stops the application with a clear explanation.
* **IDE support**: configuration metadata for completion and documentation of every `spring.quartz.*` property.

## Installation

> **Current version: 1.0.0-rc1**

Add the library and the Spring Boot starter of the store you use to your application.

Maven
```xml
<dependency>
    <groupId>com.neolama</groupId>
    <artifactId>spring-boot-quartz-scheduler</artifactId>
    <version>1.0.0-rc1</version>
</dependency>

<!-- MongoDB store -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
<!-- or Redis store -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```
Gradle
```groovy
implementation 'com.neolama:spring-boot-quartz-scheduler:1.0.0-rc1'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb' // or spring-boot-starter-data-redis
```

Optional extras:
* `spring-boot-starter-actuator` for the [`quartz` endpoint and health indicator](#actuator), plus a Micrometer
  registry for [metrics](#actuator).

> [!IMPORTANT]
> Do not add Spring Boot's own `spring-boot-starter-quartz` next to this library: it replaces it, and both would try
> to configure a scheduler. The jar is built on Java 25 and works with Spring Boot 4.x only (built against 4.1.1).

## Quick start

**1. Start a database.** For local development, add a `compose.yaml` to the root of **your application** (the
library needs none of its own). It runs MongoDB as a single-node replica set, so that transactions work, and Redis
with AOF persistence. Keep only the service of the store you use:

```yaml
services:
  mongodb:
    image: mongo:8.0
    command: ["--replSet", "rs0", "--bind_ip_all"]   # a replica set enables transactions
    ports: ["27017:27017"]
    volumes: ["mongodb-data:/data/db"]
    healthcheck:
      # initiates the replica set on the first start, then reports its status
      test: mongosh --quiet --eval "try { rs.status().ok } catch (e) { rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]}).ok }"
      interval: 5s
      retries: 10
      start_period: 10s

  redis:
    image: redis:7.4
    command: ["redis-server", "--appendonly", "yes"]  # AOF: the schedule survives Redis restarts
    ports: ["6379:6379"]
    volumes: ["redis-data:/data"]

volumes:
  mongodb-data:
  redis-data:
```

Start it from the application's folder:

```bash
docker compose up -d
```

The replica set member is named `localhost:27017`, so connect from the host with
`mongodb://localhost:27017/app?replicaSet=rs0`. An application that itself runs in the same compose project
connects with `directConnection=true` instead (`mongodb://mongodb:27017/app?directConnection=true`).

**2. Configure the connection and the scheduler** in `application.yml`:

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/app?replicaSet=rs0
  quartz:
    scheduler:
      name: app                 # shared by all instances of the application
```

With only MongoDB (or only Redis) on the classpath, that's all: the store is chosen automatically.

**3. Write a job:**

```java
public class DailyReportJob implements Job {

    private final ReportService reportService;

    public DailyReportJob(ReportService reportService) { // jobs are created by Spring and can be injected
        this.reportService = reportService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        reportService.sendDailyReport(context.getScheduledFireTime()); // an Instant
    }
}
```

**4. Declare the job and when it runs** as beans:

```java
@Configuration
class SchedulingConfiguration {

    @Bean
    JobDetail dailyReportJob() {
        return JobBuilder.newJob(DailyReportJob.class)
            .withIdentity("dailyReport", "reports")
            .storeDurably()
            .build();
    }

    @Bean
    Trigger dailyReportTrigger(JobDetail dailyReportJob) {
        return TriggerBuilder.newTrigger()
            .forJob(dailyReportJob)
            .withIdentity("dailyReport", "reports")
            .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(6, 30).inTimeZone(ZoneId.of("Asia/Kolkata")))
            .build();
    }
}
```

Start the application: the scheduler starts with it and logs a summary of its store, thread pool and cluster
settings. The job and trigger are stored on the first start; set `spring.quartz.overwrite-existing-jobs: true` to
update stored definitions from the beans on every start.

## Writing jobs

A job implements [`Job`](src/main/java/org/quartz/Job.java). It is created for every execution by [`SpringBeanJobFactory`](src/main/java/org/quartz/spring/SpringBeanJobFactory.java), which:

* creates it through Spring, so constructor injection, `@Autowired` fields and `@Value` work as in any bean;
* sets job data entries on matching setters (from the scheduler context, then the job's [`JobDataMap`](src/main/java/org/quartz/JobDataMap.java), then the
  trigger's).

```java
public class SendInvoiceJob implements Job {

    @Autowired
    private InvoiceService invoiceService;

    private String customerId; // set from the job data entry "customerId"

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            invoiceService.send(customerId);
        } catch (MailException ex) {
            throw new JobExecutionException(ex, true); // true = run the job again right away
        }
    }
}
```

Extend [`QuartzJobBean`](src/main/java/org/quartz/spring/QuartzJobBean.java) instead to implement [`QuartzJobBean.executeInternal()`](src/main/java/org/quartz/spring/QuartzJobBean.java#L79); it applies the job data to its own bean
properties. Job data is read with [`JobExecutionContext.getMergedJobDataMap()`](src/main/java/org/quartz/JobExecutionContext.java#L109) (the job's entries overridden by the
trigger's).

Annotations on the job class:

| Annotation | Effect |
|---|---|
| [`@DisallowConcurrentExecution`](src/main/java/org/quartz/DisallowConcurrentExecution.java) | Never runs two executions of the same [`JobDetail`](src/main/java/org/quartz/JobDetail.java) at once, across the whole cluster |
| [`@PersistJobDataAfterExecution`](src/main/java/org/quartz/PersistJobDataAfterExecution.java) | Stores changes the execution made to the job's [`JobDataMap`](src/main/java/org/quartz/JobDataMap.java) (typically with [`@DisallowConcurrentExecution`](src/main/java/org/quartz/DisallowConcurrentExecution.java)) |
| [`@ExecuteInTransaction`](src/main/java/org/quartz/ExecuteInTransaction.java) | Runs the job in a Spring transaction, see [Transactions](#transactions) |

Options on [`JobBuilder`](src/main/java/org/quartz/JobBuilder.java):

| Method | Effect |
|---|---|
| [`JobBuilder.withIdentity()`](src/main/java/org/quartz/JobBuilder.java#L129) | Name and group, the [`JobKey`](src/main/java/org/quartz/JobKey.java) |
| [`JobBuilder.storeDurably()`](src/main/java/org/quartz/JobBuilder.java#L231) | Keeps the job when no trigger points to it (needed for jobs only run on demand) |
| [`JobBuilder.requestRecovery()`](src/main/java/org/quartz/JobBuilder.java#L202) | When the instance running it fails, another instance runs it again |
| [`JobBuilder.usingJobData()`](src/main/java/org/quartz/JobBuilder.java#L256) | Adds job data entries; prefer simple values (strings, numbers, booleans) |
| [`JobBuilder.withDescription()`](src/main/java/org/quartz/JobBuilder.java#L174) | Free text, shown by the actuator endpoint |

A job that should stop when asked implements [`InterruptableJob`](src/main/java/org/quartz/InterruptableJob.java): [`InterruptableJob.interrupt()`](src/main/java/org/quartz/InterruptableJob.java#L73) is called by
[`Scheduler.interrupt()`](src/main/java/org/quartz/Scheduler.java#L749) and by the [`JobInterruptMonitorPlugin`](#plugins).

> [!TIP]
> Keep job data small and simple: strings, numbers and booleans are stored as typed fields. Anything else makes the
> whole map Java-serialized, which ties stored jobs to your class versions.

## Triggers and schedules

[`TriggerBuilder`](src/main/java/org/quartz/TriggerBuilder.java) creates a trigger, and a schedule builder says when it fires:

```java
// every 5 minutes, forever
TriggerBuilder.newTrigger().forJob("settlePayments", "payments")
    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withInterval(Duration.ofMinutes(5)).repeatForever())
    .build();

// once, in 10 minutes, with data for the job
TriggerBuilder.newTrigger().forJob("sendInvoice")
    .startAt(Instant.now().plus(Duration.ofMinutes(10)))
    .usingJobData("customerId", "C-1042")
    .build();

// cron: at 02:00 on the first day of every month, in a given time zone
TriggerBuilder.newTrigger().forJob("purge")
    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 2 1 * ?").inTimeZone(ZoneId.of("Europe/London")))
    .build();

// every month on the day the trigger starts, keeping the hour across daylight-saving changes
TriggerBuilder.newTrigger().forJob("billing")
    .startAt(Instant.parse("2026-11-05T09:00:00Z"))
    .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
        .withIntervalInMonths(1)
        .inTimeZone(ZoneId.of("America/New_York"))
        .preserveHourOfDayAcrossDaylightSavings(true))
    .build();

// every 15 minutes between 09:00 and 17:00, Monday to Friday
TriggerBuilder.newTrigger().forJob("syncPrices")
    .withSchedule(DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
        .onMondayThroughFriday()
        .startingDailyAt(TimeOfDay.hourAndMinuteOfDay(9, 0))
        .endingDailyAt(TimeOfDay.hourAndMinuteOfDay(17, 0))
        .withIntervalInMinutes(15))
    .build();
```

| Schedule | Trigger type | Use for |
|---|---|---|
| [`SimpleScheduleBuilder`](src/main/java/org/quartz/SimpleScheduleBuilder.java) | [`SimpleTrigger`](src/main/java/org/quartz/SimpleTrigger.java) | Once, or a fixed `Duration` apart, a number of times or forever |
| [`CronScheduleBuilder`](src/main/java/org/quartz/CronScheduleBuilder.java) | [`CronTrigger`](src/main/java/org/quartz/CronTrigger.java) | Calendar-like rules written as [`CronExpression`](src/main/java/org/quartz/CronExpression.java)s (seconds field first, `?` for "no specific value") |
| [`CalendarIntervalScheduleBuilder`](src/main/java/org/quartz/CalendarIntervalScheduleBuilder.java) | [`CalendarIntervalTrigger`](src/main/java/org/quartz/CalendarIntervalTrigger.java) | Every n days / weeks / months / years, where a fixed duration would drift |
| [`DailyTimeIntervalScheduleBuilder`](src/main/java/org/quartz/DailyTimeIntervalScheduleBuilder.java) | [`DailyTimeIntervalTrigger`](src/main/java/org/quartz/DailyTimeIntervalTrigger.java) | Repeating within a time window on chosen days of the week |

Other [`TriggerBuilder`](src/main/java/org/quartz/TriggerBuilder.java) options: [`TriggerBuilder.endAt()`](src/main/java/org/quartz/TriggerBuilder.java#L237), [`TriggerBuilder.withPriority()`](src/main/java/org/quartz/TriggerBuilder.java#L183) (higher fires first when
several are due at the same time and threads are short), [`TriggerBuilder.modifiedByCalendar()`](src/main/java/org/quartz/TriggerBuilder.java#L196) (see
[Calendars](#calendars)). [`DateBuilder`](src/main/java/org/quartz/DateBuilder.java) has helpers such as [`DateBuilder.futureDate()`](src/main/java/org/quartz/DateBuilder.java#L366) and
[`DateBuilder.evenMinuteDateAfterNow()`](src/main/java/org/quartz/DateBuilder.java#L561).

**Misfires.** A trigger that could not fire on time (the application was down, or every thread was busy) for longer
than `spring.quartz.job-store.misfire-threshold` (default `60s`) has *misfired*. Each schedule builder chooses what
happens then, for example:

```java
CronScheduleBuilder.cronSchedule("0 0/30 * * * ?").withMisfireHandlingInstructionDoNothing();       // skip, wait for the next time
CronScheduleBuilder.cronSchedule("0 0/30 * * * ?").withMisfireHandlingInstructionFireAndProceed();  // fire once now, then continue
SimpleScheduleBuilder.repeatMinutelyForever(5).withMisfireHandlingInstructionNextWithRemainingCount();
```

## Calendars

A calendar excludes times from the triggers that use it. Declare it as a bean; **the bean name is the calendar
name** the trigger refers to:

```java
@Bean
HolidayCalendar publicHolidays() {
    HolidayCalendar holidays = new HolidayCalendar(ZoneId.of("Asia/Kolkata"));
    holidays.addExcludedDate(LocalDate.of(2026, 12, 25).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant());
    return holidays;
}

@Bean
Trigger payrollTrigger() {
    return TriggerBuilder.newTrigger().forJob("payroll")
        .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(10, 0))
        .modifiedByCalendar("publicHolidays")
        .build();
}
```

| Calendar | Excludes |
|---|---|
| [`HolidayCalendar`](src/main/java/org/quartz/impl/calendar/HolidayCalendar.java) | Whole days |
| [`WeeklyCalendar`](src/main/java/org/quartz/impl/calendar/WeeklyCalendar.java) | Days of the week (Saturday and Sunday by default), [`WeeklyCalendar.setDayExcluded()`](src/main/java/org/quartz/impl/calendar/WeeklyCalendar.java#L125) with [`DateBuilder`](src/main/java/org/quartz/DateBuilder.java) day constants |
| [`MonthlyCalendar`](src/main/java/org/quartz/impl/calendar/MonthlyCalendar.java) | Days of the month |
| [`AnnualCalendar`](src/main/java/org/quartz/impl/calendar/AnnualCalendar.java) | Days of the year, every year |
| [`DailyCalendar`](src/main/java/org/quartz/impl/calendar/DailyCalendar.java) | A time range of every day, e.g. a maintenance window |
| [`CronCalendar`](src/main/java/org/quartz/impl/calendar/CronCalendar.java) | The times a cron expression matches |

Calendars can be chained: pass one calendar to another's constructor and both apply. At run time,
[`Scheduler.addCalendar()`](src/main/java/org/quartz/Scheduler.java#L706) adds or replaces one.

## Scheduling at run time

Inject [`Scheduler`](src/main/java/org/quartz/Scheduler.java) to manage jobs from your own code, for example to schedule a reminder when a user asks for it:

```java
@Service
class ReminderService {

    private final Scheduler scheduler;

    ReminderService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    void remindAt(String userId, Instant when) throws SchedulerException {
        JobDetail job = JobBuilder.newJob(ReminderJob.class)
            .withIdentity("reminder-" + userId, "reminders")
            .usingJobData("userId", userId)
            .build();
        Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity("reminder-" + userId, "reminders")
            .startAt(when)
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
            .build();
        scheduler.scheduleJob(job, trigger);
    }

    void cancel(String userId) throws SchedulerException {
        scheduler.deleteJob(JobKey.jobKey("reminder-" + userId, "reminders"));
    }
}
```

Frequently used [`Scheduler`](src/main/java/org/quartz/Scheduler.java) methods:

| Method | Does |
|---|---|
| [`Scheduler.scheduleJob()`](src/main/java/org/quartz/Scheduler.java#L344) | Stores a job with its trigger, or a trigger for a stored job |
| [`Scheduler.addJob()`](src/main/java/org/quartz/Scheduler.java#L428) | Stores a (durable) job without a trigger |
| [`Scheduler.triggerJob()`](src/main/java/org/quartz/Scheduler.java#L469) | Runs a stored job now, optionally with extra [`JobDataMap`](src/main/java/org/quartz/JobDataMap.java) entries |
| [`Scheduler.rescheduleJob()`](src/main/java/org/quartz/Scheduler.java#L412) | Replaces a trigger, e.g. to change a cron expression |
| [`Scheduler.unscheduleJob()`](src/main/java/org/quartz/Scheduler.java#L385) / [`Scheduler.deleteJob()`](src/main/java/org/quartz/Scheduler.java#L453) | Removes a trigger / a job with its triggers |
| [`Scheduler.pauseJob()`](src/main/java/org/quartz/Scheduler.java#L485), [`Scheduler.pauseTriggers()`](src/main/java/org/quartz/Scheduler.java#L537), [`Scheduler.pauseAll()`](src/main/java/org/quartz/Scheduler.java#L595) (and the `resume…` methods) | Pauses and resumes, by key or by group ([`GroupMatcher.groupEquals()`](src/main/java/org/quartz/impl/matchers/GroupMatcher.java#L44)) |
| [`Scheduler.checkExists()`](src/main/java/org/quartz/Scheduler.java#L778) | Whether a job or trigger is stored |
| [`Scheduler.getJobKeys()`](src/main/java/org/quartz/Scheduler.java#L618), [`Scheduler.getTriggersOfJob()`](src/main/java/org/quartz/Scheduler.java#L628) | Lists what is stored |
| [`Scheduler.getCurrentlyExecutingJobs()`](src/main/java/org/quartz/Scheduler.java#L301) | Executions running on this instance |
| [`Scheduler.interrupt()`](src/main/java/org/quartz/Scheduler.java#L749) | Asks a running [`InterruptableJob`](src/main/java/org/quartz/InterruptableJob.java) to stop |

> [!NOTE]
> Every change is written to the store at once and is seen by all instances of a cluster. Jobs created at run time
> survive restarts; only [`JobDetail`](src/main/java/org/quartz/JobDetail.java) / [`Trigger`](src/main/java/org/quartz/Trigger.java) beans are (re)registered on startup.

## Listeners

[`JobListener`](src/main/java/org/quartz/JobListener.java), [`TriggerListener`](src/main/java/org/quartz/TriggerListener.java) and [`SchedulerListener`](src/main/java/org/quartz/SchedulerListener.java) beans are registered automatically and see every job and
trigger. Extend the [`JobListenerSupport`](src/main/java/org/quartz/listeners/JobListenerSupport.java), [`TriggerListenerSupport`](src/main/java/org/quartz/listeners/TriggerListenerSupport.java) or [`SchedulerListenerSupport`](src/main/java/org/quartz/listeners/SchedulerListenerSupport.java) adapters to
implement only what you need:

```java
@Component
class FailedJobAlerting extends JobListenerSupport {

    private final AlertService alerts;

    FailedJobAlerting(AlertService alerts) {
        this.alerts = alerts;
    }

    @Override
    public String getName() {
        return "failedJobAlerting";
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
        if (failure != null) {
            alerts.jobFailed(context.getJobDetail().getKey(), failure);
        }
    }
}
```

A [`TriggerListener`](src/main/java/org/quartz/TriggerListener.java) can veto an execution ([`TriggerListener.vetoJobExecution()`](src/main/java/org/quartz/TriggerListener.java#L72)). To listen only to some jobs,
register the listener with matchers at run time through [`Scheduler.getListenerManager()`](src/main/java/org/quartz/Scheduler.java#L326), e.g.
[`ListenerManager.addJobListener()`](src/main/java/org/quartz/ListenerManager.java#L49) with [`GroupMatcher.jobGroupEquals()`](src/main/java/org/quartz/impl/matchers/GroupMatcher.java#L49). [`JobChainingJobListener`](src/main/java/org/quartz/listeners/JobChainingJobListener.java) runs a job after
another one completes.

## Plugins

A plugin hooks into the scheduler's lifecycle. Plugins are beans: **the bean name is the plugin name**, and the
built-in ones are off until you define them:

```java
@Bean // logs every job execution
LoggingJobHistoryPlugin jobHistory() {
    LoggingJobHistoryPlugin plugin = new LoggingJobHistoryPlugin();
    plugin.setJobFailedMessage("Job {1}.{0} failed at {2, date, HH:mm:ss}: {8}"); // optional
    return plugin;
}

@Bean // logs every trigger firing, misfire and completion
LoggingTriggerHistoryPlugin triggerHistory() {
    return new LoggingTriggerHistoryPlugin();
}

@Bean // interrupts jobs that run too long
JobInterruptMonitorPlugin jobInterruptMonitor() {
    JobInterruptMonitorPlugin plugin = new JobInterruptMonitorPlugin();
    plugin.setDefaultMaxRunTime(Duration.ofMinutes(10)); // default 5 minutes
    return plugin;
}
```

| Plugin | Does |
|---|---|
| [`LoggingJobHistoryPlugin`](src/main/java/org/quartz/plugins/history/LoggingJobHistoryPlugin.java) | Logs each job about to run, run, failed or vetoed; message templates are `MessageFormat` patterns |
| [`LoggingTriggerHistoryPlugin`](src/main/java/org/quartz/plugins/history/LoggingTriggerHistoryPlugin.java) | Logs each trigger fired, misfired and completed |
| [`JobInterruptMonitorPlugin`](src/main/java/org/quartz/plugins/interrupt/JobInterruptMonitorPlugin.java) | Interrupts [`InterruptableJob`](src/main/java/org/quartz/InterruptableJob.java)s that opt in with the job data entry [`JobInterruptMonitorPlugin.AUTO_INTERRUPTIBLE`](src/main/java/org/quartz/plugins/interrupt/JobInterruptMonitorPlugin.java#L84) = `true` and run longer than the default, or than [`JobInterruptMonitorPlugin.MAX_RUN_TIME`](src/main/java/org/quartz/plugins/interrupt/JobInterruptMonitorPlugin.java#L87) (milliseconds) in their job data |

```java
JobBuilder.newJob(ImportJob.class).withIdentity("import")
    .usingJobData(JobInterruptMonitorPlugin.AUTO_INTERRUPTIBLE, true)
    .usingJobData(JobInterruptMonitorPlugin.MAX_RUN_TIME, Duration.ofMinutes(30).toMillis())
    .build();
```

Your own [`SchedulerPlugin`](src/main/java/org/quartz/spi/SchedulerPlugin.java) implementations are registered the same way. Use `@Profile` or `@ConditionalOnProperty`
on the bean method to switch a plugin on per environment.

## Thread pool

Jobs run on a pool of `thread-count` platform threads ([`SimpleThreadPool`](src/main/java/org/quartz/simpl/SimpleThreadPool.java)) by default:

```yaml
spring.quartz.thread-pool:
  thread-count: 10        # default 10
  thread-name-prefix: jobs
```

**Virtual threads.** Switch them on for the scheduler, and each job runs on a new virtual thread
([`VirtualThreadPool`](src/main/java/org/quartz/simpl/VirtualThreadPool.java)), at most `thread-count` at a time:

```yaml
spring.quartz.thread-pool:
  virtual-threads: true
  thread-count: 50        # the maximum number of jobs running at once
```

Virtual threads suit jobs that mostly wait on I/O (HTTP calls, mail, databases). Size `thread-count` to what the
systems your jobs call can take (connection pools, rate limits), not to CPU cores. The switch is independent of
Spring Boot's `spring.threads.virtual.enabled`, which does not change the job threads.

**Your own executor.** Run jobs on a Spring `TaskExecutor` with a customizer:

```java
@Bean
SchedulerFactoryBeanCustomizer quartzExecutor(ThreadPoolTaskExecutor jobsExecutor) {
    return (schedulerFactoryBean) -> schedulerFactoryBean.setTaskExecutor(jobsExecutor);
}
```

The scheduler only takes triggers when the executor has room to run them, so they never wait in its queue (where no
other instance could run them) or get rejected. Its capacity is read from the executor ([`LocalTaskExecutorThreadPool`](src/main/java/org/quartz/spring/LocalTaskExecutorThreadPool.java)):
the core pool size of a `ThreadPoolTaskExecutor` / `ThreadPoolExecutor` (the maximum pool size if it has no queue),
or the concurrency limit of a `SimpleAsyncTaskExecutor`. An executor with no limit runs at most `thread-count` jobs at
a time; when the limit cannot be read at all, a warning says so. To set the capacity yourself:
`schedulerFactoryBean.setThreadPool(new LocalTaskExecutorThreadPool(executor, capacity))`.

The startup log names the pool in use and its capacity. With `spring.quartz.wait-for-jobs-to-complete-on-shutdown:
true`, shutdown waits for running jobs.

## Job stores

The store is chosen by `spring.quartz.job-store.type` (`mongodb` or `redis`, see [`JobStoreType`](src/main/java/org/quartz/spring/boot/autoconfigure/JobStoreType.java)). Leave it unset when
the application has only one of a `MongoClient` / `RedisConnectionFactory`: that store is used. Startup fails with
an explanation ([`JobStoreUnavailableException`](src/main/java/org/quartz/spring/boot/autoconfigure/JobStoreUnavailableException.java)) when there is neither, when there are both and no type is set, or
when the configured store's connection is missing.

Connection settings are always Spring Boot's own: `spring.mongodb.*` for MongoDB and `spring.data.redis.*` for
Redis. The scheduler uses the same client as the rest of the application.

### MongoDB

```yaml
spring:
  mongodb:
    uri: mongodb://db1,db2,db3/app?replicaSet=rs0
  quartz:
    job-store:
      type: mongodb
      mongodb:
        collection-prefix: qrtz_   # default
```

* Collections `qrtz_jobs`, `qrtz_triggers`, `qrtz_calendars`, `qrtz_paused_groups`, `qrtz_fired_triggers`,
  `qrtz_schedulers`, `qrtz_locks`, created with their indexes on startup.
* Database: the application's, i.e. the `MongoDatabaseFactory`'s (Spring Data MongoDB), otherwise
  `spring.mongodb.database`, otherwise the database of `spring.mongodb.uri`. An application with its own
  `MongoClient` and without Spring Data MongoDB defines a `MongoDatabaseFactory` bean to name the database.
* **Use a replica set** (a single-node replica set is enough) or a sharded cluster: every store operation then runs
  in a transaction, all or nothing, even when an instance crashes halfway. The store also works on a standalone
  server, where each single write is atomic; the startup log says which mode is used.
* Tested with MongoDB 8.0. If you build your own `MongoClient`, keep the driver's default codec registry.

### Redis

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
  quartz:
    job-store:
      type: redis
      redis:
        key-prefix: "quartz:"     # default
```

* Keys are `quartz:{schedulerName}:…`. All keys of a scheduler share one hash slot, so **Redis Cluster** works.
* Every multi-key change is a single Lua script, so it is atomic.
* Tested with Redis 7.4.

> [!IMPORTANT]
> The schedule is only as durable as Redis. Run it with **AOF persistence** (`appendonly yes`, `appendfsync
> everysec`): with RDB snapshots only, every change since the last snapshot (new jobs, completed one-shot triggers,
> paused groups) is lost when Redis restarts.

## Clustering

Run the same application on several instances against the same store:

```yaml
spring:
  quartz:
    scheduler:
      name: app              # the same on every instance
      instance-id: AUTO      # unique per instance: host name + timestamp
    job-store:
      clustered: true
      cluster-checkin-interval: 7500ms
      cluster-checkin-misfire-threshold: 7500ms
```

* Each firing of a trigger runs on exactly one instance; instances with free threads take the next due triggers.
* Every instance checks in every `cluster-checkin-interval`. An instance silent for longer than
  `cluster-checkin-interval` + `cluster-checkin-misfire-threshold` is considered failed: its triggers are freed, and
  the jobs it was running that were built with [`JobBuilder.requestRecovery()`](src/main/java/org/quartz/JobBuilder.java#L202) are run again elsewhere.
* A lock held by a crashed instance is released after `spring.quartz.job-store.{mongodb,redis}.lock-timeout`.
* `instance-id: SYS_PROP` reads the id from the `org.quartz.scheduler.instanceId` system property instead; an
  [`InstanceIdGenerator`](src/main/java/org/quartz/spi/InstanceIdGenerator.java) bean generates it in code.
* Keep the instances' clocks in sync (NTP).

To spread acquisition work under high load, let each instance take several due triggers at a time:

```yaml
spring.quartz.scheduler:
  batch-trigger-acquisition-max-count: 10
  batch-trigger-acquisition-fire-ahead-time-window: 1s
```

## Transactions

A job class annotated with [`@ExecuteInTransaction`](src/main/java/org/quartz/ExecuteInTransaction.java) runs in a Spring-managed transaction:

```java
@ExecuteInTransaction(transactionManager = "ordersTransactionManager", timeout = 30)
public class SettleOrdersJob implements Job {

    @Autowired
    private OrderRepository orders;

    @Override
    public void execute(JobExecutionContext context) {
        orders.settleOpenOrders(); // rolls back if the job throws
    }
}
```

Attributes as on `@Transactional`: `transactionManager` (bean name; empty for the unique or `@Primary` one),
`propagation`, `isolation`, `timeout` (seconds), `readOnly`, `rollbackFor`. A [`JobExecutionException`](src/main/java/org/quartz/JobExecutionException.java), runtime
exceptions and errors roll back. A vetoed execution opens no transaction, and the transaction completes before the
scheduler records the job as finished. To run **every** job in a transaction:

```yaml
spring.quartz.transaction:
  enabled: true                # default false: only annotated jobs
  default-propagation: REQUIRED
```

The job's transaction and the scheduler's own writes are independent, with one exception: with the MongoDB store
and a `MongoTransactionManager` on the same `MongoClient`, the job data of a [`@PersistJobDataAfterExecution`](src/main/java/org/quartz/PersistJobDataAfterExecution.java) job is
saved in the job's transaction (and discarded if it rolls back).

## Logging

While a job runs, the SLF4J MDC holds:

| MDC key | Value |
|---|---|
| `quartz.jobKey` ([`JobRunShell.MDC_JOB_KEY`](src/main/java/org/quartz/core/JobRunShell.java#L63)) | The job, as `group.name` |
| `quartz.triggerKey` ([`JobRunShell.MDC_TRIGGER_KEY`](src/main/java/org/quartz/core/JobRunShell.java#L66)) | The trigger, as `group.name` |
| `quartz.fireInstanceId` ([`JobRunShell.MDC_FIRE_INSTANCE_ID`](src/main/java/org/quartz/core/JobRunShell.java#L69)) | Unique to this firing |

Everything logged during the execution, the job and the listeners included, can be traced back to it. Add the keys
to your log pattern:

```yaml
logging.pattern.level: "%5p [%X{quartz.jobKey:-}] [%X{quartz.fireInstanceId:-}]"
```

JSON encoders that write the whole MDC pick them up without configuration. Threads a job starts itself do not
inherit them (the MDC is per thread): pass them on if those threads log.

## Actuator

With `spring-boot-starter-actuator` on the classpath:

**Endpoint `/actuator/quartz`** ([`QuartzEndpoint`](src/main/java/org/quartz/spring/boot/actuate/endpoint/QuartzEndpoint.java), [`QuartzEndpointWebExtension`](src/main/java/org/quartz/spring/boot/actuate/endpoint/QuartzEndpointWebExtension.java)), exposed with:

```yaml
management.endpoints.web.exposure.include: health,quartz
```

| Request | Returns |
|---|---|
| `GET /actuator/quartz` | Job and trigger group names |
| `GET /actuator/quartz/jobs`, `/triggers` | Groups with their jobs / triggers |
| `GET /actuator/quartz/jobs/{group}`, `/triggers/{group}` | The jobs / triggers of a group |
| `GET /actuator/quartz/jobs/{group}/{name}`, `/triggers/{group}/{name}` | Details: class, data, triggers, next fire times |
| `POST /actuator/quartz/jobs/{group}/{name}` with `{"state":"running"}` | Runs the job now |

Job data values are masked unless `management.endpoint.quartz.show-values` allows them (`roles` restricts who).

**Health indicator `quartz`** ([`QuartzHealthIndicator`](src/main/java/org/quartz/spring/boot/actuate/health/QuartzHealthIndicator.java)): `DOWN` when the scheduler is shut down, its store cannot be
read, or (clustered) its own check-in is overdue; `UP` otherwise, standby included.
`management.health.quartz.enabled: false` turns it off.

**Metrics** ([`QuartzMetrics`](src/main/java/org/quartz/spring/boot/actuate/metrics/QuartzMetrics.java), with a `MeterRegistry`), tagged with `scheduler` and the job or trigger group:

| Meter | Type |
|---|---|
| `quartz.jobs.executed` | Counter |
| `quartz.jobs.failed` | Counter |
| `quartz.jobs.duration` | Timer |
| `quartz.triggers.misfired` | Counter |

`management.metrics.quartz.enabled: false` turns them off.

## Customizations

* **[`SchedulerFactoryBeanCustomizer`](src/main/java/org/quartz/spring/boot/autoconfigure/SchedulerFactoryBeanCustomizer.java)** beans adjust the [`SchedulerFactoryBean`](src/main/java/org/quartz/spring/SchedulerFactoryBean.java) before the scheduler is created:
  executor, thread pool, extra listeners, [`SchedulerFactoryBean.setStartupDelay()`](src/main/java/org/quartz/spring/SchedulerFactoryBean.java#L352), …
* **`Clock`** bean: the scheduler's notion of time (useful in tests).
* **[`InstanceIdGenerator`](src/main/java/org/quartz/spi/InstanceIdGenerator.java)** bean: how a clustered instance names itself.
* **Your own scheduler**: define a [`SchedulerFactoryBean`](src/main/java/org/quartz/spring/SchedulerFactoryBean.java) (or [`Scheduler`](src/main/java/org/quartz/Scheduler.java)) bean and the auto-configuration steps
  back. Give it a store with [`SchedulerFactoryBean.setJobStore()`](src/main/java/org/quartz/spring/SchedulerFactoryBean.java#L191), e.g. `new MongoJobStore(…)` or
  `new RedisJobStore(…)`.

## Configuration reference

All properties, as in the configuration metadata your IDE completes them from
([`additional-spring-configuration-metadata.json`](src/main/resources/META-INF/additional-spring-configuration-metadata.json)
plus the metadata generated from [`QuartzProperties`](src/main/java/org/quartz/spring/boot/autoconfigure/QuartzProperties.java)). Durations accept `500ms`, `30s`, `10m` or ISO-8601 (`PT30S`).

| Property | Type | Default | Description |
|---|---|---|---|
| `management.endpoint.quartz.access` | Access | `unrestricted` | Permitted level of access for the quartz endpoint. |
| `management.endpoint.quartz.cache.time-to-live` | Duration | `0ms` | Maximum time that a response can be cached. |
| `management.endpoint.quartz.roles` | Set |  | Roles used to determine whether a user is authorized to be shown unsanitized job or trigger values. When empty, all authenticated users are authorized. |
| `management.endpoint.quartz.show-values` | Show | `never` | When to show unsanitized job or trigger values. |
| `spring.quartz.auto-startup` | Boolean | `true` | Whether to automatically start the scheduler after initialization. |
| `spring.quartz.job-store.cluster-checkin-interval` | Duration | `7500ms` | How often a clustered instance checks in with the others. |
| `spring.quartz.job-store.cluster-checkin-misfire-threshold` | Duration | `7500ms` | Grace period after a missed check-in before an instance is considered failed and its work is recovered. |
| `spring.quartz.job-store.clustered` | Boolean | `false` | Whether several scheduler instances share the store. |
| `spring.quartz.job-store.max-misfires-to-handle-at-a-time` | Integer | `20` | Maximum number of misfired triggers handled per pass. |
| `spring.quartz.job-store.misfire-threshold` | Duration | `60s` | How late a trigger may fire before it counts as misfired. |
| `spring.quartz.job-store.mongodb.collection-prefix` | String | `qrtz_` | Prefix of the collections the store uses. |
| `spring.quartz.job-store.mongodb.lock-timeout` | Duration | `10m` | How long a lock is held before it is considered abandoned. |
| `spring.quartz.job-store.redis.key-prefix` | String | `quartz:` | Prefix of the keys the store uses. |
| `spring.quartz.job-store.redis.lock-timeout` | Duration | `30s` | How long a lock is held before it is considered abandoned. |
| `spring.quartz.job-store.retry-interval` | Duration | `15s` | Delay before retrying after the store could not be reached. |
| `spring.quartz.job-store.type` | JobStoreType |  | Job store type; when unset, the store whose connection bean is present. |
| `spring.quartz.overwrite-existing-jobs` | Boolean | `false` | Whether configured jobs should overwrite existing job definitions. |
| `spring.quartz.scheduler.batch-trigger-acquisition-fire-ahead-time-window` | Duration | `0s` | How far ahead of their fire time triggers may be acquired. |
| `spring.quartz.scheduler.batch-trigger-acquisition-max-count` | Integer | `1` | Maximum number of triggers acquired at once. |
| `spring.quartz.scheduler.context` | Map |  | Initial entries of the scheduler context. |
| `spring.quartz.scheduler.idle-wait-time` | Duration | `30s` | How long the scheduler thread waits for new triggers when idle; at least 1s. |
| `spring.quartz.scheduler.instance-id` | String | `NON_CLUSTERED` | Instance id of this node; "AUTO" generates one and "SYS_PROP" reads the "org.quartz.scheduler.instanceId" system property (both only when the job store is clustered). |
| `spring.quartz.scheduler.interrupt-jobs-on-shutdown` | Boolean | `false` | Whether to interrupt running interruptable jobs on shutdown without waiting. |
| `spring.quartz.scheduler.interrupt-jobs-on-shutdown-with-wait` | Boolean | `false` | Whether to interrupt running interruptable jobs on shutdown with waiting. |
| `spring.quartz.scheduler.name` | String | `quartzScheduler` | Name of the scheduler, shared by all nodes of a cluster. |
| `spring.quartz.scheduler.thread-name` | String |  | Name of the scheduler thread; defaults to the scheduler name followed by "_QuartzSchedulerThread". |
| `spring.quartz.startup-delay` | Duration | `0s` | Delay after which the scheduler is started once initialization completes. Setting this property makes sense if no jobs should be run before the entire application has started up. |
| `spring.quartz.thread-pool.thread-count` | Integer | `10` | Number of worker threads, or, with virtual threads, the maximum number of concurrently running jobs. |
| `spring.quartz.thread-pool.thread-name-prefix` | String |  | Prefix of the worker thread names; defaults to the scheduler name followed by "_Worker". |
| `spring.quartz.thread-pool.virtual-threads` | Boolean | `false` | Whether to run each job on a new virtual thread, at most thread-count at a time, instead of a pool of platform threads; independent of "spring.threads.virtual.enabled". |
| `spring.quartz.transaction.default-propagation` | Propagation | `REQUIRED` | Propagation of the transactions of jobs that are not annotated, when enabled. |
| `spring.quartz.transaction.enabled` | Boolean | `false` | Whether every job runs in a transaction of the unique or primary transaction manager, not only annotated jobs. |
| `spring.quartz.wait-for-jobs-to-complete-on-shutdown` | Boolean | `false` | Whether to wait for running jobs to complete on shutdown. |

> [!WARNING]
> Removed properties (`spring.quartz.plugins.*`, `spring.quartz.job-store.mongodb.database`, thread priority and
> daemon flags, store-internal locking options) **fail startup** with a list of what to use instead
> ([`RemovedPropertiesException`](src/main/java/org/quartz/spring/boot/autoconfigure/RemovedPropertiesException.java)), rather than being silently ignored. Your IDE flags them as errors too.

## Testing your application

Test against a real database with Testcontainers and `@ServiceConnection`; the scheduler then uses the container
like the rest of the application:

```java
@SpringBootTest
@Testcontainers
class DailyReportJobIT {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0"); // a single-node replica set

    @Autowired
    Scheduler scheduler;

    @Test
    void runsTheReportOnDemand() throws Exception {
        scheduler.triggerJob(JobKey.jobKey("dailyReport", "reports"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(reportService).sendDailyReport(any()));
    }

    @MockitoBean
    ReportService reportService;
}
```

For Redis use `@ServiceConnection(name = "redis") static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);`.

Tips:
* `spring.quartz.auto-startup: false` keeps the scheduler in standby, so only what a test triggers runs; call
  [`Scheduler.start()`](src/main/java/org/quartz/Scheduler.java#L203) when needed.
* A `Clock` bean controls the time the scheduler sees.
* Await results (Awaitility) instead of sleeping: jobs run on the scheduler's threads.

## Coming from Quartz

The `org.quartz` packages and the builder API are the same; types moved to `java.time`:

| Before | Now | For example |
|---|---|---|
| `java.util.Date` | `java.time.Instant` | [`TriggerBuilder.startAt()`](src/main/java/org/quartz/TriggerBuilder.java#L211), [`Trigger.getNextFireTime()`](src/main/java/org/quartz/Trigger.java#L210), [`JobExecutionContext.getFireTime()`](src/main/java/org/quartz/JobExecutionContext.java#L128), [`CronExpression.getNextValidTimeAfter()`](src/main/java/org/quartz/CronExpression.java#L335), [`DateBuilder`](src/main/java/org/quartz/DateBuilder.java) |
| `long` milliseconds | `java.time.Duration` | [`SimpleScheduleBuilder.withInterval()`](src/main/java/org/quartz/SimpleScheduleBuilder.java#L275), [`SimpleTrigger.getRepeatInterval()`](src/main/java/org/quartz/SimpleTrigger.java#L117), [`JobExecutionContext.getJobRunTime()`](src/main/java/org/quartz/JobExecutionContext.java#L191) |
| `java.util.TimeZone` | `java.time.ZoneId` | [`CronScheduleBuilder.inTimeZone()`](src/main/java/org/quartz/CronScheduleBuilder.java#L258), [`CalendarIntervalScheduleBuilder.inTimeZone()`](src/main/java/org/quartz/CalendarIntervalScheduleBuilder.java#L271), [`BaseCalendar`](src/main/java/org/quartz/impl/calendar/BaseCalendar.java) |
| `java.util.Calendar` | `java.time.ZonedDateTime` | [`AnnualCalendar.setDayExcluded()`](src/main/java/org/quartz/impl/calendar/AnnualCalendar.java#L150), [`DailyCalendar`](src/main/java/org/quartz/impl/calendar/DailyCalendar.java) |
| `@ExecuteInJTATransaction` | [`@ExecuteInTransaction`](src/main/java/org/quartz/ExecuteInTransaction.java) | see [Transactions](#transactions) |
| `TimeBroker` | `java.time.Clock` bean | |

Configuration moves from `quartz.properties` to `spring.quartz.*`, for example:

| `quartz.properties` / `spring-boot-starter-quartz` | Now |
|---|---|
| `org.quartz.scheduler.instanceName`, `spring.quartz.scheduler-name` | `spring.quartz.scheduler.name` |
| `org.quartz.scheduler.instanceId` | `spring.quartz.scheduler.instance-id` |
| `org.quartz.threadPool.threadCount` | `spring.quartz.thread-pool.thread-count` |
| `org.quartz.jobStore.misfireThreshold=60000` | `spring.quartz.job-store.misfire-threshold: 60s` |
| `org.quartz.jobStore.isClustered` | `spring.quartz.job-store.clustered` |
| `spring.quartz.job-store-type` | `spring.quartz.job-store.type` |
| `org.quartz.plugin.*` | plugin beans, see [Plugins](#plugins) |

Not available: the JDBC store and `RAMJobStore` (use MongoDB or Redis; existing jobs are re-created from your
beans or code), JTA, RMI, JMX (use the [actuator](#actuator)), JNDI, servlet integration, the XML job definitions
plugin and `DirectSchedulerFactory`.

## Building from source

Docker must be running: the integration tests start MongoDB (`mongo:8.0`) and Redis (`redis:7.4`) containers.

```bash
./mvnw verify
```

`verify` runs the unit tests (`*Test`) and the container tests (`*IT`), including [`SampleApplicationIT`](src/test/java/org/quartz/spring/boot/sample/SampleApplicationIT.java), a sample
application run on MongoDB and Redis, single and clustered. Use JDK 25. How the library works inside is described in
the [Developer guide](DEVELOPER_GUIDE.md); design decisions are recorded in [DECISIONS.md](DECISIONS.md).

## License

Apache License 2.0, see [LICENSE.txt](LICENSE.txt) and [NOTICE](NOTICE). Derived from
[Quartz Scheduler](https://github.com/quartz-scheduler/quartz) and from Spring Framework / Spring Boot's Quartz
support, all Apache 2.0.

