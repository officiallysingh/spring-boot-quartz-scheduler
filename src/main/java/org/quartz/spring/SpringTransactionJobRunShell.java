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

import java.time.Instant;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionException;
import org.quartz.JobPersistenceException;
import org.quartz.Scheduler;
import org.quartz.core.JobRunShell;
import org.quartz.impl.JobExecutionContextImpl;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link JobRunShell} that runs the job's {@code execute} call in a Spring-managed transaction
 * (replaces Quartz's {@code JTAJobRunShell}).
 *
 * <p>The transaction is opened only once the listeners have been notified and the execution was not
 * vetoed, and it is completed before the job store is told that the execution finished. It is
 * rolled back when the job throws an exception the transaction attribute rolls back for ({@link
 * JobExecutionException}, runtime exceptions, errors and the annotation's {@code rollbackFor}), or
 * when the transaction timeout expired while the job ran; then the job fails with a {@link
 * TransactionTimedOutException}. Failures to begin or commit the transaction are reported as the
 * job's failure.
 *
 * @see SpringTransactionJobRunShellFactory
 */
public class SpringTransactionJobRunShell extends JobRunShell {

  private final SpringTransactionJobRunShellFactory factory;

  private final RuleBasedTransactionAttribute attribute;

  public SpringTransactionJobRunShell(
      Scheduler scheduler,
      TriggerFiredBundle bundle,
      SpringTransactionJobRunShellFactory factory,
      RuleBasedTransactionAttribute attribute) {
    super(scheduler, bundle);
    this.factory = factory;
    this.attribute = attribute;
  }

  @Override
  protected void executeJob(Job job, JobExecutionContextImpl context) throws JobExecutionException {
    PlatformTransactionManager transactionManager = this.factory.transactionManager(this.attribute);
    JobDetail jobDetail = context.getJobDetail();
    boolean joinJobData =
        jobDetail.isPersistJobDataAfterExecution()
            && this.factory.canJoinJobData(transactionManager);
    Instant deadline =
        (this.attribute.getTimeout() > 0)
            ? this.factory.clock().instant().plusSeconds(this.attribute.getTimeout())
            : null;

    Throwable failure =
        new TransactionTemplate(transactionManager, this.attribute)
            .execute(
                (status) -> {
                  if (joinJobData
                      && this.factory.isJobDataTransactionActive(transactionManager)
                      && TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(
                        new JobDataSynchronization(transactionManager, jobDetail));
                  }
                  try {
                    job.execute(context);
                    if (deadline != null && this.factory.clock().instant().isAfter(deadline)) {
                      throw new TransactionTimedOutException(
                          "Transaction of job '"
                              + jobDetail.getKey()
                              + "' timed out: deadline was "
                              + deadline);
                    }
                    return null;
                  } catch (Throwable ex) {
                    if (this.attribute.rollbackOn(ex)) {
                      status.setRollbackOnly();
                    }
                    return ex;
                  }
                });

    if (failure instanceof JobExecutionException jobExecutionException) {
      throw jobExecutionException;
    }
    if (failure != null) {
      throw SpringTransactionJobRunShell.<RuntimeException>sneakyThrow(failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T sneakyThrow(Throwable ex) throws T {
    throw (T) ex;
  }

  /**
   * Writes the job data in the transaction just before it commits, and discards the job's changes
   * to it when the transaction does not commit. Either way the data is no longer dirty, so the job
   * store does not write it again after the execution.
   */
  private final class JobDataSynchronization implements TransactionSynchronization {

    private final PlatformTransactionManager transactionManager;

    private final JobDetail jobDetail;

    private final JobDataMap snapshot;

    JobDataSynchronization(PlatformTransactionManager transactionManager, JobDetail jobDetail) {
      this.transactionManager = transactionManager;
      this.jobDetail = jobDetail;
      this.snapshot = new JobDataMap(jobDetail.getJobDataMap().getWrappedMap());
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      try {
        SpringTransactionJobRunShell.this.factory.updateJobDataInTransaction(
            this.transactionManager, this.jobDetail);
      } catch (JobPersistenceException ex) {
        throw new TransactionSystemException(
            "Couldn't write the job data of job '" + this.jobDetail.getKey() + "'", ex);
      }
    }

    @Override
    public void afterCompletion(int status) {
      JobDataMap jobDataMap = this.jobDetail.getJobDataMap();
      if (status != STATUS_COMMITTED) {
        jobDataMap.clear();
        jobDataMap.putAll(this.snapshot.getWrappedMap());
      }
      jobDataMap.clearDirtyFlag();
    }
  }
}
