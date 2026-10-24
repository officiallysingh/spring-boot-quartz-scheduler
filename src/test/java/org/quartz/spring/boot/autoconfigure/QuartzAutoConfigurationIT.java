/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.quartz.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Calendar;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerListener;
import org.quartz.SchedulerMetaData;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.impl.matchers.KeyMatcher;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.listeners.SchedulerListenerSupport;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.plugins.history.LoggingJobHistoryPlugin;
import org.quartz.plugins.history.LoggingTriggerHistoryPlugin;
import org.quartz.plugins.interrupt.JobInterruptMonitorPlugin;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.simpl.VirtualThreadPool;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spring.SchedulerFactoryBean;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.AbstractRedisIT;
import org.quartz.support.RecordingTransactionManager;
import org.quartz.support.TestSchedulers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link QuartzAutoConfiguration} on the shared MongoDB container. The runner gets {@code
 * ConnectionDetails} beans for the shared containers, as {@code @ServiceConnection} registers them,
 * but without handing the containers to the context, which would stop them when it closes (D-016).
 * Checks that need no store are in {@link QuartzAutoConfigurationTest}.
 */
class QuartzAutoConfigurationIT extends AbstractMongoIT {

  static final List<String> RESULTS = new CopyOnWriteArrayList<>();

  // no job-store type: inferred from the MongoClient bean
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MongoAutoConfiguration.class,
                  DataMongoAutoConfiguration.class,
                  QuartzAutoConfiguration.class))
          .withUserConfiguration(MongoContainerConfiguration.class);

  @BeforeEach
  void clearResults() {
    RESULTS.clear();
  }

  @Test
  void schedulerIsCreatedWithDefaults() {
    contextRunner.run(
        context -> {
          assertThat(context)
              .hasSingleBean(Scheduler.class)
              .hasSingleBean(SchedulerFactoryBean.class)
              .hasSingleBean(QuartzProperties.class);
          Scheduler scheduler = context.getBean(Scheduler.class);
          assertThat(scheduler.getSchedulerName()).isEqualTo("quartzScheduler");
          assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("NON_CLUSTERED");
          assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(10);
          assertThat(scheduler.getMetaData().getJobStoreClass()).isEqualTo(MongoJobStore.class);
          assertThat(scheduler.isStarted()).isTrue();
        });
  }

  @Test
  void propertiesAreApplied() {
    contextRunner
        .withPropertyValues(
            "spring.quartz.scheduler.name=app-scheduler",
            "spring.quartz.scheduler.instance-id=node-1",
            "spring.quartz.scheduler.idle-wait-time=45s",
            "spring.quartz.scheduler.context.region=eu",
            "spring.quartz.thread-pool.thread-count=3",
            "spring.quartz.job-store.misfire-threshold=90s",
            "spring.quartz.auto-startup=false")
        .run(
            context -> {
              Scheduler scheduler = context.getBean(Scheduler.class);
              assertThat(scheduler.getSchedulerName()).isEqualTo("app-scheduler");
              assertThat(scheduler.getSchedulerInstanceId()).isEqualTo("node-1");
              assertThat(scheduler.getContext().get("region")).isEqualTo("eu");
              assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(3);
              assertThat(scheduler.isStarted()).isFalse();
              QuartzProperties properties = context.getBean(QuartzProperties.class);
              assertThat(properties.scheduler().idleWaitTime()).isEqualTo(Duration.ofSeconds(45));
              assertThat(properties.jobStore().misfireThreshold())
                  .isEqualTo(Duration.ofSeconds(90));
            });
  }

  @Test
  void startupDelayIsAppliedAsDuration() {
    contextRunner
        .withPropertyValues("spring.quartz.startup-delay=1s")
        .run(
            context -> {
              Scheduler scheduler = context.getBean(Scheduler.class);
              assertThat(scheduler.isStarted()).isFalse();
              await().atMost(Duration.ofSeconds(10)).until(scheduler::isStarted);
            });
  }

  @Test
  void transactionSwitchWithoutTransactionManagerFailsFast() {
    contextRunner
        .withPropertyValues("spring.quartz.transaction.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("PlatformTransactionManager"));
  }

  @Test
  void transactionSwitchRunsEveryJobInATransaction() {
    contextRunner
        .withUserConfiguration(TransactionalJobConfiguration.class)
        .withPropertyValues(
            "spring.quartz.transaction.enabled=true",
            "spring.quartz.transaction.default-propagation=requires-new")
        .run(
            context -> {
              RecordingTransactionManager transactionManager =
                  context.getBean(RecordingTransactionManager.class);
              await()
                  .atMost(Duration.ofSeconds(10))
                  .until(() -> transactionManager.events().contains("commit DEFAULT.plainJob"));
              assertThat(RESULTS).containsExactly("in transaction: true");
              assertThat(transactionManager.definitions().get(0).getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            });
  }

  @Test
  void withoutTheSwitchPlainJobsRunWithoutATransaction() {
    contextRunner
        .withUserConfiguration(TransactionalJobConfiguration.class)
        .run(
            context -> {
              await().atMost(Duration.ofSeconds(10)).until(() -> !RESULTS.isEmpty());
              assertThat(RESULTS).containsExactly("in transaction: false");
              assertThat(context.getBean(RecordingTransactionManager.class).events()).isEmpty();
            });
  }

  @Test
  void invalidSettingsFailFast() {
    contextRunner
        .withPropertyValues("spring.quartz.scheduler.idle-wait-time=10ms")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("idleWaitTime"));
  }

  @Test
  void customizersAreAppliedInOrder() {
    contextRunner
        .withUserConfiguration(CustomizerConfiguration.class)
        .run(
            context -> {
              assertThat(context.getBean(Scheduler.class).getSchedulerName()).isEqualTo("second");
              assertThat(RESULTS).containsExactly("first", "second");
            });
  }

  @Test
  void quartzBeansAreRegistered() {
    // not started, so the one-shot trigger is still there to look up
    contextRunner
        .withUserConfiguration(QuartzBeansConfiguration.class)
        .withPropertyValues("spring.quartz.auto-startup=false")
        .run(
            context -> {
              Scheduler scheduler = context.getBean(Scheduler.class);
              assertThat(scheduler.getJobDetail(JobKey.jobKey("greetingJob"))).isNotNull();
              assertThat(scheduler.getTrigger(TriggerKey.triggerKey("greetingTrigger")))
                  .isNotNull();
              assertThat(scheduler.getCalendar("weekdays")).isInstanceOf(WeeklyCalendar.class);
              assertThat(scheduler.getListenerManager().getJobListener("jobListener")).isNotNull();
              assertThat(scheduler.getListenerManager().getTriggerListener("triggerListener"))
                  .isNotNull();
              assertThat(scheduler.getListenerManager().getSchedulerListeners())
                  .contains(context.getBean(SchedulerListener.class));
              assertThat(RESULTS).contains("plugin myPlugin initialized");
            });
  }

  @Test
  void jobsAreAutowiredBySpringBeanJobFactory() {
    contextRunner
        .withUserConfiguration(QuartzBeansConfiguration.class)
        .run(
            context ->
                await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> RESULTS.stream().anyMatch(r -> r.startsWith("hello quartz"))));
  }

  @Test
  void virtualThreadsRunTheJobs() {
    contextRunner
        .withUserConfiguration(QuartzBeansConfiguration.class)
        .withPropertyValues(
            "spring.quartz.thread-pool.virtual-threads=true",
            "spring.quartz.thread-pool.thread-count=3")
        .run(
            context -> {
              SchedulerMetaData metaData = context.getBean(Scheduler.class).getMetaData();
              assertThat(metaData.getThreadPoolClass()).isEqualTo(VirtualThreadPool.class);
              assertThat(metaData.getThreadPoolSize()).isEqualTo(3);
              assertThat(metaData.getSummary())
                  .contains(
                      "Using thread pool 'org.quartz.simpl.VirtualThreadPool' - virtual threads,"
                          + " at most 3 concurrent jobs.");
              await()
                  .atMost(Duration.ofSeconds(10))
                  .until(() -> RESULTS.stream().anyMatch(r -> r.startsWith("hello quartz")));
              assertThat(RESULTS).contains("hello quartz on virtual thread");
            });
  }

  @Test
  void platformThreadPoolIsTheDefault() {
    contextRunner.run(
        context -> {
          SchedulerMetaData metaData = context.getBean(Scheduler.class).getMetaData();
          assertThat(metaData.getThreadPoolClass()).isEqualTo(SimpleThreadPool.class);
          assertThat(metaData.getThreadPoolSize()).isEqualTo(10);
          assertThat(metaData.getSummary())
              .contains("Using thread pool 'org.quartz.simpl.SimpleThreadPool' - with 10 threads.");
        });
  }

  @Test
  void springVirtualThreadsSettingDoesNotSwitchTheJobThreads() {
    contextRunner
        .withPropertyValues("spring.threads.virtual.enabled=true")
        .run(
            context ->
                assertThat(context.getBean(Scheduler.class).getMetaData().getThreadPoolClass())
                    .isEqualTo(SimpleThreadPool.class));
  }

  @Test
  void lifecycleFollowsTheContext() {
    contextRunner.run(
        context -> {
          Scheduler scheduler = context.getBean(Scheduler.class);
          assertThat(scheduler.isStarted()).isTrue();
          context.stop();
          assertThat(scheduler.isInStandbyMode()).isTrue();
          context.start();
          assertThat(scheduler.isInStandbyMode()).isFalse();
          context.close();
          assertThat(scheduler.isShutdown()).isTrue();
        });
  }

  @Test
  void backsOffWhenTheApplicationDefinesASchedulerFactoryBean() {
    contextRunner
        .withUserConfiguration(OwnSchedulerFactoryBeanConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(SchedulerFactoryBean.class);
              assertThat(context.getBean(Scheduler.class).getSchedulerName()).isEqualTo("own");
            });
  }

  @Test
  void mongoStoreUsesTheDatabaseOfSpringDataMongo() {
    contextRunner
        .withPropertyValues("spring.mongodb.database=appdb")
        .run(context -> assertThat(mongoDatabase(context)).isEqualTo("appdb"));
  }

  @Test
  void mongoStoreUsesSpringMongodbDatabaseWithoutSpringData() {
    withoutSpringData()
        .withPropertyValues("spring.mongodb.database=jobsdb")
        .run(context -> assertThat(mongoDatabase(context)).isEqualTo("jobsdb"));
  }

  @Test
  void mongoStoreUsesTheDatabaseOfTheConnectionStringWithoutSpringData() {
    withoutSpringData()
        .run(
            context ->
                assertThat(mongoDatabase(context))
                    .isEqualTo(
                        new ConnectionString(AbstractMongoIT.connectionString()).getDatabase()));
  }

  private static ApplicationContextRunner withoutSpringData() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(MongoAutoConfiguration.class, QuartzAutoConfiguration.class))
        .withUserConfiguration(MongoContainerConfiguration.class);
  }

  private static String mongoDatabase(AssertableApplicationContext context) {
    assertThat(context).hasNotFailed();
    return ((MongoJobStore) context.getBean(SchedulerFactoryBean.class).getJobStore())
        .getDatabaseName();
  }

  @Test
  void removedPropertiesFailStartup() {
    contextRunner
        .withPropertyValues(
            "spring.quartz.job-store.mongodb.database=quartz",
            "spring.quartz.plugins.job-history.enabled=true",
            "spring.quartz.thread-pool.thread-priority=7")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable root =
                  NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure());
              assertThat(root).isInstanceOf(RemovedPropertiesException.class);
              RemovedPropertiesException failure = (RemovedPropertiesException) root;
              assertThat(failure.getProperties())
                  .containsExactly(
                      "spring.quartz.job-store.mongodb.database",
                      "spring.quartz.plugins",
                      "spring.quartz.thread-pool.thread-priority");
            });
  }

  @Test
  void mongoStoreWorksWithoutSpringBootsMongoModuleGivenADatabaseFactory() {
    new ApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader(MongoProperties.class))
        .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
        .withBean(
            MongoClient.class,
            AbstractMongoIT::mongoClient,
            // the shared client: the context must not close it
            (definition) -> definition.setDestroyMethodName(""))
        .withBean(
            MongoDatabaseFactory.class,
            () ->
                new SimpleMongoClientDatabaseFactory(
                    AbstractMongoIT.connectionString().replaceFirst("/[^/?]*(\\?|$)", "/owndb$1")))
        .run(context -> assertThat(mongoDatabase(context)).isEqualTo("owndb"));
  }

  @Test
  void mongoStoreWithoutADatabaseFailsWithTheProperties() {
    new ApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader(MongoProperties.class))
        .withConfiguration(AutoConfigurations.of(QuartzAutoConfiguration.class))
        .withBean(
            MongoClient.class,
            AbstractMongoIT::mongoClient,
            // the shared client: the context must not close it
            (definition) -> definition.setDestroyMethodName(""))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("spring.mongodb.database")
                    .hasMessageContaining("MongoDatabaseFactory"));
  }

  @Test
  void pluginsThatAreListenersKeepTheirOwnMatchers() {
    contextRunner
        .withBean("selective", SchedulerPlugin.class, SelectiveListenerPlugin::new)
        .run(
            context -> {
              Scheduler scheduler = context.getBean(Scheduler.class);
              assertThat(scheduler.getListenerManager().getJobListenerMatchers("selective"))
                  .containsExactly(KeyMatcher.keyEquals(JobKey.jobKey("only-this")));
            });
  }

  /** A plugin that is also a job listener, named and registered in initialize. */
  static class SelectiveListenerPlugin implements SchedulerPlugin, JobListener {

    private String name = "";

    @Override
    public void initialize(String name, Scheduler scheduler) throws SchedulerException {
      this.name = name;
      scheduler
          .getListenerManager()
          .addJobListener(this, KeyMatcher.keyEquals(JobKey.jobKey("only-this")));
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}

    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {}

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {}

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {}
  }

  @Test
  void noPluginIsRegisteredByDefault() {
    contextRunner.run(
        context -> {
          Scheduler scheduler = context.getBean(Scheduler.class);
          assertThat(scheduler.getListenerManager().getJobListener("jobHistory")).isNull();
          assertThat(scheduler.getListenerManager().getTriggerListener("jobInterruptMonitor"))
              .isNull();
        });
  }

  @Test
  void builtInPluginsAreDefinedAsBeans() {
    contextRunner
        .withUserConfiguration(BuiltInPluginsConfiguration.class)
        .run(
            context -> {
              Scheduler scheduler = context.getBean(Scheduler.class);
              assertThat(scheduler.getListenerManager().getJobListener("jobHistory"))
                  .isSameAs(context.getBean("jobHistory"));
              assertThat(scheduler.getListenerManager().getTriggerListener("triggerHistory"))
                  .isSameAs(context.getBean("triggerHistory"));
              assertThat(scheduler.getListenerManager().getTriggerListener("jobInterruptMonitor"))
                  .isSameAs(context.getBean("jobInterruptMonitor"));
              assertThat(
                      context
                          .getBean("jobInterruptMonitor", JobInterruptMonitorPlugin.class)
                          .getDefaultMaxRunTime())
                  .isEqualTo(Duration.ofMinutes(10));
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class BuiltInPluginsConfiguration {

    @Bean
    LoggingJobHistoryPlugin jobHistory() {
      LoggingJobHistoryPlugin plugin = new LoggingJobHistoryPlugin();
      plugin.setJobFailedMessage("Job {1}.{0} failed: {8}");
      return plugin;
    }

    @Bean
    LoggingTriggerHistoryPlugin triggerHistory() {
      return new LoggingTriggerHistoryPlugin();
    }

    @Bean
    JobInterruptMonitorPlugin jobInterruptMonitor() {
      JobInterruptMonitorPlugin plugin = new JobInterruptMonitorPlugin();
      plugin.setDefaultMaxRunTime(Duration.ofMinutes(10));
      return plugin;
    }
  }

  @Test
  void anExplicitTypeChoosesAmongSeveralStores() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
        .withUserConfiguration(RedisContainerConfiguration.class)
        .withPropertyValues("spring.quartz.job-store.type=redis")
        .run(
            context ->
                assertThat(context.getBean(Scheduler.class).getMetaData().getJobStoreClass())
                    .isEqualTo(RedisJobStore.class));
    AbstractRedisIT.flushDatabase();
  }

  @Test
  void severalStoresWithoutATypeFail() {
    contextRunner
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
        .withUserConfiguration(RedisContainerConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(JobStoreUnavailableException.class)
                    .hasMessageContaining(
                        "Several Quartz job stores are available (mongodb, redis)"));
  }

  @Test
  void autoInstanceIdUsesTheInstanceIdGeneratorBean() {
    contextRunner
        .withPropertyValues("spring.quartz.scheduler.instance-id=AUTO")
        .withPropertyValues("spring.quartz.job-store.clustered=true")
        .withBean(InstanceIdGenerator.class, () -> () -> "generated-id")
        .run(
            context ->
                assertThat(context.getBean(Scheduler.class).getSchedulerInstanceId())
                    .isEqualTo("generated-id"));
  }

  @Test
  void sysPropInstanceIdReadsTheSystemProperty() {
    System.setProperty("org.quartz.scheduler.instanceId", "from-system-property");
    try {
      contextRunner
          .withPropertyValues("spring.quartz.scheduler.instance-id=SYS_PROP")
          .withPropertyValues("spring.quartz.job-store.clustered=true")
          .run(
              context ->
                  assertThat(context.getBean(Scheduler.class).getSchedulerInstanceId())
                      .isEqualTo("from-system-property"));
    } finally {
      System.clearProperty("org.quartz.scheduler.instanceId");
    }
  }

  public static class GreetingService {
    String greet() {
      return "hello quartz";
    }
  }

  public static class GreetingJob implements Job {
    @Autowired private GreetingService service;

    @Override
    public void execute(JobExecutionContext context) {
      RESULTS.add(
          service.greet() + (Thread.currentThread().isVirtual() ? " on virtual thread" : ""));
    }
  }

  static class RecordingPlugin implements SchedulerPlugin {
    @Override
    public void initialize(String name, Scheduler scheduler) {
      RESULTS.add("plugin " + name + " initialized");
    }

    @Override
    public void start() {}

    @Override
    public void shutdown() {}
  }

  @Configuration(proxyBeanMethods = false)
  static class TransactionalJobConfiguration {

    @Bean
    RecordingTransactionManager transactionManager() {
      return new RecordingTransactionManager();
    }

    @Bean
    JobDetail plainJob() {
      return JobBuilder.newJob(TransactionProbeJob.class)
          .withIdentity("plainJob")
          .storeDurably()
          .build();
    }

    @Bean
    Trigger plainTrigger(JobDetail plainJob) {
      return TriggerBuilder.newTrigger()
          .withIdentity("plainTrigger")
          .forJob(plainJob)
          .startNow()
          .build();
    }
  }

  public static class TransactionProbeJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      RESULTS.add(
          "in transaction: " + TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class QuartzBeansConfiguration {

    @Bean
    GreetingService greetingService() {
      return new GreetingService();
    }

    @Bean
    JobDetail greetingJob() {
      return JobBuilder.newJob(GreetingJob.class)
          .withIdentity("greetingJob")
          .storeDurably()
          .build();
    }

    @Bean
    Trigger greetingTrigger(JobDetail greetingJob) {
      return TriggerBuilder.newTrigger()
          .withIdentity("greetingTrigger")
          .forJob(greetingJob)
          .startNow()
          .build();
    }

    @Bean
    Calendar weekdays() {
      return new WeeklyCalendar();
    }

    @Bean
    JobListenerSupport jobListener() {
      return new JobListenerSupport() {
        @Override
        public String getName() {
          return "jobListener";
        }
      };
    }

    @Bean
    TriggerListenerSupport triggerListener() {
      return new TriggerListenerSupport() {
        @Override
        public String getName() {
          return "triggerListener";
        }
      };
    }

    @Bean
    SchedulerListener schedulerListener() {
      return new SchedulerListenerSupport() {};
    }

    @Bean
    SchedulerPlugin myPlugin() {
      return new RecordingPlugin();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomizerConfiguration {

    @Bean
    @Order(2)
    SchedulerFactoryBeanCustomizer secondCustomizer() {
      return bean -> {
        RESULTS.add("second");
        bean.setSchedulerName("second");
      };
    }

    @Bean
    @Order(1)
    SchedulerFactoryBeanCustomizer firstCustomizer() {
      return bean -> {
        RESULTS.add("first");
        bean.setSchedulerName("first");
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MongoContainerConfiguration {

    @Bean
    MongoConnectionDetails sharedMongoConnectionDetails() {
      return () -> new ConnectionString(AbstractMongoIT.connectionString());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RedisContainerConfiguration {

    @Bean
    DataRedisConnectionDetails sharedRedisConnectionDetails() {
      RedisURI uri = AbstractRedisIT.redisUri();
      return new DataRedisConnectionDetails() {
        @Override
        public Standalone getStandalone() {
          return Standalone.of(uri.getHost(), uri.getPort());
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnSchedulerFactoryBeanConfiguration {

    @Bean
    SchedulerFactoryBean ownScheduler() {
      SchedulerFactoryBean bean = new SchedulerFactoryBean();
      bean.setSchedulerName("own");
      bean.setJobStore(TestSchedulers.mongoStore());
      return bean;
    }
  }
}
