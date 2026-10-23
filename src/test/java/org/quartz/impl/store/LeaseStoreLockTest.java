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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobPersistenceException;

class LeaseStoreLockTest {

  private final List<String> leases = new ArrayList<>();

  private final List<Boolean> interruptedOnRelease = new ArrayList<>();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void leaseGrantedByAFailedAcquireIsGivenBack() {
    // the store wrote the lease, then the call failed (an interrupted driver call at shutdown)
    LeaseStoreLock lock = lock(true);
    Thread.currentThread().interrupt();

    assertThatExceptionOfType(JobPersistenceException.class)
        .isThrownBy(() -> lock.obtain("TRIGGER_ACCESS"));

    assertThat(leases).isEmpty();
    assertThat(interruptedOnRelease).containsExactly(false);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void releaseOnAnInterruptedThreadReachesTheStore() throws Exception {
    LeaseStoreLock lock = lock(false);
    lock.obtain("TRIGGER_ACCESS");
    Thread.currentThread().interrupt();

    lock.release("TRIGGER_ACCESS");

    assertThat(leases).isEmpty();
    assertThat(interruptedOnRelease).containsExactly(false);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  private LeaseStoreLock lock(boolean failAfterAcquire) {
    return new TestLock("owner", failAfterAcquire);
  }

  /** One lease, held in {@link #leases}; can fail after granting it, as an interrupted call can. */
  private final class TestLock extends LeaseStoreLock {

    private final boolean failAfterAcquire;

    TestLock(String owner, boolean failAfterAcquire) {
      super(owner, Duration.ofMinutes(10), Duration.ofMillis(1));
      this.failAfterAcquire = failAfterAcquire;
    }

    @Override
    protected boolean tryAcquire(String lockName, String owner, Duration leaseTime) {
      if (Thread.currentThread().isInterrupted() && !failAfterAcquire) {
        throw new IllegalStateException("interrupted");
      }
      if (!leases.isEmpty() && !leases.contains(owner)) {
        return false;
      }
      leases.clear();
      leases.add(owner);
      if (failAfterAcquire) {
        throw new IllegalStateException("interrupted after the lease was written");
      }
      return true;
    }

    @Override
    protected void releaseLease(String lockName, String owner) {
      boolean interrupted = Thread.currentThread().isInterrupted();
      interruptedOnRelease.add(interrupted);
      if (interrupted) {
        throw new IllegalStateException("interrupted");
      }
      leases.remove(owner);
    }
  }
}
