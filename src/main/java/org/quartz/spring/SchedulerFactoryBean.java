/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quartz.spring;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.quartz.Calendar;
import org.quartz.JobDetail;
import org.quartz.JobListener;
import org.quartz.ListenerManager;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerListener;
import org.quartz.Trigger;
import org.quartz.TriggerListener;
import org.quartz.core.JobRunShellFactory;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.JobFactory;
import org.quartz.spi.JobStore;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spi.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.SchedulingException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.Assert;

/**
 * {@link FactoryBean} that creates and configures a Quartz {@link org.quartz.Scheduler}, manages
 * its lifecycle as part of the Spring application context, and exposes the Scheduler as bean
 * reference for dependency injection.
 *
 * <p>Allows registration of JobDetails, Calendars and Triggers, automatically starting the
 * scheduler on initialization and shutting it down on destruction. In scenarios that just require
 * static registration of jobs at startup, there is no need to access the Scheduler instance itself
 * in application code.
 *
 * <p>For dynamic registration of jobs at runtime, use a bean reference to this SchedulerFactoryBean
 * to get direct access to the Quartz Scheduler ({@code org.quartz.Scheduler}). This allows you to
 * create new jobs and triggers, and also to control and monitor the entire Scheduler.
 *
 * <p>A new Job instance is created for each execution; only the JobDetail descriptors are shared.
 *
 * <p>The scheduler is created by a {@link StdSchedulerFactory} from typed {@link QuartzSettings}
 * plus collaborator instances (job store, thread pool or Spring {@link Executor}, plugins,
 * instance-id generator, clock).
 *
 * <p>Inside an application context, jobs annotated with {@link
 * org.quartz.ExecuteInTransaction @ExecuteInTransaction} run in Spring-managed transactions (see
 * {@link SpringTransactionJobRunShellFactory}); with {@link #setTransactionalJobs(boolean)
 * transactionalJobs} every job does.
 *
 * @author Juergen Hoeller
 * @see org.quartz.Scheduler
 * @see org.quartz.impl.StdSchedulerFactory
 */
