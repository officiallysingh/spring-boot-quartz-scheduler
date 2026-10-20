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

import com.mongodb.client.MongoClient;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.mongodb.MongoJobStoreSettings;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.impl.redis.RedisJobStoreSettings;
import org.quartz.spi.JobStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link JobStoreFactory} registrations for the persistent stores. Each is only active when
 * its client library and connection bean are present, so neither library is required.
 */
final class JobStoreConfigurations {

	private JobStoreConfigurations() {
	}

	/** What a store type needs, for the startup error message. */
	static String requirement(JobStoreType type) {
		return switch (type) {
			case MONGODB -> "MongoClient bean (add spring-boot-starter-data-mongodb and configure spring.mongodb.*)";
			case REDIS -> "RedisConnectionFactory bean (add spring-boot-starter-data-redis and configure spring.data.redis.*)";
		};
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ MongoClient.class, MongoDatabaseFactory.class })
	@ConditionalOnBean(MongoClient.class)
	static class MongoJobStoreConfiguration {

		@Bean
		JobStoreFactory quartzMongoJobStoreFactory(MongoClient mongoClient,
				ObjectProvider<MongoDatabaseFactory> databaseFactory) {
			return new JobStoreFactory() {

				@Override
				public JobStoreType type() {
					return JobStoreType.MONGODB;
				}

				@Override
				public JobStore create(QuartzProperties properties) {
					QuartzProperties.JobStore.Mongodb mongodb = properties.jobStore().mongodb();
					String database = mongodb.database();
					if (database == null) {
						MongoDatabaseFactory factory = databaseFactory.getIfAvailable();
						if (factory == null) {
							throw new IllegalStateException("Set 'spring.quartz.job-store.mongodb.database' or "
									+ "configure Spring Data MongoDB so the database is known");
						}
						database = factory.getMongoDatabase().getName();
					}
					return new MongoJobStore(mongoClient, database, properties.jobStore().toSettings(),
							new MongoJobStoreSettings(mongodb.collectionPrefix(), mongodb.lockTimeout()));
				}

			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(RedisConnectionFactory.class)
	@ConditionalOnBean(RedisConnectionFactory.class)
	static class RedisJobStoreConfiguration {

		@Bean
		JobStoreFactory quartzRedisJobStoreFactory(RedisConnectionFactory connectionFactory) {
			return new JobStoreFactory() {

				@Override
				public JobStoreType type() {
					return JobStoreType.REDIS;
				}

				@Override
				public JobStore create(QuartzProperties properties) {
					QuartzProperties.JobStore.Redis redis = properties.jobStore().redis();
					return new RedisJobStore(new StringRedisTemplate(connectionFactory),
							properties.jobStore().toSettings(),
							new RedisJobStoreSettings(redis.keyPrefix(), redis.lockTimeout()));
				}

			};
		}

	}

}
