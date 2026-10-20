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

import java.util.List;
import java.util.Locale;

/**
 * Thrown at startup when the auto-configuration cannot create a Quartz job store: no
 * {@code spring.quartz.job-store.type} and no MongoDB / Redis connection bean, both
 * available without a type, or a type whose connection bean is missing. Reported by
 * {@link JobStoreUnavailableFailureAnalyzer}.
 */
public class JobStoreUnavailableException extends IllegalStateException {

	private final JobStoreType configuredType;

	private final List<JobStoreType> availableTypes;

	JobStoreUnavailableException(JobStoreType configuredType, List<JobStoreType> availableTypes) {
		super(message(configuredType, availableTypes));
		this.configuredType = configuredType;
		this.availableTypes = List.copyOf(availableTypes);
	}

	/** The configured {@code spring.quartz.job-store.type}, or {@code null} if unset. */
	public JobStoreType getConfiguredType() {
		return this.configuredType;
	}

	/** The store types whose connection bean is present. */
	public List<JobStoreType> getAvailableTypes() {
		return this.availableTypes;
	}

	private static String message(JobStoreType configuredType, List<JobStoreType> availableTypes) {
		if (configuredType != null) {
			return "'spring.quartz.job-store.type' is " + name(configuredType) + " but no "
					+ JobStoreConfigurations.requirement(configuredType) + " is available";
		}
		if (availableTypes.isEmpty()) {
			return "No Quartz job store configured: set 'spring.quartz.job-store.type' (mongodb or redis) "
					+ "and provide a MongoClient or RedisConnectionFactory bean";
		}
		return "Several Quartz job stores are available (" + String.join(", ",
				availableTypes.stream().map(JobStoreUnavailableException::name).toList())
				+ "): set 'spring.quartz.job-store.type' to choose one";
	}

	static String name(JobStoreType type) {
		return type.name().toLowerCase(Locale.ROOT);
	}

}
