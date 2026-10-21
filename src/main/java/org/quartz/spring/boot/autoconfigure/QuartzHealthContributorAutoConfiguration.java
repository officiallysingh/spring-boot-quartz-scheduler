/*
 * Copyright 2012-present the original author or authors.
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
package org.quartz.spring.boot.autoconfigure;

import org.quartz.Scheduler;
import org.quartz.spring.SchedulerFactoryBean;
import org.quartz.spring.boot.actuate.health.QuartzHealthIndicator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for {@link QuartzHealthIndicator}
 * (contributor {@code quartz}; {@code management.health.quartz.enabled} switches it off).
 */
@AutoConfiguration(after = QuartzAutoConfiguration.class)
@ConditionalOnClass({ Scheduler.class, HealthIndicator.class, ConditionalOnEnabledHealthIndicator.class })
@ConditionalOnBean(Scheduler.class)
@ConditionalOnEnabledHealthIndicator("quartz")
public final class QuartzHealthContributorAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(name = "quartzHealthIndicator")
	QuartzHealthIndicator quartzHealthIndicator(Scheduler scheduler,
			ObjectProvider<SchedulerFactoryBean> schedulerFactoryBean) {
		SchedulerFactoryBean factoryBean = schedulerFactoryBean.getIfUnique();
		return new QuartzHealthIndicator(scheduler, (factoryBean != null) ? factoryBean.getJobStore() : null);
	}

}
