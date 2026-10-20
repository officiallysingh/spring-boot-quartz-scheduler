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
package org.quartz.impl.store;

import org.quartz.JobPersistenceException;

/**
 * Mutual exclusion for compound store operations, by lock name
 * ({@link PersistentJobStore#LOCK_TRIGGER_ACCESS}, {@link PersistentJobStore#LOCK_STATE_ACCESS}).
 * Re-entrancy is handled by {@link PersistentJobStore}; implementations see each
 * obtain/release pair once per thread.
 */
public interface StoreLock {

    /** Blocks until the calling thread holds the named lock. */
    void obtain(String lockName) throws JobPersistenceException;

    /** Releases a lock the calling thread holds. */
    void release(String lockName);
}
