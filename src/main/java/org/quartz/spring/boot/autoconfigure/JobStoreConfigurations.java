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
import org.jspecify.annotations.Nullable;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.impl.mongodb.MongoJobStoreSettings;
import org.quartz.impl.redis.RedisJobStore;
import org.quartz.impl.redis.RedisJobStoreSettings;
import org.quartz.spi.JobStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.ClassUtils;

/**
 * {@link JobStoreFactory} registrations for the persistent stores. Each is only active when its
 * client library and connection bean are present, so neither library is required.
 */
final class JobStoreConfigurations {

  private JobStoreConfigurations() {}

  /** What a store type needs, for the startup error message. */
  static String requirement(JobStoreType type) {
    return switch (type) {
      case MONGODB ->
          "MongoClient bean (add spring-boot-starter-data-mongodb and configure spring.mongodb.*)";
      case REDIS ->
          "RedisConnectionFactory bean (add spring-boot-starter-data-redis and configure spring.data.redis.*)";
    };
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({MongoClient.class, MongoDatabaseFactory.class})
  @ConditionalOnBean(MongoClient.class)
  static class MongoJobStoreConfiguration {

    @Bean
    JobStoreFactory quartzMongoJobStoreFactory(
        MongoClient mongoClient,
        ObjectProvider<MongoDatabaseFactory> databaseFactory,
        ListableBeanFactory beanFactory) {
      return new JobStoreFactory() {

        @Override
        public JobStoreType type() {
          return JobStoreType.MONGODB;
        }

        @Override
        public JobStore create(QuartzProperties properties) {
          QuartzProperties.JobStore.Mongodb mongodb = properties.jobStore().mongodb();
          String database = database(databaseFactory, beanFactory);
          return new MongoJobStore(
              mongoClient,
              database,
              properties.jobStore().toSettings(),
              new MongoJobStoreSettings(mongodb.collectionPrefix(), mongodb.lockTimeout()));
        }
      };
    }

    /**
     * The application's MongoDB database, resolved as Spring Boot does: the {@link
     * MongoDatabaseFactory}'s when there is one (it may be the application's own), otherwise {@code
     * spring.mongodb.database}, otherwise the database of the connection string ({@code
     * spring.mongodb.uri} or a service connection). The last two need Spring Boot's MongoDB module
     * ({@code spring-boot-mongodb}), which is optional: an application with its own {@code
     * MongoClient} can define a {@code MongoDatabaseFactory} instead.
     */
    private static String database(
        ObjectProvider<MongoDatabaseFactory> databaseFactory, ListableBeanFactory beanFactory) {
      MongoDatabaseFactory factory = databaseFactory.getIfAvailable();
      if (factory != null) {
        return factory.getMongoDatabase().getName();
      }
      ClassLoader classLoader =
          (beanFactory instanceof ConfigurableBeanFactory configurable
                  && configurable.getBeanClassLoader() != null)
              ? configurable.getBeanClassLoader()
              : JobStoreConfigurations.class.getClassLoader();
      String database =
          ClassUtils.isPresent(SPRING_BOOT_MONGO_PROPERTIES, classLoader)
              ? SpringBootMongoDatabase.of(beanFactory)
              : null;
      if (database == null || database.isBlank()) {
        throw new IllegalStateException(
            "The MongoDB job store needs a database: set 'spring.mongodb.database' or include"
                + " the database in 'spring.mongodb.uri', or define a MongoDatabaseFactory bean");
      }
      return database;
    }

    private static final String SPRING_BOOT_MONGO_PROPERTIES =
        "org.springframework.boot.mongodb.autoconfigure.MongoProperties";

    /** Loaded only when {@code spring-boot-mongodb} is on the class path. */
    private static final class SpringBootMongoDatabase {

      static @Nullable String of(ListableBeanFactory beanFactory) {
        MongoProperties properties =
            beanFactory.getBeanProvider(MongoProperties.class).getIfAvailable();
        String database = (properties != null) ? properties.getDatabase() : null;
        if (database == null) {
          MongoConnectionDetails details =
              beanFactory.getBeanProvider(MongoConnectionDetails.class).getIfAvailable();
          database = (details != null) ? details.getConnectionString().getDatabase() : null;
        }
        return database;
      }
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
          return new RedisJobStore(
              new StringRedisTemplate(connectionFactory),
              properties.jobStore().toSettings(),
              new RedisJobStoreSettings(redis.keyPrefix(), redis.lockTimeout()));
        }
      };
    }
  }
}
