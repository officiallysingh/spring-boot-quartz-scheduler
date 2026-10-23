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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.quartz.ExecuteInTransaction;
import org.quartz.JobDetail;
import org.quartz.JobExecutionException;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.core.JobRunShell;
import org.quartz.core.JobRunShellFactory;
import org.quartz.spi.JobStore;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * {@link JobRunShellFactory} that runs jobs in Spring-managed transactions (replaces Quartz's JTA
 * run shell factories).
 *
 * <p>A job runs in a transaction when its class is annotated with {@link
 * ExecuteInTransaction @ExecuteInTransaction}, or when {@link #setTransactionalByDefault(boolean)
 * transactionalByDefault} is set (then jobs without the annotation use the {@link
 * #setDefaultPropagation(Propagation) default propagation} and the default transaction manager).
 * Transaction managers are looked up in the bean factory: by the annotation's bean name, else the
 * unique or {@code @Primary} {@link PlatformTransactionManager}. Other jobs run without a
 * transaction, as with the standard factory.
 *
 * <p>When the {@linkplain #setJobStore(JobStore) job store} is a {@code
 * org.quartz.impl.mongodb.MongoJobStore} and a job's transaction manager is a {@code
 * MongoTransactionManager} on the same {@code MongoClient}, the data of a {@link
 * org.quartz.PersistJobDataAfterExecution @PersistJobDataAfterExecution} job is written in the
 * job's transaction: it is committed or rolled back together with the job's own writes.
 *
 * @see SpringTransactionJobRunShell
 */
public class SpringTransactionJobRunShellFactory implements JobRunShellFactory {

  private static final boolean MONGO_PRESENT =
      isPresent("org.springframework.data.mongodb.MongoTransactionManager")
          && isPresent("com.mongodb.client.MongoClient");

  private final ListableBeanFactory beanFactory;

  private boolean transactionalByDefault = false;

  private Propagation defaultPropagation = Propagation.REQUIRED;

  private @Nullable JobStore jobStore;

  private Clock clock = Clock.systemUTC();

  @SuppressWarnings("NullAway.Init")
  private Scheduler scheduler;

  private final Map<Class<?>, Optional<ExecuteInTransaction>> annotations =
      new ConcurrentHashMap<>();

  private volatile @Nullable PlatformTransactionManager defaultTransactionManager;

  public SpringTransactionJobRunShellFactory(ListableBeanFactory beanFactory) {
    this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory");
  }

  /**
   * Whether every job runs in a transaction, not only jobs annotated with {@link
   * ExecuteInTransaction}. Default is {@code false}.
   */
  public void setTransactionalByDefault(boolean transactionalByDefault) {
    this.transactionalByDefault = transactionalByDefault;
  }

  /**
   * The propagation of the transactions of jobs without {@link ExecuteInTransaction} when {@link
   * #setTransactionalByDefault(boolean) transactionalByDefault} is set. Default is {@link
   * Propagation#REQUIRED}.
   */
  public void setDefaultPropagation(Propagation defaultPropagation) {
    this.defaultPropagation = Objects.requireNonNull(defaultPropagation, "defaultPropagation");
  }

  /**
   * The scheduler's job store; needed only to write the job data of {@link
   * org.quartz.PersistJobDataAfterExecution} jobs in their transaction.
   */
  public void setJobStore(@Nullable JobStore jobStore) {
    this.jobStore = jobStore;
  }

  /**
   * The clock that transaction timeouts are measured with (the scheduler's clock). Default is
   * {@link Clock#systemUTC()}.
   */
  public void setClock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void initialize(Scheduler scheduler) throws SchedulerConfigException {
    this.scheduler = scheduler;
    if (this.transactionalByDefault && findDefaultTransactionManager() == null) {
      throw new SchedulerConfigException(
          "Every job is to run in a transaction, but there is no unique or "
              + "primary PlatformTransactionManager bean");
    }
  }

  @Override
  public JobRunShell createJobRunShell(TriggerFiredBundle bundle) throws SchedulerException {
    RuleBasedTransactionAttribute attribute = transactionAttribute(bundle.getJobDetail());
    if (attribute == null) {
      return new JobRunShell(this.scheduler, bundle);
    }
    return new SpringTransactionJobRunShell(this.scheduler, bundle, this, attribute);
  }

  /**
   * The transaction attribute for a job, or {@code null} if the job does not run in a transaction.
   * The attribute's qualifier is the transaction manager bean name, its name the job key.
   */
  @Nullable RuleBasedTransactionAttribute transactionAttribute(JobDetail jobDetail) {
    ExecuteInTransaction annotation =
        this.annotations
            .computeIfAbsent(
                jobDetail.getJobClass(),
                (jobClass) ->
                    Optional.ofNullable(
                        AnnotatedElementUtils.findMergedAnnotation(
                            jobClass, ExecuteInTransaction.class)))
            .orElse(null);
    if (annotation == null && !this.transactionalByDefault) {
      return null;
    }
    List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
    rollbackRules.add(new RollbackRuleAttribute(JobExecutionException.class));
    RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
    attribute.setName(jobDetail.getKey().toString());
    if (annotation != null) {
      attribute.setQualifier(annotation.transactionManager());
      attribute.setPropagationBehavior(annotation.propagation().value());
      attribute.setIsolationLevel(annotation.isolation().value());
      attribute.setTimeout(annotation.timeout());
      attribute.setReadOnly(annotation.readOnly());
      for (Class<? extends Throwable> type : annotation.rollbackFor()) {
        rollbackRules.add(new RollbackRuleAttribute(type));
      }
    } else {
      attribute.setPropagationBehavior(this.defaultPropagation.value());
    }
    attribute.setRollbackRules(rollbackRules);
    return attribute;
  }

  /**
   * The transaction manager named by the attribute's qualifier, else the default one.
   *
   * @throws IllegalStateException if there is no default transaction manager
   */
  PlatformTransactionManager transactionManager(RuleBasedTransactionAttribute attribute) {
    String name = attribute.getQualifier();
    if (StringUtils.hasText(name)) {
      return this.beanFactory.getBean(name, PlatformTransactionManager.class);
    }
    PlatformTransactionManager transactionManager = findDefaultTransactionManager();
    if (transactionManager == null) {
      throw new IllegalStateException(
          "Job '"
              + attribute.getName()
              + "' is to run in a transaction, but "
              + "there is no unique or primary PlatformTransactionManager bean; name one in "
              + "@ExecuteInTransaction(transactionManager = ...)");
    }
    return transactionManager;
  }

  private @Nullable PlatformTransactionManager findDefaultTransactionManager() {
    PlatformTransactionManager transactionManager = this.defaultTransactionManager;
    if (transactionManager == null) {
      transactionManager =
          this.beanFactory.getBeanProvider(PlatformTransactionManager.class).getIfUnique();
      this.defaultTransactionManager = transactionManager;
    }
    return transactionManager;
  }

  Clock clock() {
    return this.clock;
  }

  /** Whether job data written in a transaction of this manager reaches the job store. */
  boolean canJoinJobData(PlatformTransactionManager transactionManager) {
    return MONGO_PRESENT
        && this.jobStore != null
        && MongoJobDataTransactions.canJoin(transactionManager, this.jobStore);
  }

  boolean isJobDataTransactionActive(PlatformTransactionManager transactionManager) {
    return MongoJobDataTransactions.isTransactionActive(transactionManager);
  }

  void updateJobDataInTransaction(
      PlatformTransactionManager transactionManager, JobDetail jobDetail)
      throws JobPersistenceException {
    MongoJobDataTransactions.updateJobData(
        transactionManager, Objects.requireNonNull(this.jobStore, "jobStore"), jobDetail);
  }

  private static boolean isPresent(String className) {
    return ClassUtils.isPresent(
        className, SpringTransactionJobRunShellFactory.class.getClassLoader());
  }
}
