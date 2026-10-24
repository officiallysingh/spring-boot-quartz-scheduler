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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.core.JobRunShellFactory;
import org.quartz.impl.SchedulerRepository;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.JobFactory;
import org.quartz.spi.JobStore;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spi.ThreadPool;
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
 * <p>Note that Quartz instantiates a new Job for each execution, in contrast to Timer which uses a
 * TimerTask instance that is shared between repeated executions. Just JobDetail descriptors are
 * shared.
 *
 * <p>The scheduler is created by a {@link StdSchedulerFactory} from typed {@link QuartzSettings}
 * plus collaborator instances (job store, thread pool or Spring {@link Executor}, plugins,
 * instance-id generator, clock); there is no {@code quartz.properties} or {@code DataSource}
 * support.
 *
 * <p>Inside an application context, jobs annotated with {@link
 * org.quartz.ExecuteInTransaction @ExecuteInTransaction} run in Spring-managed transactions (see
 * {@link SpringTransactionJobRunShellFactory}); with {@link #setTransactionalJobs(boolean)
 * transactionalJobs} every job does.
 *
 * @author Juergen Hoeller
 * @since 18.02.2004
 * @see org.quartz.Scheduler
 * @see org.quartz.SchedulerFactory
 * @see org.quartz.impl.StdSchedulerFactory
 */
