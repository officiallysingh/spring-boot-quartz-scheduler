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

import org.quartz.spi.JobStore;

/**
 * Creates the {@link JobStore} for one {@link JobStoreType} from the bound properties.
 * Registered by the store-specific configurations when their client library and connection
 * bean are present.
 */
interface JobStoreFactory {

	JobStoreType type();

	JobStore create(QuartzProperties properties);

}
