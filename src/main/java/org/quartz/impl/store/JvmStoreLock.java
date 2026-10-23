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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** In-JVM locks, for a store that is not clustered (only one scheduler instance uses the data). */
public class JvmStoreLock implements StoreLock {

  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  @Override
  public void obtain(String lockName) {
    locks.computeIfAbsent(lockName, name -> new ReentrantLock()).lock();
  }

  @Override
  public void release(String lockName) {
    ReentrantLock lock = locks.get(lockName);
    if (lock != null && lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }
}
