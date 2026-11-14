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

import org.jspecify.annotations.Nullable;
import org.quartz.Job;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

/**
 * The {@link JobFactory} of a {@link SchedulerFactoryBean}: creates each job instance as a bean of
 * the application context (constructor and field injection) when there is one, otherwise through
 * its no-argument constructor.
 *
 * <p>Applies scheduler context, job data map and trigger data map entries as bean property values;
 * an entry without a matching bean property is ignored. {@link QuartzJobBean} instances apply them
 * themselves.
 *
 * @author Juergen Hoeller
 * @see SchedulerFactoryBean#setJobFactory
 * @see QuartzJobBean
 */
public class SpringBeanJobFactory
    implements JobFactory, ApplicationContextAware, SchedulerContextAware {

  private @Nullable ApplicationContext applicationContext;

  private @Nullable SchedulerContext schedulerContext;

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  @Override
  public void setSchedulerContext(SchedulerContext schedulerContext) {
    this.schedulerContext = schedulerContext;
  }

  @Override
  public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException {
    try {
      return createJobInstance(bundle);
    } catch (Throwable ex) {
      throw new SchedulerException("Job instantiation failed", ex);
    }
  }

  /**
   * Create the job instance, populating it with property values taken from the scheduler context,
   * job data map and trigger data map.
   *
   * <p>Can be overridden to post-process the job instance.
   *
   * @param bundle the fired trigger and its job
   * @return the job instance
   * @throws Exception if job instantiation failed
   */
  protected Job createJobInstance(TriggerFiredBundle bundle) throws Exception {
    Class<? extends Job> jobClass = bundle.getJobDetail().getJobClass();
    Job job =
        (this.applicationContext != null
            ? this.applicationContext.getAutowireCapableBeanFactory().createBean(jobClass)
            : ReflectionUtils.accessibleConstructor(jobClass).newInstance());

    if (isEligibleForPropertyPopulation(job)) {
      BeanWrapper bw = PropertyAccessorFactory.forBeanPropertyAccess(job);
      MutablePropertyValues pvs = new MutablePropertyValues();
      if (this.schedulerContext != null) {
        pvs.addPropertyValues(this.schedulerContext);
      }
      pvs.addPropertyValues(bundle.getJobDetail().getJobDataMap());
      pvs.addPropertyValues(bundle.getTrigger().getJobDataMap());
      bw.setPropertyValues(pvs, true);
    }

    return job;
  }

  /**
   * Return whether the given job object is eligible for having its bean properties populated.
   *
   * <p>The default implementation ignores {@link QuartzJobBean} instances, which will inject bean
   * properties themselves.
   *
   * @param jobObject the job object to introspect
   * @see QuartzJobBean
   */
  protected boolean isEligibleForPropertyPopulation(Object jobObject) {
    return (!(jobObject instanceof QuartzJobBean));
  }
}
