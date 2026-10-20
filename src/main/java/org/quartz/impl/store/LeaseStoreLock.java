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

import java.time.Duration;

import org.quartz.JobPersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A cluster-wide lock held as a lease in the store: the holder writes its owner id with
 * an expiry, and other instances poll until the lease is released or has expired. An
 * instance that dies while holding a lock therefore blocks the others for at most
 * {@code leaseTime}.
 * <p>
 * Threads of the same instance first serialize on an in-JVM lock, so one lease is
 * enough per instance.
 */
public abstract class LeaseStoreLock implements StoreLock {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final JvmStoreLock localLock = new JvmStoreLock();

    private final String owner;

    private final Duration leaseTime;

    private final Duration retryInterval;

    /**
     * @param owner unique id of the lock holder (scheduler instance plus store instance)
     * @param leaseTime how long a lease is valid without being released
     * @param retryInterval pause between attempts while another instance holds the lease
     */
    protected LeaseStoreLock(String owner, Duration leaseTime, Duration retryInterval) {
        this.owner = owner;
        this.leaseTime = leaseTime;
        this.retryInterval = retryInterval.isZero() ? Duration.ofMillis(1) : retryInterval;
    }

    public String getOwner() {
        return owner;
    }

    public Duration getLeaseTime() {
        return leaseTime;
    }

    /**
     * Takes the lease if it is free, expired or already held by {@code owner}.
     *
     * @return whether {@code owner} now holds the lease
     */
    protected abstract boolean tryAcquire(String lockName, String owner, Duration leaseTime);

    /** Releases the lease if {@code owner} holds it. */
    protected abstract void releaseLease(String lockName, String owner);

    @Override
    public void obtain(String lockName) throws JobPersistenceException {
        localLock.obtain(lockName);
        try {
            while (!tryAcquire(lockName, owner, leaseTime)) {
                Thread.sleep(retryInterval);
            }
            log.trace("Lock '{}' obtained by {}", lockName, owner);
        }
        catch (InterruptedException ex) {
            localLock.release(lockName);
            Thread.currentThread().interrupt();
            throw new JobPersistenceException("Interrupted while waiting for lock '" + lockName + "'", ex);
        }
        catch (RuntimeException ex) {
            localLock.release(lockName);
            throw new JobPersistenceException("Couldn't obtain lock '" + lockName + "': " + ex.getMessage(), ex);
        }
    }

    @Override
    public void release(String lockName) {
        try {
            releaseLease(lockName, owner);
            log.trace("Lock '{}' released by {}", lockName, owner);
        }
        catch (RuntimeException ex) {
            log.warn("Couldn't release lock '{}' (it expires after {}): {}", lockName, leaseTime, ex.getMessage());
        }
        finally {
            localLock.release(lockName);
        }
    }
}
