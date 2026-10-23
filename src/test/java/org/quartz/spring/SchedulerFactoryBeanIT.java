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
package org.quartz.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerMetaData;
import org.quartz.SimpleTrigger;
import org.quartz.impl.calendar.WeeklyCalendar;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.support.AbstractMongoIT;
import org.quartz.support.TestSchedulers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SchedulerFactoryBeanIT extends AbstractMongoIT {

  static final List<String> RESULTS = new CopyOnWriteArrayList<>();

  public static class GreetingService {
    String greet(String name) {
      return "hello " + name;
    }
  }

  public static class AutowiredJob implements Job {
    @Autowired private GreetingService service;

    private String name;

    public void setName(String name) {
      this.name = name;
    }

    @Override
    public void execute(JobExecutionContext context) {
      RESULTS.add(service.greet(name) + " on " + Thread.currentThread().getName());
    }
  }

  public static class BlockingJob implements Job {
    static volatile CountDownLatch release = new CountDownLatch(1);

    @Override
    public void execute(JobExecutionContext context) {
      RESULTS.add("started");
      try {
        release.await();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      RESULTS.add("finished");
    }
  }

  public static class RunnableJob implements Runnable {
    @Override
    public void run() {
      RESULTS.add("runnable");
    }
  }

  private AnnotationConfigApplicationContext context;

  private SchedulerFactoryBean factoryBean;

  @BeforeEach
  void setUp() {
    RESULTS.clear();
    context = new AnnotationConfigApplicationContext(GreetingService.class);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (factoryBean != null) {
      factoryBean.destroy();
    }
    context.close();
  }

  private static SimpleTrigger fireOnceTrigger(
      String name, Class<?> jobClass, Map<String, ?> jobData) {
    JobDetailFactoryBean job = new JobDetailFactoryBean();
    job.setBeanName(name + "Job");
    @SuppressWarnings("unchecked")
    Class<? extends Job> type = (Class<? extends Job>) jobClass;
    job.setJobClass(type);
    job.setJobDataAsMap(jobData);
    job.afterPropertiesSet();
    SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
    trigger.setBeanName(name + "Trigger");
    trigger.setJobDetail(job.getObject());
    trigger.setRepeatCount(0);
    trigger.afterPropertiesSet();
    return trigger.getObject();
  }

  private SchedulerFactoryBean newFactoryBean(String beanName) {
    SchedulerFactoryBean bean = new SchedulerFactoryBean();
    bean.setBeanName(beanName);
    bean.setApplicationContext(context);
    bean.setSettings(
        new QuartzSettings(
            SchedulerSettings.builder().name(beanName).build(),
            ThreadPoolSettings.builder().threadCount(2).build()));
    bean.setJobStore(TestSchedulers.mongoStore());
    return bean;
  }

  @Test
  void springBeanJobFactoryAutowiresJobsAndAppliesJobData() throws Exception {
    factoryBean = newFactoryBean("autowiring");
    factoryBean.setJobFactory(new SpringBeanJobFactory());
    factoryBean.setTriggers(
        fireOnceTrigger("greeting", AutowiredJob.class, Map.of("name", "quartz")));
    factoryBean.afterPropertiesSet();
    factoryBean.start();

    await().atMost(Duration.ofSeconds(10)).until(() -> !RESULTS.isEmpty());
    assertThat(RESULTS.get(0)).startsWith("hello quartz on autowiring_Worker");
  }

  @Test
  void schedulerIsShutDownBeforeTheContextStopsItsLifecycleBeans() throws Exception {
    // e.g. a LettuceConnectionFactory (phase 0) the job store still needs to complete running jobs
    AtomicReference<Boolean> shutDownWhenStopped = new AtomicReference<>();
    try (AnnotationConfigApplicationContext app = new AnnotationConfigApplicationContext()) {
      app.registerBean(
          "lifecycle",
          SchedulerFactoryBean.class,
          () -> {
            SchedulerFactoryBean bean = new SchedulerFactoryBean();
            bean.setSettings(
                new QuartzSettings(
                    SchedulerSettings.builder().name("lifecycle").build(),
                    ThreadPoolSettings.builder().threadCount(2).build()));
            bean.setJobStore(TestSchedulers.mongoStore());
            return bean;
          });
      app.registerBean(
          "connectionFactory",
          SmartLifecycle.class,
          () ->
              new SmartLifecycle() {
                private volatile boolean running;

                @Override
                public void start() {
                  running = true;
                }

                @Override
                public void stop() {
                  Scheduler scheduler = app.getBean(Scheduler.class);
                  shutDownWhenStopped.set(isShutdown(scheduler));
                  running = false;
                }

                @Override
                public boolean isRunning() {
                  return running;
                }

                @Override
                public int getPhase() {
                  return 0;
                }
              });
      app.refresh();
      assertThat(app.getBean(Scheduler.class).isStarted()).isTrue();
    }
    assertThat(shutDownWhenStopped.get()).isTrue();
  }

  private static boolean isShutdown(Scheduler scheduler) {
    try {
      return scheduler.isShutdown();
    } catch (SchedulerException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  void adaptableJobFactoryWrapsRunnables() throws Exception {
    AdaptableJobFactory factory = new AdaptableJobFactory();
    Job job = factory.adaptJob(new RunnableJob());

    assertThat(job).isInstanceOf(DelegatingJob.class);
    job.execute(null);
    assertThat(RESULTS).containsExactly("runnable");
    assertThatThrownBy(() -> factory.adaptJob("not a job"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void taskExecutorRunsTheJobs() throws Exception {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "spring-executor"));
    try {
      factoryBean = newFactoryBean("executor");
      factoryBean.setTaskExecutor(executor);
      factoryBean.setJobFactory(new SpringBeanJobFactory());
      factoryBean.setTriggers(
          fireOnceTrigger("executor", AutowiredJob.class, Map.of("name", "pool")));
      factoryBean.afterPropertiesSet();
      factoryBean.start();

      await().atMost(Duration.ofSeconds(10)).until(() -> !RESULTS.isEmpty());
      assertThat(RESULTS.get(0)).isEqualTo("hello pool on spring-executor");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void anExecutorWithoutALimitRunsAtMostThreadCountJobs() throws Exception {
    ExecutorService executor = Executors.newCachedThreadPool();
    try {
      factoryBean = newFactoryBean("unlimitedExecutor"); // thread-count 2
      factoryBean.setTaskExecutor(executor);
      factoryBean.afterPropertiesSet();

      SchedulerMetaData metaData = factoryBean.getScheduler().getMetaData();
      assertThat(metaData.getThreadPoolSize()).isEqualTo(2);
      assertThat(metaData.getThreadPoolDescription())
          .endsWith("at most 2 concurrent jobs (the executor sets no limit)");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void shutdownWaitsForJobsRunningOnTheTaskExecutor() throws Exception {
    BlockingJob.release = new CountDownLatch(1);
    ExecutorService executor = Executors.newCachedThreadPool();
    try {
      factoryBean = newFactoryBean("executorShutdown");
      factoryBean.setTaskExecutor(executor);
      factoryBean.setWaitForJobsToCompleteOnShutdown(true);
      factoryBean.setTriggers(fireOnceTrigger("blocking", BlockingJob.class, Map.of()));
      factoryBean.afterPropertiesSet();
      factoryBean.start();
      await().atMost(Duration.ofSeconds(10)).until(() -> RESULTS.contains("started"));

      CompletableFuture<Void> shutdown =
          CompletableFuture.runAsync(
              () -> {
                try {
                  factoryBean.destroy();
                } catch (SchedulerException ex) {
                  throw new IllegalStateException(ex);
                }
              });
      await()
          .during(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(2))
          .until(() -> !shutdown.isDone());
      BlockingJob.release.countDown();
      await().atMost(Duration.ofSeconds(10)).until(shutdown::isDone);
      shutdown.join();
      assertThat(RESULTS).containsExactly("started", "finished");
    } finally {
      BlockingJob.release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void taskExecutorAndThreadPoolAreMutuallyExclusive() {
    factoryBean = null;
    SchedulerFactoryBean bean = newFactoryBean("both");
    bean.setTaskExecutor(Runnable::run);
    bean.setThreadPool(new SimpleThreadPool(1, 5));

    assertThatThrownBy(bean::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void schedulerNamePrecedence() throws Exception {
    SchedulerFactoryBean explicit = newFactoryBean("beanA");
    explicit.setSchedulerName("explicitName");
    explicit.afterPropertiesSet();
    assertThat(explicit.getScheduler().getSchedulerName()).isEqualTo("explicitName");
    explicit.destroy();

    SchedulerFactoryBean fromSettings = newFactoryBean("beanB");
    fromSettings.setSettings(
        new QuartzSettings(
            SchedulerSettings.builder().name("settingsName").build(),
            ThreadPoolSettings.builder().threadCount(1).build()));
    fromSettings.afterPropertiesSet();
    assertThat(fromSettings.getScheduler().getSchedulerName()).isEqualTo("settingsName");
    fromSettings.destroy();

    SchedulerFactoryBean fromBeanName = new SchedulerFactoryBean();
    fromBeanName.setBeanName("beanNameScheduler");
    fromBeanName.setJobStore(TestSchedulers.mongoStore());
    fromBeanName.afterPropertiesSet();
    assertThat(fromBeanName.getScheduler().getSchedulerName()).isEqualTo("beanNameScheduler");
    fromBeanName.destroy();
  }

  @Test
  void schedulerContextIsPopulated() throws Exception {
    factoryBean = newFactoryBean("contextMap");
    factoryBean.setSchedulerContextAsMap(Map.of("service", "value"));
    factoryBean.setApplicationContextSchedulerContextKey("applicationContext");
    factoryBean.afterPropertiesSet();

    Scheduler scheduler = factoryBean.getScheduler();
    assertThat(scheduler.getContext().get("service")).isEqualTo("value");
    assertThat(scheduler.getContext().get("applicationContext")).isSameAs(context);
  }

  @Test
  void jobsCalendarsAndTriggersAreRegistered() throws Exception {
    JobDetailFactoryBean job = new JobDetailFactoryBean();
    job.setBeanName("durableJob");
    job.setJobClass(AutowiredJob.class);
    job.setDurability(true);
    job.afterPropertiesSet();
    JobDetail jobDetail = job.getObject();
    SimpleTrigger trigger = fireOnceTrigger("registered", AutowiredJob.class, Map.of("name", "x"));

    factoryBean = newFactoryBean("registration");
    factoryBean.setJobDetails(jobDetail);
    factoryBean.setCalendars(Map.of("weekdays", new WeeklyCalendar()));
    factoryBean.setTriggers(trigger);
    factoryBean.afterPropertiesSet();

    Scheduler scheduler = factoryBean.getScheduler();
    assertThat(scheduler.getJobDetail(jobDetail.getKey())).isNotNull();
    assertThat(scheduler.getCalendar("weekdays")).isInstanceOf(WeeklyCalendar.class);
    assertThat(scheduler.getTrigger(trigger.getKey())).isNotNull();
    assertThat(scheduler.getJobDetail(trigger.getJobKey())).isNotNull();
  }

  @Test
  void startupDelayStartsTheSchedulerAsynchronously() throws Exception {
    factoryBean = newFactoryBean("delayed");
    factoryBean.setStartupDelay(Duration.ofMillis(500));
    factoryBean.afterPropertiesSet();
    factoryBean.start();

    assertThat(factoryBean.getScheduler().isStarted()).isFalse();
    await().atMost(Duration.ofSeconds(10)).until(() -> factoryBean.getScheduler().isStarted());
  }

  @Test
  void lifecycleStartStopAndDestroy() throws Exception {
    factoryBean = newFactoryBean("lifecycle");
    factoryBean.afterPropertiesSet();
    Scheduler scheduler = factoryBean.getScheduler();

    assertThat(factoryBean.isRunning()).isFalse();
    factoryBean.start();
    assertThat(factoryBean.isRunning()).isTrue();
    factoryBean.stop();
    assertThat(factoryBean.isRunning()).isFalse();
    assertThat(scheduler.isInStandbyMode()).isTrue();
    factoryBean.destroy();
    assertThat(scheduler.isShutdown()).isTrue();
    factoryBean = null;
  }

  @Test
  void anActiveSchedulerOfTheSameNameIsRejected() throws Exception {
    factoryBean = newFactoryBean("duplicate");
    factoryBean.setExposeSchedulerInRepository(true);
    factoryBean.afterPropertiesSet();

    SchedulerFactoryBean second = newFactoryBean("duplicate");
    assertThatThrownBy(second::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already registered");
  }

  @Test
  void negativeStartupDelayIsRejected() {
    assertThatThrownBy(() -> new SchedulerFactoryBean().setStartupDelay(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
