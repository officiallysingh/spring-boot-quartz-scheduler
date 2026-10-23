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
package org.quartz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

/**
 * Marks a {@link Job} class whose {@link Job#execute(JobExecutionContext) execute} method runs in a
 * Spring-managed transaction (replaces Quartz's {@code @ExecuteInJTATransaction}).
 *
 * <p>The transaction covers only the job's {@code execute} call: listeners run outside it, vetoed
 * executions open none, and it is completed (committed or rolled back) before the job store is told
 * that the execution finished. It is rolled back when the job throws a {@link
 * JobExecutionException}, a {@link RuntimeException} or an {@link Error}, or an exception matching
 * {@link #rollbackFor()}; any other exception commits, as with Spring's {@code @Transactional}.
 *
 * <p>Honoured when the scheduler is created by {@code org.quartz.spring.SchedulerFactoryBean}
 * inside an application context (including the Spring Boot auto-configuration); ignored by a
 * scheduler built without Spring. With {@code spring.quartz.transaction.enabled=true} every job
 * runs in a transaction, and this annotation refines its attributes.
 *
 * @see PersistJobDataAfterExecution
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExecuteInTransaction {

  /**
   * Bean name of the {@code PlatformTransactionManager} to use; empty for the default one (the
   * unique or {@code @Primary} transaction manager of the application context).
   */
  String transactionManager() default "";

  /** The transaction propagation, as {@code @Transactional#propagation()}. */
  Propagation propagation() default Propagation.REQUIRED;

  /** The transaction isolation level, as {@code @Transactional#isolation()}. */
  Isolation isolation() default Isolation.DEFAULT;

  /**
   * The timeout in seconds, as {@code @Transactional#timeout()}; a job that is still running when
   * it expires is rolled back and fails with {@code TransactionTimedOutException}.
   */
  int timeout() default TransactionDefinition.TIMEOUT_DEFAULT;

  /** Whether the transaction is read-only, as {@code @Transactional#readOnly()}. */
  boolean readOnly() default false;

  /**
   * Further exception types that cause a rollback (in addition to {@link JobExecutionException},
   * {@link RuntimeException} and {@link Error}).
   */
  Class<? extends Throwable>[] rollbackFor() default {};
}