public class SchedulerFactoryBean
    implements FactoryBean<Scheduler>,
        BeanNameAware,
        BeanClassLoaderAware,
        ApplicationContextAware,
        InitializingBean,
        DisposableBean,
        SmartLifecycle,
        ApplicationListener<ContextClosedEvent> {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private boolean overwriteExistingJobs = false;

  private @Nullable List<JobDetail> jobDetails;

  private @Nullable Map<String, Calendar> calendars;

  private @Nullable List<Trigger> triggers;

  private SchedulerListener @Nullable [] schedulerListeners;

  private JobListener @Nullable [] globalJobListeners;

  private TriggerListener @Nullable [] globalTriggerListeners;

  private @Nullable String schedulerName;

  private @Nullable QuartzSettings settings;

  private @Nullable Executor taskExecutor;

  private @Nullable ThreadPool threadPool;

  private @Nullable JobStore jobStore;

  private final Map<String, SchedulerPlugin> plugins = new LinkedHashMap<>();

  private @Nullable InstanceIdGenerator instanceIdGenerator;

  private @Nullable Clock clock;

  private @Nullable JobFactory jobFactory;

  private @Nullable JobRunShellFactory jobRunShellFactory;

  private boolean transactionalJobs = false;

  private Propagation defaultJobTransactionPropagation = Propagation.REQUIRED;

  private boolean autoStartup = true;

  private Duration startupDelay = Duration.ZERO;

  private int phase = DEFAULT_PHASE;

  private boolean waitForJobsToCompleteOnShutdown = false;

  private @Nullable String beanName;

  private @Nullable ApplicationContext applicationContext;

  private @Nullable ClassLoader beanClassLoader;

  private @Nullable Scheduler scheduler;

  /**
   * Set the name of the Scheduler to create.
   *
   * <p>If not specified, the name will be taken from the {@link #setSettings settings} (if given),
   * or from the declared {@code SchedulerFactoryBean} bean name as a fallback.
   *
   * @see #setBeanName
   */
  public void setSchedulerName(String schedulerName) {
    this.schedulerName = schedulerName;
  }

  /**
   * Set the typed scheduler, thread-pool and job-store settings.
   *
   * <p>Default is {@link QuartzSettings#defaults()}.
   */
  public void setSettings(QuartzSettings settings) {
    this.settings = settings;
  }

  /**
   * Set a Spring-managed {@link Executor} to use as Quartz backend. Exposed as thread pool through
   * the Quartz SPI.
   *
   * <p>Can be used to assign a local JDK ThreadPoolExecutor, a Spring {@code
   * ThreadPoolTaskExecutor} or a virtual-thread executor as Quartz backend, to avoid Quartz's
   * manual thread creation.
   *
   * <p>By default, a Quartz SimpleThreadPool will be used, configured through the thread-pool
   * settings.
   *
   * @see LocalTaskExecutorThreadPool
   * @see org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
   */
  public void setTaskExecutor(Executor taskExecutor) {
    this.taskExecutor = taskExecutor;
  }

  /**
   * Set the Quartz {@link ThreadPool} to use instead of a SimpleThreadPool created from the
   * thread-pool settings. Mutually exclusive with {@link #setTaskExecutor}.
   */
  public void setThreadPool(ThreadPool threadPool) {
    this.threadPool = threadPool;
  }

  /**
   * Set the {@link JobStore} (required: there is no default store, so {@link #afterPropertiesSet()}
   * fails without one).
   */
  public void setJobStore(JobStore jobStore) {
    this.jobStore = jobStore;
  }

  /** Return the {@link JobStore} the scheduler uses, or {@code null} if none was set. */
  public @Nullable JobStore getJobStore() {
    return this.jobStore;
  }

  /** Register {@link SchedulerPlugin}s by name. Plugins are initialized in map iteration order. */
  public void setPlugins(Map<String, ? extends SchedulerPlugin> plugins) {
    this.plugins.clear();
    this.plugins.putAll(plugins);
  }

  /**
   * Set the generator for automatically assigned instance ids ({@link
   * SchedulerSettings#instanceIdAuto()}).
   */
  public void setInstanceIdGenerator(InstanceIdGenerator instanceIdGenerator) {
    this.instanceIdGenerator = instanceIdGenerator;
  }

  /** Set the {@link Clock} the scheduler uses as its source of "now". */
  public void setClock(Clock clock) {
    this.clock = clock;
  }

  /**
   * Set the {@link JobFactory} to use for this Scheduler.
   *
   * <p>Default is a {@link SpringBeanJobFactory}: jobs are created as beans of the application
   * context when there is one, and their bean properties are populated from the job data map and
   * the scheduler context.
   *
   * @see SpringBeanJobFactory
   */
  public void setJobFactory(JobFactory jobFactory) {
    this.jobFactory = jobFactory;
  }

  /**
   * Set the factory of the shells that run each job. Default: inside an application context a
   * {@link SpringTransactionJobRunShellFactory} (configured from {@link
   * #setTransactionalJobs(boolean) transactionalJobs}, {@link
   * #setDefaultJobTransactionPropagation(Propagation) defaultJobTransactionPropagation}, the job
   * store and the clock of this bean), else Quartz's standard factory, which ignores {@link
   * org.quartz.ExecuteInTransaction}.
   */
  public void setJobRunShellFactory(JobRunShellFactory jobRunShellFactory) {
    this.jobRunShellFactory = jobRunShellFactory;
  }

  /**
   * Set whether every job runs in a transaction of the default transaction manager, not only jobs
   * annotated with {@link org.quartz.ExecuteInTransaction}. Requires an application context with a
   * unique or primary {@code PlatformTransactionManager}.
   *
   * <p>Default is "false".
   */
  public void setTransactionalJobs(boolean transactionalJobs) {
    this.transactionalJobs = transactionalJobs;
  }

  /**
   * Set the propagation of the transactions of jobs without {@link org.quartz.ExecuteInTransaction}
   * when {@link #setTransactionalJobs(boolean) transactionalJobs} is set.
   *
   * <p>Default is {@link Propagation#REQUIRED}.
   */
  public void setDefaultJobTransactionPropagation(Propagation defaultJobTransactionPropagation) {
    this.defaultJobTransactionPropagation = defaultJobTransactionPropagation;
  }

  /**
   * Set whether to automatically start the scheduler after initialization.
   *
   * <p>Default is "true"; set this to "false" to allow for manual startup.
   */
  public void setAutoStartup(boolean autoStartup) {
    this.autoStartup = autoStartup;
  }

  /**
   * Return whether this scheduler is configured for auto-startup. If "true", the scheduler will
   * start after the context is refreshed and after the start delay, if any.
   */
  @Override
  public boolean isAutoStartup() {
    return this.autoStartup;
  }

  /**
   * Specify the phase in which this scheduler should be started and stopped. The startup order
   * proceeds from lowest to highest, and the shutdown order is the reverse of that. By default this
   * value is {@code Integer.MAX_VALUE} meaning that this scheduler starts as late as possible and
   * stops as soon as possible.
   */
  public void setPhase(int phase) {
    this.phase = phase;
  }

  /** Return the phase in which this scheduler will be started and stopped. */
  @Override
  public int getPhase() {
    return this.phase;
  }

  /**
   * Set the delay to wait after initialization before starting the scheduler asynchronously.
   * Default is zero, meaning immediate synchronous startup on initialization of this bean.
   *
   * <p>Setting this to 10 or 20 seconds makes sense if no jobs should be run before the entire
   * application has started up.
   */
  public void setStartupDelay(Duration startupDelay) {
    Assert.notNull(startupDelay, "Startup delay must not be null");
    Assert.isTrue(!startupDelay.isNegative(), "Startup delay cannot be negative");
    this.startupDelay = startupDelay;
  }

  /**
   * Set whether to wait for running jobs to complete on shutdown.
   *
   * <p>Default is "false". Switch this to "true" if you prefer fully completed jobs at the expense
   * of a longer shutdown phase.
   *
   * @see org.quartz.Scheduler#shutdown(boolean)
   */
  public void setWaitForJobsToCompleteOnShutdown(boolean waitForJobsToCompleteOnShutdown) {
    this.waitForJobsToCompleteOnShutdown = waitForJobsToCompleteOnShutdown;
  }

  @Override
  public void setBeanName(String name) {
    this.beanName = name;
  }

  /**
   * The application's class loader: it loads job classes and is the context class loader of every
   * job execution. Set by the application context; without one, the application context's class
   * loader or else the thread context class loader is used.
   */
  @Override
  public void setBeanClassLoader(ClassLoader classLoader) {
    this.beanClassLoader = classLoader;
  }

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  // ---------------------------------------------------------------------
  // Implementation of InitializingBean interface
  // ---------------------------------------------------------------------

  @Override
  public void afterPropertiesSet() throws Exception {
    // Initialize the Scheduler instance...
    this.scheduler = prepareScheduler(prepareSchedulerFactory());
    try {
      registerListeners();
      registerJobsAndTriggers();
    } catch (Exception ex) {
      try {
        this.scheduler.shutdown(true);
      } catch (Exception ex2) {
        logger.debug("Scheduler shutdown exception after registration failure", ex2);
      }
      throw ex;
    }
  }

  /**
   * Create the factory of the scheduler from the settings and collaborators of this bean.
   *
   * @return the factory to obtain the Scheduler from
   */
  private StdSchedulerFactory prepareSchedulerFactory() {
    Assert.state(
        this.taskExecutor == null || this.threadPool == null,
        "Specify either a 'taskExecutor' or a 'threadPool', not both");

    QuartzSettings base = (this.settings != null ? this.settings : QuartzSettings.defaults());
    this.schedulerName = determineSchedulerName(base);
    SchedulerSettings schedulerSettings =
        base.scheduler().toBuilder().name(this.schedulerName).build();

    StdSchedulerFactory.Builder builder =
        StdSchedulerFactory.builder()
            .settings(new QuartzSettings(schedulerSettings, base.threadPool()));
    ClassLoader classLoader =
        (this.beanClassLoader != null)
            ? this.beanClassLoader
            : (this.applicationContext != null) ? this.applicationContext.getClassLoader() : null;
    if (classLoader != null) {
      builder.classLoader(classLoader);
    }
    if (this.taskExecutor != null) {
      LocalTaskExecutorThreadPool pool = new LocalTaskExecutorThreadPool(this.taskExecutor);
      // an executor without a limit runs at most thread-count jobs at a time
      pool.setDefaultCapacity(base.threadPool().threadCount());
      builder.threadPool(pool);
    } else if (this.threadPool != null) {
      builder.threadPool(this.threadPool);
    }
    if (this.jobStore != null) {
      builder.jobStore(this.jobStore);
    }
    if (this.instanceIdGenerator != null) {
      builder.instanceIdGenerator(this.instanceIdGenerator);
    }
    if (this.clock != null) {
      builder.clock(this.clock);
    }
    JobRunShellFactory shellFactory = determineJobRunShellFactory();
    if (shellFactory != null) {
      builder.jobRunShellFactory(shellFactory);
    }
    this.plugins.forEach(builder::plugin);
    return builder.build();
  }

  private @Nullable JobRunShellFactory determineJobRunShellFactory() {
    if (this.jobRunShellFactory != null) {
      return this.jobRunShellFactory;
    }
    if (this.applicationContext == null) {
      Assert.state(!this.transactionalJobs, "'transactionalJobs' requires an application context");
      return null;
    }
    SpringTransactionJobRunShellFactory factory =
        new SpringTransactionJobRunShellFactory(this.applicationContext);
    factory.setTransactionalByDefault(this.transactionalJobs);
    factory.setDefaultPropagation(this.defaultJobTransactionPropagation);
    factory.setJobStore(this.jobStore);
    if (this.clock != null) {
      factory.setClock(this.clock);
    }
    return factory;
  }

  /**
   * Determine the scheduler name: an explicit name, else the name from explicitly given settings,
   * else the bean name.
   */
  private String determineSchedulerName(QuartzSettings base) {
    if (this.schedulerName != null) {
      return this.schedulerName;
    }
    if (this.settings != null || this.beanName == null) {
      return base.scheduler().name();
    }
    return this.beanName;
  }

  private Scheduler prepareScheduler(StdSchedulerFactory schedulerFactory)
      throws SchedulerException {
    Scheduler scheduler = schedulerFactory.getScheduler();

    JobFactory jobFactory =
        (this.jobFactory != null) ? this.jobFactory : new SpringBeanJobFactory();
    if (this.applicationContext != null
        && jobFactory instanceof ApplicationContextAware applicationContextAware) {
      applicationContextAware.setApplicationContext(this.applicationContext);
    }
    if (jobFactory instanceof SchedulerContextAware schedulerContextAware) {
      schedulerContextAware.setSchedulerContext(scheduler.getContext());
    }
    scheduler.setJobFactory(jobFactory);
    return scheduler;
  }

  /**
   * Start the Quartz Scheduler, respecting the "startupDelay" setting.
   *
   * @param scheduler the Scheduler to start
   * @param startupDelay the delay before starting the Scheduler asynchronously
   */
  protected void startScheduler(final Scheduler scheduler, final Duration startupDelay)
      throws SchedulerException {
    if (startupDelay.isZero()) {
      logger.info("Starting Quartz Scheduler now");
      scheduler.start();
    } else {
      if (logger.isInfoEnabled()) {
        logger.info(
            "Will start Quartz Scheduler ["
                + scheduler.getSchedulerName()
                + "] in "
                + startupDelay);
      }
      // Not using the Quartz startDelayed method since we explicitly want a daemon
      // thread here, not keeping the JVM alive in case of all other threads ending.
      Thread schedulerThread =
          new Thread(
              () -> {
                try {
                  Thread.sleep(startupDelay);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  // simply proceed
                }
                if (logger.isInfoEnabled()) {
                  logger.info("Starting Quartz Scheduler now, after delay of " + startupDelay);
                }
                try {
                  scheduler.start();
                } catch (SchedulerException ex) {
                  throw new SchedulingException("Could not start Quartz Scheduler after delay", ex);
                }
              });
      schedulerThread.setName("Quartz Scheduler [" + scheduler.getSchedulerName() + "]");
      schedulerThread.setDaemon(true);
      schedulerThread.start();
    }
  }

  // ---------------------------------------------------------------------
  // Implementation of FactoryBean interface
  // ---------------------------------------------------------------------

  /** The scheduler this bean created; available once the bean is initialized. */
  public Scheduler getScheduler() {
    Assert.state(this.scheduler != null, "No Scheduler set");
    return this.scheduler;
  }

  @Override
  public @Nullable Scheduler getObject() {
    return this.scheduler;
  }

  @Override
  public Class<? extends Scheduler> getObjectType() {
    return (this.scheduler != null ? this.scheduler.getClass() : Scheduler.class);
  }

  @Override
  public boolean isSingleton() {
    return true;
  }

  // ---------------------------------------------------------------------
  // Implementation of SmartLifecycle interface
  // ---------------------------------------------------------------------

  @Override
  public void start() throws SchedulingException {
    if (this.scheduler != null) {
      try {
        startScheduler(this.scheduler, this.startupDelay);
      } catch (SchedulerException ex) {
        throw new SchedulingException("Could not start Quartz Scheduler", ex);
      }
    }
  }

  @Override
  public void stop() throws SchedulingException {
    if (this.scheduler != null) {
      try {
        this.scheduler.standby();
      } catch (SchedulerException ex) {
        throw new SchedulingException("Could not stop Quartz Scheduler", ex);
      }
    }
  }

  @Override
  public boolean isRunning() throws SchedulingException {
    if (this.scheduler != null) {
      try {
        return !this.scheduler.isInStandbyMode();
      } catch (SchedulerException ex) {
        return false;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------
  // Implementation of ApplicationListener interface
  // ---------------------------------------------------------------------

  /**
   * Shut down the Quartz scheduler when its application context closes, before the context stops
   * its lifecycle beans: jobs still running (or waited for, see {@link
   * #setWaitForJobsToCompleteOnShutdown}) can then still reach the job store, whose connection
   * (e.g. a {@code LettuceConnectionFactory}) may be such a bean. {@link #destroy()} has nothing
   * left to do afterwards.
   */
  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    if (this.applicationContext != null
        && event.getApplicationContext() == this.applicationContext) {
      try {
        destroy();
      } catch (SchedulerException ex) {
        logger.warn("Could not shut down Quartz Scheduler", ex);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Implementation of DisposableBean interface
  // ---------------------------------------------------------------------

  /** Shut down the Quartz scheduler on bean factory shutdown, stopping all scheduled jobs. */
  @Override
  public void destroy() throws SchedulerException {
    if (this.scheduler != null && !this.scheduler.isShutdown()) {
      logger.info("Shutting down Quartz Scheduler");
      this.scheduler.shutdown(this.waitForJobsToCompleteOnShutdown);
    }
  }

  /**
   * Set whether any jobs defined on this SchedulerFactoryBean should overwrite existing job
   * definitions. Default is "false", to not overwrite already registered jobs that have been read
   * in from a persistent job store.
   */
  public void setOverwriteExistingJobs(boolean overwriteExistingJobs) {
    this.overwriteExistingJobs = overwriteExistingJobs;
  }

  /**
   * Register a list of JobDetail objects with the Scheduler that this FactoryBean creates, to be
   * referenced by Triggers. A job without a trigger must be durable.
   *
   * @see #setTriggers
   * @see org.quartz.JobDetail
   */
  public void setJobDetails(JobDetail... jobDetails) {
    this.jobDetails = Arrays.asList(jobDetails);
  }

  /**
   * Register a list of Quartz Calendar objects with the Scheduler that this FactoryBean creates, to
   * be referenced by Triggers.
   *
   * @param calendars a Map with calendar names as keys as Calendar objects as values
   * @see org.quartz.Calendar
   */
  public void setCalendars(Map<String, Calendar> calendars) {
    this.calendars = calendars;
  }

  /**
   * Register a list of Trigger objects with the Scheduler that this FactoryBean creates.
   *
   * <p>The job a trigger fires must be stored already, or be registered via the "jobDetails"
   * property of this FactoryBean.
   *
   * @see #setJobDetails
   * @see org.quartz.JobDetail
   */
  public void setTriggers(Trigger... triggers) {
    this.triggers = Arrays.asList(triggers);
  }

  /** Specify Quartz SchedulerListeners to be registered with the Scheduler. */
  public void setSchedulerListeners(SchedulerListener... schedulerListeners) {
    this.schedulerListeners = schedulerListeners;
  }

  /**
   * Specify global Quartz JobListeners to be registered with the Scheduler. Such JobListeners will
   * apply to all Jobs in the Scheduler.
   */
  public void setGlobalJobListeners(JobListener... globalJobListeners) {
    this.globalJobListeners = globalJobListeners;
  }

  /**
   * Specify global Quartz TriggerListeners to be registered with the Scheduler. Such
   * TriggerListeners will apply to all Triggers in the Scheduler.
   */
  public void setGlobalTriggerListeners(TriggerListener... globalTriggerListeners) {
    this.globalTriggerListeners = globalTriggerListeners;
  }

  /** Register the jobs, calendars and triggers set on this bean. */
  private void registerJobsAndTriggers() throws SchedulerException {
    try {
      // Register JobDetails.
      if (this.jobDetails != null) {
        for (JobDetail jobDetail : this.jobDetails) {
          addJobToScheduler(jobDetail);
        }
      }

      // Register Calendars.
      if (this.calendars != null) {
        for (String calendarName : this.calendars.keySet()) {
          Calendar calendar = this.calendars.get(calendarName);
          getScheduler().addCalendar(calendarName, calendar, true, true);
        }
      }

      // Register Triggers.
      if (this.triggers != null) {
        for (Trigger trigger : this.triggers) {
          addTriggerToScheduler(trigger);
        }
      }
    } catch (RuntimeException ex) {
      throw new SchedulerException(
          "Registration of jobs and triggers failed: " + ex.getMessage(), ex);
    }
  }

  /**
   * Add the given job to the Scheduler, if it doesn't already exist. Overwrites the job in any case
   * if "overwriteExistingJobs" is set.
   *
   * @param jobDetail the job to add
   * @return {@code true} if the job was actually added, {@code false} if it already existed before
   * @see #setOverwriteExistingJobs
   */
  private boolean addJobToScheduler(JobDetail jobDetail) throws SchedulerException {
    if (this.overwriteExistingJobs || getScheduler().getJobDetail(jobDetail.getKey()) == null) {
      getScheduler().addJob(jobDetail, true);
      return true;
    } else {
      return false;
    }
  }

  /**
   * Add the given trigger to the Scheduler, if it doesn't already exist. Overwrites the trigger in
   * any case if "overwriteExistingJobs" is set.
   *
   * @param trigger the trigger to add
   * @return {@code true} if the trigger was actually added, {@code false} if it already existed
   *     before
   * @see #setOverwriteExistingJobs
   */
  private boolean addTriggerToScheduler(Trigger trigger) throws SchedulerException {
    boolean triggerExists = (getScheduler().getTrigger(trigger.getKey()) != null);
    if (triggerExists && !this.overwriteExistingJobs) {
      return false;
    }

    if (triggerExists) {
      try {
        getScheduler().rescheduleJob(trigger.getKey(), trigger);
      } catch (ObjectAlreadyExistsException ex) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Unexpectedly encountered existing trigger on rescheduling, assumably due to "
                  + "cluster race condition: "
                  + ex.getMessage()
                  + " - can safely be ignored");
        }
      }
    } else {
      try {
        getScheduler().scheduleJob(trigger);
      } catch (ObjectAlreadyExistsException ex) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Unexpectedly encountered existing trigger on job scheduling, assumably due to "
                  + "cluster race condition: "
                  + ex.getMessage()
                  + " - can safely be ignored");
        }
        if (this.overwriteExistingJobs) {
          getScheduler().rescheduleJob(trigger.getKey(), trigger);
        }
      }
    }
    return true;
  }

  /** Register all specified listeners with the Scheduler. */
  private void registerListeners() throws SchedulerException {
    ListenerManager listenerManager = getScheduler().getListenerManager();
    if (this.schedulerListeners != null) {
      for (SchedulerListener listener : this.schedulerListeners) {
        listenerManager.addSchedulerListener(listener);
      }
    }
    if (this.globalJobListeners != null) {
      for (JobListener listener : this.globalJobListeners) {
        listenerManager.addJobListener(listener);
      }
    }
    if (this.globalTriggerListeners != null) {
      for (TriggerListener listener : this.globalTriggerListeners) {
        listenerManager.addTriggerListener(listener);
      }
    }
  }
}