public class SchedulerFactoryBean extends SchedulerAccessor
    implements FactoryBean<Scheduler>,
        BeanNameAware,
        ApplicationContextAware,
        InitializingBean,
        DisposableBean,
        SmartLifecycle,
        ApplicationListener<ContextClosedEvent> {

  private @Nullable SchedulerFactory schedulerFactory;

  private @Nullable String schedulerName;

  private @Nullable QuartzSettings settings;

  private @Nullable Executor taskExecutor;

  private @Nullable ThreadPool threadPool;

  private @Nullable JobStore jobStore;

  private final Map<String, SchedulerPlugin> plugins = new LinkedHashMap<>();

  private @Nullable InstanceIdGenerator instanceIdGenerator;

  private @Nullable Clock clock;

  private @Nullable Map<String, ?> schedulerContextMap;

  private @Nullable String applicationContextSchedulerContextKey;

  private @Nullable JobFactory jobFactory;

  private @Nullable JobRunShellFactory jobRunShellFactory;

  private boolean transactionalJobs = false;

  private Propagation defaultJobTransactionPropagation = Propagation.REQUIRED;

  private boolean jobFactorySet = false;

  private boolean autoStartup = true;

  private Duration startupDelay = Duration.ZERO;

  private int phase = DEFAULT_PHASE;

  private boolean exposeSchedulerInRepository = false;

  private boolean waitForJobsToCompleteOnShutdown = false;

  private @Nullable String beanName;

  private @Nullable ApplicationContext applicationContext;

  private @Nullable Scheduler scheduler;

  /**
   * Set an external Quartz {@link SchedulerFactory} instance to use.
   *
   * <p>Default is an internal {@link StdSchedulerFactory} built from the settings and collaborators
   * configured on this bean. If this method is called, those local settings are ignored, expecting
   * the external {@code SchedulerFactory} instance to be configured on its own.
   *
   * @since 4.3.15
   */
  public void setSchedulerFactory(SchedulerFactory schedulerFactory) {
    this.schedulerFactory = schedulerFactory;
  }

  /**
   * Set the name of the Scheduler to create.
   *
   * <p>If not specified, the name will be taken from the {@link #setSettings settings} (if given),
   * or from the declared {@code SchedulerFactoryBean} bean name as a fallback.
   *
   * @see #setBeanName
   * @see org.quartz.SchedulerFactory#getScheduler()
   * @see org.quartz.SchedulerFactory#getScheduler(String)
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
   * @since 2.0
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
   * Register objects in the Scheduler context via a given Map. These objects will be available to
   * any Job that runs in this Scheduler.
   *
   * <p>Note: When using persistent Jobs whose JobDetail will be kept in a persistent store, do not
   * put Spring-managed beans or an ApplicationContext reference into the JobDataMap but rather into
   * the SchedulerContext.
   *
   * @param schedulerContextAsMap a Map with String keys and any objects as values (for example
   *     Spring-managed beans)
   */
  public void setSchedulerContextAsMap(Map<String, ?> schedulerContextAsMap) {
    this.schedulerContextMap = schedulerContextAsMap;
  }

  /**
   * Set the key of an {@link ApplicationContext} reference to expose in the SchedulerContext, for
   * example "applicationContext". Default is none. Only applicable when running in a Spring
   * ApplicationContext.
   *
   * <p>Note: When using persistent Jobs whose JobDetail will be kept in a persistent store, do not
   * put an ApplicationContext reference into the JobDataMap but rather into the SchedulerContext.
   *
   * <p>In case of a QuartzJobBean, the reference will be applied to the Job instance as bean
   * property. An "applicationContext" attribute will correspond to a "setApplicationContext" method
   * in that scenario.
   *
   * <p>Note that BeanFactory callback interfaces like ApplicationContextAware are not automatically
   * applied to Quartz Job instances, because Quartz itself is responsible for the lifecycle of its
   * Jobs.
   *
   * @see org.springframework.context.ApplicationContext
   */
  public void setApplicationContextSchedulerContextKey(
      String applicationContextSchedulerContextKey) {
    this.applicationContextSchedulerContextKey = applicationContextSchedulerContextKey;
  }

  /**
   * Set the Quartz {@link JobFactory} to use for this Scheduler.
   *
   * <p>Default is {@link AdaptableJobFactory}, which supports {@link java.lang.Runnable} objects as
   * well as standard Quartz {@link org.quartz.Job} instances.
   *
   * <p>Specify an instance of {@link SpringBeanJobFactory} here (typically as an inner bean
   * definition) to automatically populate a job's bean properties from the specified job data map
   * and scheduler context.
   *
   * @since 2.0
   * @see AdaptableJobFactory
   * @see SpringBeanJobFactory
   */
  public void setJobFactory(JobFactory jobFactory) {
    this.jobFactory = jobFactory;
    this.jobFactorySet = true;
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
   *
   * @since 3.0
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
   * Set whether to expose the Spring-managed {@link Scheduler} instance in the Quartz {@link
   * SchedulerRepository}. Default is "false", since the Spring-managed Scheduler is usually
   * exclusively intended for access within the Spring context.
   *
   * <p>Switch this flag to "true" in order to expose the Scheduler globally. This is not
   * recommended unless you have an existing Spring application that relies on this behavior.
   */
  public void setExposeSchedulerInRepository(boolean exposeSchedulerInRepository) {
    this.exposeSchedulerInRepository = exposeSchedulerInRepository;
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

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  // ---------------------------------------------------------------------
  // Implementation of InitializingBean interface
  // ---------------------------------------------------------------------

  @Override
  public void afterPropertiesSet() throws Exception {
    if (this.applicationContext != null && this.resourceLoader == null) {
      this.resourceLoader = this.applicationContext;
    }

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
   * Create the SchedulerFactory if necessary, applying the locally defined settings and
   * collaborators to it.
   *
   * @return the SchedulerFactory to obtain the Scheduler from
   */
  private SchedulerFactory prepareSchedulerFactory() {
    if (this.schedulerFactory != null) {
      // Assume that externally provided factory has been initialized with appropriate settings
      return this.schedulerFactory;
    }
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
    if (this.resourceLoader != null) {
      builder.classLoadHelper(new ResourceLoaderClassLoadHelper(this.resourceLoader));
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

  private Scheduler prepareScheduler(SchedulerFactory schedulerFactory) throws SchedulerException {
    // Get Scheduler instance from SchedulerFactory.
    Scheduler scheduler = createScheduler(schedulerFactory, this.schedulerName);
    populateSchedulerContext(scheduler);

    if (!this.jobFactorySet) {
      // Use AdaptableJobFactory as default for a local Scheduler, unless when
      // explicitly given a null value through the "jobFactory" bean property.
      this.jobFactory = new AdaptableJobFactory();
    }
    if (this.jobFactory != null) {
      if (this.applicationContext != null
          && this.jobFactory instanceof ApplicationContextAware applicationContextAware) {
        applicationContextAware.setApplicationContext(this.applicationContext);
      }
      if (this.jobFactory instanceof SchedulerContextAware schedulerContextAware) {
        schedulerContextAware.setSchedulerContext(scheduler.getContext());
      }
      scheduler.setJobFactory(this.jobFactory);
    }
    return scheduler;
  }

  /**
   * Create the Scheduler instance for the given factory and scheduler name. Called by {@link
   * #afterPropertiesSet}.
   *
   * <p>The default implementation invokes SchedulerFactory's {@code getScheduler} method. Can be
   * overridden for custom Scheduler creation.
   *
   * @param schedulerFactory the factory to create the Scheduler with
   * @param schedulerName the name of the scheduler to create
   * @return the Scheduler instance
   * @throws SchedulerException if thrown by Quartz methods
   * @see #afterPropertiesSet
   * @see org.quartz.SchedulerFactory#getScheduler
   */
  protected Scheduler createScheduler(
      SchedulerFactory schedulerFactory, @Nullable String schedulerName) throws SchedulerException {

    // Override thread context ClassLoader to work around naive Quartz ClassLoadHelper loading.
    Thread currentThread = Thread.currentThread();
    ClassLoader threadContextClassLoader = currentThread.getContextClassLoader();
    ClassLoader resourceClassLoader =
        (this.resourceLoader != null
            ? this.resourceLoader.getClassLoader()
            : threadContextClassLoader);
    boolean overrideClassLoader = (resourceClassLoader != threadContextClassLoader);
    if (overrideClassLoader) {
      currentThread.setContextClassLoader(resourceClassLoader);
    }
    try {
      SchedulerRepository repository = SchedulerRepository.getInstance();
      synchronized (repository) {
        Scheduler existingScheduler =
            (schedulerName != null ? repository.lookup(schedulerName) : null);
        Scheduler newScheduler = schedulerFactory.getScheduler();
        if (newScheduler == existingScheduler) {
          throw new IllegalStateException(
              "Active Scheduler of name '"
                  + schedulerName
                  + "' already registered "
                  + "in Quartz SchedulerRepository. Cannot create a new Spring-managed Scheduler of the same name!");
        }
        if (!this.exposeSchedulerInRepository) {
          // Need to remove it in this case, since Quartz shares the Scheduler instance by default!
          SchedulerRepository.getInstance().remove(newScheduler.getSchedulerName());
        }
        return newScheduler;
      }
    } finally {
      if (overrideClassLoader) {
        // Reset original thread context ClassLoader.
        currentThread.setContextClassLoader(threadContextClassLoader);
      }
    }
  }

  /**
   * Expose the specified context attributes and/or the current ApplicationContext in the Quartz
   * SchedulerContext.
   */
  private void populateSchedulerContext(Scheduler scheduler) throws SchedulerException {
    // Put specified objects into Scheduler context.
    if (this.schedulerContextMap != null) {
      scheduler.getContext().putAll(this.schedulerContextMap);
    }

    // Register ApplicationContext in Scheduler context.
    if (this.applicationContextSchedulerContextKey != null) {
      if (this.applicationContext == null) {
        throw new IllegalStateException(
            "SchedulerFactoryBean needs to be set up in an ApplicationContext "
                + "to be able to handle an 'applicationContextSchedulerContextKey'");
      }
      scheduler
          .getContext()
          .put(this.applicationContextSchedulerContextKey, this.applicationContext);
    }
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

  @Override
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
}
