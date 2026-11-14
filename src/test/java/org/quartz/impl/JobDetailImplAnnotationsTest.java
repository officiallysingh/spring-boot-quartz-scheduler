package org.quartz.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.PersistJobDataAfterExecution;

/**
 * The job annotations are found on the job class, its superclasses and the interfaces it
 * implements, also as meta-annotations of the application's own annotations.
 *
 * @author Alex Snaps
 */
class JobDetailImplAnnotationsTest {

  private static JobDetail job(Class<? extends Job> jobClass) {
    return JobBuilder.newJob(jobClass).withIdentity(jobClass.getSimpleName()).build();
  }

  @Test
  void annotationsAreInheritedFromSuperclasses() {
    assertThat(job(BaseJob.class).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(job(BaseJob.class).isPersistJobDataAfterExecution()).isFalse();
    assertThat(job(ExtendedJob.class).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(job(ExtendedJob.class).isPersistJobDataAfterExecution()).isFalse();
    assertThat(job(ReallyExtendedJob.class).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(job(ReallyExtendedJob.class).isPersistJobDataAfterExecution()).isTrue();
  }

  @Test
  void annotationsAreFoundOnInterfaces() {
    assertThat(job(InterfaceAnnotatedJob.class).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(job(InterfaceAnnotatedJob.class).isPersistJobDataAfterExecution()).isFalse();
    assertThat(job(PlainJob.class).isConcurrentExecutionDisallowed()).isFalse();
  }

  @Test
  void annotationsAreFoundAsMetaAnnotations() {
    assertThat(job(StatefulAnnotatedJob.class).isConcurrentExecutionDisallowed()).isTrue();
    assertThat(job(StatefulAnnotatedJob.class).isPersistJobDataAfterExecution()).isTrue();
  }

  @DisallowConcurrentExecution
  @PersistJobDataAfterExecution
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
  @interface Stateful {}

  @Stateful
  static class StatefulAnnotatedJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  @DisallowConcurrentExecution
  static class BaseJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  static class ExtendedJob extends BaseJob {}

  @PersistJobDataAfterExecution
  static class ReallyExtendedJob extends ExtendedJob {}

  @DisallowConcurrentExecution
  interface SingleRunJob extends Job {}

  static class InterfaceAnnotatedJob implements SingleRunJob {
    @Override
    public void execute(JobExecutionContext context) {}
  }

  static class PlainJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {}
  }
}
