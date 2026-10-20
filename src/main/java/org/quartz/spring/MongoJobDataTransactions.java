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

import org.quartz.JobDetail;
import org.quartz.JobPersistenceException;
import org.quartz.impl.mongodb.MongoJobStore;
import org.quartz.spi.JobStore;

import org.springframework.data.mongodb.MongoClusterCapable;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoDatabaseUtils;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.SessionSynchronization;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Lets the job data of a {@link org.quartz.PersistJobDataAfterExecution} job be written in
 * the job's {@link MongoTransactionManager} transaction when the job store is a
 * {@link MongoJobStore} on the same {@code MongoClient}. Only loaded when Spring Data
 * MongoDB is on the classpath.
 */
final class MongoJobDataTransactions {

	private MongoJobDataTransactions() {
	}

	/**
	 * Whether {@code transactionManager} is a {@link MongoTransactionManager} whose
	 * transactions run on the client of {@code jobStore}, a {@link MongoJobStore}.
	 */
	static boolean canJoin(PlatformTransactionManager transactionManager, JobStore jobStore) {
		return transactionManager instanceof MongoTransactionManager mongoTransactionManager
				&& jobStore instanceof MongoJobStore mongoJobStore
				&& mongoTransactionManager.getDatabaseFactory() instanceof MongoClusterCapable clusterCapable
				&& mongoJobStore.usesClient(clusterCapable.getMongoCluster());
	}

	/** Whether a MongoDB transaction of the manager is active on the current thread. */
	static boolean isTransactionActive(PlatformTransactionManager transactionManager) {
		return MongoDatabaseUtils.isTransactionActive(databaseFactory(transactionManager));
	}

	/** Writes the job's data through the session of the current transaction. */
	static void updateJobData(PlatformTransactionManager transactionManager, JobStore jobStore, JobDetail jobDetail)
			throws JobPersistenceException {
		MongoJobStore mongoJobStore = (MongoJobStore) jobStore;
		mongoJobStore.updateJobData(MongoDatabaseUtils.getDatabase(mongoJobStore.getDatabaseName(),
				databaseFactory(transactionManager), SessionSynchronization.ON_ACTUAL_TRANSACTION), jobDetail);
	}

	private static MongoDatabaseFactory databaseFactory(PlatformTransactionManager transactionManager) {
		return ((MongoTransactionManager) transactionManager).getDatabaseFactory();
	}

}
