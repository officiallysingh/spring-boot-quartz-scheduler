/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
 *
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
 *
 */

package org.quartz.impl;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.TriggerListener;
import org.quartz.core.JobRunShellFactory;
import org.quartz.core.QuartzScheduler;
import org.quartz.core.QuartzSchedulerResources;
import org.quartz.impl.config.JobStoreSettings;
import org.quartz.impl.config.QuartzSettings;
import org.quartz.impl.config.SchedulerSettings;
import org.quartz.impl.config.ThreadPoolSettings;
import org.quartz.impl.matchers.EverythingMatcher;
import org.quartz.simpl.DefaultClassLoadHelper;
import org.quartz.simpl.SimpleInstanceIdGenerator;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.simpl.VirtualThreadPool;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.InstanceIdGenerator;
import org.quartz.spi.JobFactory;
import org.quartz.spi.JobStore;
import org.quartz.spi.SchedulerPlugin;
import org.quartz.spi.ThreadExecutor;
import org.quartz.spi.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of <code>{@link org.quartz.SchedulerFactory}</code> that creates a <code>
 * QuartzScheduler</code> from typed settings ({@link QuartzSettings}) and collaborator instances.
 *
 * <pre>
 * Scheduler scheduler = StdSchedulerFactory.builder()
 *         .scheduler(SchedulerSettings.builder().name("MyScheduler").build())
 *         .threadPool(ThreadPoolSettings.builder().threadCount(4).build())
 *         .jobStore(new MongoJobStore(mongoClient, "app", JobStoreSettings.defaults(), MongoJobStoreSettings.defaults()))
 *         .plugin("jobHistory", new LoggingJobHistoryPlugin())
 *         .build()
 *         .getScheduler();
 * </pre>
 *
 * <p>A {@link JobStore} must be supplied ({@code MongoJobStore} or {@code RedisJobStore}): there is
 * no default store, and {@link #getScheduler()} fails without one. {@link ThreadPoolSettings}
 * configure the {@link SimpleThreadPool} this factory creates when no {@link ThreadPool} instance
 * is supplied; a supplied instance is used as it is. The factory creates a fresh default thread
 * pool each time it instantiates a scheduler; supplied instances, plugins and listeners are reused.
 *
 * <p>Schedulers are bound by name in the {@link SchedulerRepository}: {@link #getScheduler()}
 * returns the bound scheduler while it is not shut down, and creates a new one otherwise.
 *
 * @author James House
 * @author Anthony Eden
 * @author Mohammad Rezaei
 */
public class StdSchedulerFactory implements SchedulerFactory {

  private final QuartzSettings settings;

  private final @Nullable ThreadPool threadPool;

  private final @Nullable JobStore jobStore;

  private final @Nullable JobFactory jobFactory;

  private final @Nullable InstanceIdGenerator instanceIdGenerator;

  private final @Nullable ClassLoadHelper classLoadHelper;

  private final @Nullable ThreadExecutor threadExecutor;

  private final Map<String, SchedulerPlugin> plugins;

  private final List<JobListener> jobListeners;

  private final List<TriggerListener> triggerListeners;

  private final Clock clock;

  private final @Nullable JobRunShellFactory jobRunShellFactory;

  private final Logger log = LoggerFactory.getLogger(getClass());

  private StdSchedulerFactory(Builder builder) {
    this.settings = new QuartzSettings(builder.scheduler, builder.threadPoolSettings);
    this.threadPool = builder.threadPool;
    this.jobStore = builder.jobStore;
    this.jobFactory = builder.jobFactory;
    this.instanceIdGenerator = builder.instanceIdGenerator;
    this.classLoadHelper = builder.classLoadHelper;
    this.threadExecutor = builder.threadExecutor;
    this.plugins = new LinkedHashMap<>(builder.plugins);
    this.jobListeners = List.copyOf(builder.jobListeners);
    this.triggerListeners = List.copyOf(builder.triggerListeners);
    this.clock = builder.clock;
    this.jobRunShellFactory = builder.jobRunShellFactory;
  }

  /**
   * A builder for a factory. A job store is required: {@code builder().jobStore(new
   * MongoJobStore(...))}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The factory's logger.
   *
   * @return the logger
   */
  public Logger getLog() {
    return log;
  }

  /**
   * The settings schedulers are created with.
   *
   * @return the settings
   */
  public QuartzSettings getSettings() {
    return settings;
  }

  private Scheduler instantiate() throws SchedulerException {
    SchedulerSettings sched = settings.scheduler();
    String schedName = sched.name();
    String schedInstId = sched.instanceId();
    boolean threadsInheritInitializersClassLoader =
        sched.threadsInheritContextClassLoaderOfInitializer();

    SchedulerRepository schedRep = SchedulerRepository.getInstance();

    ClassLoadHelper loadHelper =
        classLoadHelper != null ? classLoadHelper : new DefaultClassLoadHelper();
    loadHelper.initialize();

    ThreadPool tp = threadPool != null ? threadPool : createThreadPool(settings.threadPool());
    if (jobStore == null) {
      throw new SchedulerConfigException(
          "No JobStore configured: supply one with "
              + "StdSchedulerFactory.Builder.jobStore(JobStore) (MongoJobStore or RedisJobStore)");
    }
    JobStore js = jobStore;
    SchedulerDetailsSetter.setDetails(js, schedName, schedInstId);

    ThreadExecutor executor = threadExecutor != null ? threadExecutor : new DefaultThreadExecutor();

    QuartzScheduler qs = null;
    boolean tpInited = false;
    boolean qsInited = false;

    // Fire everything up
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    try {
      JobRunShellFactory jrsf =
          jobRunShellFactory != null ? jobRunShellFactory : new StdJobRunShellFactory();

      if (sched.instanceIdAuto()) {
        try {
          schedInstId = SchedulerSettings.DEFAULT_INSTANCE_ID;
          if (js.isClustered()) {
            InstanceIdGenerator generator =
                instanceIdGenerator != null ? instanceIdGenerator : new SimpleInstanceIdGenerator();
            schedInstId = generator.generateInstanceId();
          }
        } catch (Exception e) {
          getLog().error("Couldn't generate instance Id!", e);
          throw new IllegalStateException("Cannot run without an instance id.");
        }
      }

      QuartzSchedulerResources rsrcs = new QuartzSchedulerResources();
      rsrcs.setName(schedName);
      rsrcs.setThreadName(sched.effectiveThreadName());
      rsrcs.setInstanceId(schedInstId);
      rsrcs.setJobRunShellFactory(jrsf);
      rsrcs.setMakeSchedulerThreadDaemon(sched.makeSchedulerThreadDaemon());
      rsrcs.setThreadsInheritInitializersClassLoadContext(threadsInheritInitializersClassLoader);
      rsrcs.setBatchTimeWindow(sched.batchTriggerAcquisitionFireAheadTimeWindow());
      rsrcs.setMaxBatchSize(sched.batchTriggerAcquisitionMaxCount());
      rsrcs.setInterruptJobsOnShutdown(sched.interruptJobsOnShutdown());
      rsrcs.setInterruptJobsOnShutdownWithWait(sched.interruptJobsOnShutdownWithWait());
      rsrcs.setClock(clock);

      SchedulerDetailsSetter.setDetails(tp, schedName, schedInstId);

      rsrcs.setThreadExecutor(executor);
      executor.initialize();

      rsrcs.setThreadPool(tp);
      if (tp instanceof SimpleThreadPool && threadsInheritInitializersClassLoader) {
        ((SimpleThreadPool) tp).setThreadsInheritContextClassLoaderOfInitializingThread(true);
      }
      tp.initialize();
      tpInited = true;

      rsrcs.setJobStore(js);

      // add plugins
      for (SchedulerPlugin plugin : plugins.values()) {
        rsrcs.addSchedulerPlugin(plugin);
      }

      qs = new QuartzScheduler(rsrcs, sched.idleWaitTime());
      qsInited = true;

      // Create Scheduler ref...
      Scheduler scheduler = instantiate(rsrcs, qs);

      // set job factory if specified
      if (jobFactory != null) {
        qs.setJobFactory(jobFactory);
      }

      // Initialize plugins now that we have a Scheduler instance.
      for (Map.Entry<String, SchedulerPlugin> plugin : plugins.entrySet()) {
        plugin.getValue().initialize(plugin.getKey(), scheduler, loadHelper);
      }

      // add listeners
      for (JobListener jobListener : jobListeners) {
        qs.getListenerManager().addJobListener(jobListener, EverythingMatcher.allJobs());
      }
      for (TriggerListener triggerListener : triggerListeners) {
        qs.getListenerManager()
            .addTriggerListener(triggerListener, EverythingMatcher.allTriggers());
      }

      // set scheduler context data...
      scheduler.getContext().putAll(sched.context());

      // fire up job store, and runshell factory

      js.setInstanceId(schedInstId);
      js.setInstanceName(schedName);
      js.setThreadPoolSize(tp.getPoolSize());
      js.initialize(loadHelper, qs.getSchedulerSignaler());

      jrsf.initialize(scheduler);

      qs.initialize();

      getLog().info("Quartz scheduler '{}' initialized", scheduler.getSchedulerName());

      getLog().info("Quartz scheduler version: {}", qs.getVersion());

      // prevents the repository from being garbage collected
      qs.addNoGCObject(schedRep);

      schedRep.bind(scheduler);
      return scheduler;
    } catch (SchedulerException | Error | RuntimeException e) {
      shutdownFromInstantiateException(tp, qs, tpInited, qsInited);
      throw e;
    }
  }

  private static ThreadPool createThreadPool(ThreadPoolSettings tps) {
    if (tps.virtualThreads()) {
      VirtualThreadPool vtp = new VirtualThreadPool(tps.threadCount());
      vtp.setThreadNamePrefix(tps.threadNamePrefix());
      vtp.setThreadsInheritContextClassLoaderOfInitializingThread(
          tps.threadsInheritContextClassLoaderOfInitializingThread());
      return vtp;
    }
    SimpleThreadPool tp = new SimpleThreadPool(tps.threadCount(), tps.threadPriority());
    if (tps.threadNamePrefix() != null) {
      tp.setThreadNamePrefix(tps.threadNamePrefix());
    }
    tp.setMakeThreadsDaemons(tps.daemon());
    tp.setThreadsInheritContextClassLoaderOfInitializingThread(
        tps.threadsInheritContextClassLoaderOfInitializingThread());
    tp.setThreadsInheritGroupOfInitializingThread(tps.threadsInheritGroupOfInitializingThread());
    return tp;
  }

  private void shutdownFromInstantiateException(
      ThreadPool tp, @Nullable QuartzScheduler qs, boolean tpInited, boolean qsInited) {
    try {
      if (qsInited && qs != null) qs.shutdown(false);
      else if (tpInited) tp.shutdown(false);
    } catch (Exception e) {
      getLog().error("Got another exception while shutting down after instantiation exception", e);
    }
  }

  /**
   * Wrap the created scheduler in the {@link Scheduler} handed to applications; subclasses may
   * return their own.
   *
   * @param rsrcs the scheduler's resources
   * @param qs the scheduler
   * @return the application-facing scheduler
   */
  protected Scheduler instantiate(QuartzSchedulerResources rsrcs, QuartzScheduler qs) {

    return new StdScheduler(qs);
  }

  /**
   * Returns a handle to the Scheduler produced by this factory, creating it if no scheduler with
   * the configured name is bound, or the bound one has been shut down.
   */
  public Scheduler getScheduler() throws SchedulerException {
    SchedulerRepository schedRep = SchedulerRepository.getInstance();
    String schedName = settings.scheduler().name();

    Scheduler sched = schedRep.lookup(schedName);

    if (sched != null) {
      if (sched.isShutdown()) {
        schedRep.remove(schedName);
      } else {
        return sched;
      }
    }

    return instantiate();
  }

  /**
   * Returns a handle to the Scheduler with the given name, if it exists (if it has already been
   * instantiated).
   */
  public @Nullable Scheduler getScheduler(String schedName) throws SchedulerException {
    return SchedulerRepository.getInstance().lookup(schedName);
  }

  /** Returns a handle to all known Schedulers (made by any StdSchedulerFactory instance.). */
  public Collection<Scheduler> getAllSchedulers() throws SchedulerException {
    return SchedulerRepository.getInstance().lookupAll();
  }

  /**
   * Builds a {@link StdSchedulerFactory}. Everything not set falls back to its default: {@link
   * QuartzSettings#defaults()}, a {@link SimpleThreadPool} configured from the settings (a job
   * store must be set), {@link DefaultClassLoadHelper}, {@link SimpleInstanceIdGenerator}, {@link
   * DefaultThreadExecutor}, the scheduler's default job factory, {@link StdJobRunShellFactory} and
   * {@link Clock#systemUTC()}.
   */
  public static final class Builder {
    private SchedulerSettings scheduler = SchedulerSettings.defaults();
    private ThreadPoolSettings threadPoolSettings = ThreadPoolSettings.defaults();
    private @Nullable ThreadPool threadPool;
    private @Nullable JobStore jobStore;
    private @Nullable JobFactory jobFactory;
    private @Nullable InstanceIdGenerator instanceIdGenerator;
    private @Nullable ClassLoadHelper classLoadHelper;
    private @Nullable ThreadExecutor threadExecutor;
    private final Map<String, SchedulerPlugin> plugins = new LinkedHashMap<>();
    private final List<JobListener> jobListeners = new ArrayList<>();
    private final List<TriggerListener> triggerListeners = new ArrayList<>();
    private Clock clock = Clock.systemUTC();
    private @Nullable JobRunShellFactory jobRunShellFactory;

    private Builder() {}

    /**
     * Set the scheduler and thread pool settings.
     *
     * @param settings the settings
     * @return this builder
     */
    public Builder settings(QuartzSettings settings) {
      Objects.requireNonNull(settings, "settings");
      this.scheduler = settings.scheduler();
      this.threadPoolSettings = settings.threadPool();
      return this;
    }

    /**
     * Set the scheduler settings; default {@link SchedulerSettings#defaults()}.
     *
     * @param scheduler the settings
     * @return this builder
     */
    public Builder scheduler(SchedulerSettings scheduler) {
      this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
      return this;
    }

    /**
     * Set the settings of the thread pool the factory creates when no {@link ThreadPool} instance
     * is given; default {@link ThreadPoolSettings#defaults()}.
     *
     * @param threadPool the settings
     * @return this builder
     */
    public Builder threadPool(ThreadPoolSettings threadPool) {
      this.threadPoolSettings = Objects.requireNonNull(threadPool, "threadPool");
      return this;
    }

    /**
     * Use this thread pool instead of creating a {@link SimpleThreadPool} from the {@link
     * ThreadPoolSettings}.
     */
    public Builder threadPool(ThreadPool threadPool) {
      this.threadPool = threadPool;
      return this;
    }

    /**
     * The job store (required: there is no default store). It is configured by its own constructor,
     * with its own {@link JobStoreSettings}.
     */
    public Builder jobStore(JobStore jobStore) {
      this.jobStore = jobStore;
      return this;
    }

    /**
     * Set the factory creating job instances; default {@link
     * org.quartz.simpl.PropertySettingJobFactory}.
     *
     * @param jobFactory the job factory
     * @return this builder
     */
    public Builder jobFactory(JobFactory jobFactory) {
      this.jobFactory = jobFactory;
      return this;
    }

    /**
     * The generator used when {@link SchedulerSettings#instanceIdAuto()} is set and the job store
     * is clustered.
     */
    public Builder instanceIdGenerator(InstanceIdGenerator instanceIdGenerator) {
      this.instanceIdGenerator = instanceIdGenerator;
      return this;
    }

    /**
     * Set how job classes are loaded; default {@link DefaultClassLoadHelper}.
     *
     * @param classLoadHelper the class load helper
     * @return this builder
     */
    public Builder classLoadHelper(ClassLoadHelper classLoadHelper) {
      this.classLoadHelper = classLoadHelper;
      return this;
    }

    /**
     * Set how the scheduler thread is started; default {@link DefaultThreadExecutor}.
     *
     * @param threadExecutor the thread executor
     * @return this builder
     */
    public Builder threadExecutor(ThreadExecutor threadExecutor) {
      this.threadExecutor = threadExecutor;
      return this;
    }

    /**
     * Adds a plugin; {@code name} is passed to {@link SchedulerPlugin#initialize(String, Scheduler,
     * ClassLoadHelper)}. Plugins are initialized in the order they were added.
     */
    public Builder plugin(String name, SchedulerPlugin plugin) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(plugin, "plugin");
      if (plugins.putIfAbsent(name, plugin) != null) {
        throw new IllegalArgumentException("Duplicate plugin name: " + name);
      }
      return this;
    }

    /** Adds a job listener for all jobs. */
    public Builder jobListener(JobListener listener) {
      jobListeners.add(Objects.requireNonNull(listener, "listener"));
      return this;
    }

    /** Adds a trigger listener for all triggers. */
    public Builder triggerListener(TriggerListener listener) {
      triggerListeners.add(Objects.requireNonNull(listener, "listener"));
      return this;
    }

    /** The source of "now" for the scheduler; defaults to {@link Clock#systemUTC()}. */
    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * The factory of the shells that run each job; defaults to {@link StdJobRunShellFactory}. A
     * factory instance belongs to one scheduler.
     */
    public Builder jobRunShellFactory(JobRunShellFactory jobRunShellFactory) {
      this.jobRunShellFactory = jobRunShellFactory;
      return this;
    }

    /**
     * The factory.
     *
     * @return the factory
     */
    public StdSchedulerFactory build() {
      return new StdSchedulerFactory(this);
    }
  }
}
