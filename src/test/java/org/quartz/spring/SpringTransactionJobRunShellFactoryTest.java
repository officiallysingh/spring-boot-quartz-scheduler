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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;
import org.quartz.ExecuteInTransaction;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerConfigException;
import org.quartz.support.RecordingTransactionManager;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;

/**
 * Transaction attributes and transaction manager lookup of
 * {@link SpringTransactionJobRunShellFactory}; execution itself is covered by the
 * {@code SpringTransaction*IT} tests.
 */
class SpringTransactionJobRunShellFactoryTest {

    private final GenericApplicationContext context = new GenericApplicationContext();

    private final SpringTransactionJobRunShellFactory factory = new SpringTransactionJobRunShellFactory(context);

    @Test
    void annotationAttributesAreApplied() {
        RuleBasedTransactionAttribute attribute = factory.transactionAttribute(job(FullyAnnotatedJob.class));

        assertThat(attribute.getName()).isEqualTo("DEFAULT.job");
        assertThat(attribute.getQualifier()).isEqualTo("auditTransactionManager");
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(attribute.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(attribute.getTimeout()).isEqualTo(7);
        assertThat(attribute.isReadOnly()).isTrue();
    }

    @Test
    void annotationDefaultsMatchTransactional() {
        RuleBasedTransactionAttribute attribute = factory.transactionAttribute(job(AnnotatedJob.class));

        assertThat(attribute.getQualifier()).isEmpty();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(attribute.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(attribute.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        assertThat(attribute.isReadOnly()).isFalse();
    }

    @Test
    void jobsWithoutTheAnnotationRunWithoutATransactionByDefault() {
        assertThat(factory.transactionAttribute(job(PlainJob.class))).isNull();
    }

    @Test
    void transactionalByDefaultWrapsEveryJobWithTheDefaultPropagation() {
        factory.setTransactionalByDefault(true);
        factory.setDefaultPropagation(Propagation.REQUIRES_NEW);

        RuleBasedTransactionAttribute attribute = factory.transactionAttribute(job(PlainJob.class));
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(attribute.getQualifier()).isNull();
        // the annotation still wins for annotated jobs
        assertThat(factory.transactionAttribute(job(AnnotatedJob.class)).getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    void annotationIsInheritedAndFoundAsMetaAnnotation() {
        assertThat(factory.transactionAttribute(job(InheritingJob.class))).isNotNull();
        assertThat(factory.transactionAttribute(job(MetaAnnotatedJob.class)).getQualifier()).isEqualTo("meta");
    }

    @Test
    void rollbackRules() {
        RuleBasedTransactionAttribute plain = factory.transactionAttribute(job(AnnotatedJob.class));
        assertThat(plain.rollbackOn(new JobExecutionException("failed"))).isTrue();
        assertThat(plain.rollbackOn(new IllegalStateException())).isTrue();
        assertThat(plain.rollbackOn(new AssertionError())).isTrue();
        assertThat(plain.rollbackOn(new IOException())).isFalse();

        RuleBasedTransactionAttribute withRollbackFor = factory.transactionAttribute(job(FullyAnnotatedJob.class));
        assertThat(withRollbackFor.rollbackOn(new IOException())).isTrue();
        assertThat(withRollbackFor.rollbackOn(new JobExecutionException("failed"))).isTrue();
    }

    @Test
    void transactionManagerIsLookedUpByNameElseTheDefault() {
        RecordingTransactionManager primary = new RecordingTransactionManager();
        RecordingTransactionManager audit = new RecordingTransactionManager();
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> primary,
                (definition) -> definition.setPrimary(true));
        context.registerBean("auditTransactionManager", PlatformTransactionManager.class, () -> audit);
        context.refresh();

        assertThat(factory.transactionManager(factory.transactionAttribute(job(FullyAnnotatedJob.class))))
            .isSameAs(audit);
        assertThat(factory.transactionManager(factory.transactionAttribute(job(AnnotatedJob.class))))
            .isSameAs(primary);
        assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
            .isThrownBy(() -> factory.transactionManager(factory.transactionAttribute(job(MetaAnnotatedJob.class))));
    }

    @Test
    void missingDefaultTransactionManagerFailsTheJob() {
        context.refresh();

        assertThatExceptionOfType(IllegalStateException.class)
            .isThrownBy(() -> factory.transactionManager(factory.transactionAttribute(job(AnnotatedJob.class))))
            .withMessageContaining("no unique or primary PlatformTransactionManager");
    }

    @Test
    void transactionalByDefaultWithoutTransactionManagerFailsFast() throws Exception {
        context.refresh();
        factory.initialize(mock(Scheduler.class));

        factory.setTransactionalByDefault(true);
        assertThatExceptionOfType(SchedulerConfigException.class)
            .isThrownBy(() -> factory.initialize(mock(Scheduler.class)))
            .withMessageContaining("PlatformTransactionManager");
    }

    private static JobDetail job(Class<? extends Job> jobClass) {
        return JobBuilder.newJob(jobClass).withIdentity("job").build();
    }

    public static class PlainJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
        }
    }

    @ExecuteInTransaction
    public static class AnnotatedJob extends PlainJob {
    }

    public static class InheritingJob extends AnnotatedJob {
    }

    @ExecuteInTransaction(transactionManager = "auditTransactionManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.SERIALIZABLE, timeout = 7, readOnly = true, rollbackFor = IOException.class)
    public static class FullyAnnotatedJob extends PlainJob {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ExecuteInTransaction(transactionManager = "meta")
    @interface MetaTransactional {
    }

    @MetaTransactional
    public static class MetaAnnotatedJob extends PlainJob {
    }
}
