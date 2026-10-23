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
package org.quartz.simpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VirtualThreadPoolTest {

  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() {
    release.countDown();
  }

  @Test
  void runsEachJobOnANewNamedVirtualThread() throws Exception {
    VirtualThreadPool pool = initialized(new VirtualThreadPool(2), "sched");
    AtomicReference<Thread> first = new AtomicReference<>();
    AtomicReference<Thread> second = new AtomicReference<>();

    assertThat(pool.runInThread(() -> first.set(Thread.currentThread()))).isTrue();
    assertThat(pool.runInThread(() -> second.set(Thread.currentThread()))).isTrue();

    await().atMost(Duration.ofSeconds(5)).until(() -> first.get() != null && second.get() != null);
    assertThat(first.get().isVirtual()).isTrue();
    assertThat(first.get()).isNotSameAs(second.get());
    assertThat(first.get().getName()).startsWith("sched_Worker-");
    assertThat(second.get().getName()).startsWith("sched_Worker-");
  }

  @Test
  void usesTheConfiguredThreadNamePrefix() {
    VirtualThreadPool pool = new VirtualThreadPool(1);
    pool.setThreadNamePrefix("jobs");
    initialized(pool, "sched");
    AtomicReference<String> name = new AtomicReference<>();

    pool.runInThread(() -> name.set(Thread.currentThread().getName()));

    await().atMost(Duration.ofSeconds(5)).until(() -> name.get() != null);
    assertThat(name.get()).isEqualTo("jobs-1");
  }

  @Test
  void jobsUseTheContextClassLoaderOfTheInitializingThread() throws Exception {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try (URLClassLoader loader = new URLClassLoader(new URL[0], original)) {
      Thread.currentThread().setContextClassLoader(loader);
      VirtualThreadPool pool = initialized(new VirtualThreadPool(1), "sched");
      Thread.currentThread().setContextClassLoader(original);
      AtomicReference<ClassLoader> seen = new AtomicReference<>();

      CompletableFuture.runAsync(
              () ->
                  pool.runInThread(() -> seen.set(Thread.currentThread().getContextClassLoader())))
          .join();

      await().atMost(Duration.ofSeconds(5)).until(() -> seen.get() != null);
      assertThat(seen.get()).isSameAs(loader);
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  void reportsRoomLeftAndBlocksWhileFull() {
    VirtualThreadPool pool = initialized(new VirtualThreadPool(2), "sched");
    assertThat(pool.getPoolSize()).isEqualTo(2);
    assertThat(pool.blockForAvailableThreads()).isEqualTo(2);
    CountDownLatch first = new CountDownLatch(1);
    pool.runInThread(() -> awaitLatch(first));
    assertThat(pool.blockForAvailableThreads()).isEqualTo(1);
    pool.runInThread(this::awaitRelease);
    await().atMost(Duration.ofSeconds(5)).until(() -> pool.getRunningCount() == 2);

    CompletableFuture<Integer> available =
        CompletableFuture.supplyAsync(pool::blockForAvailableThreads);
    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(1))
        .until(() -> !available.isDone());
    first.countDown();
    await().atMost(Duration.ofSeconds(5)).until(available::isDone);
    assertThat(available.join()).isEqualTo(1);
  }

  @Test
  void aJobThatThrowsFreesItsSlot() {
    VirtualThreadPool pool = initialized(new VirtualThreadPool(1), "sched");

    pool.runInThread(
        () -> {
          throw new IllegalStateException("job failed");
        });

    await().atMost(Duration.ofSeconds(5)).until(() -> pool.getRunningCount() == 0);
    assertThat(pool.blockForAvailableThreads()).isEqualTo(1);
  }

  @Test
  void shutdownWithWaitWaitsForRunningJobs() {
    VirtualThreadPool pool = initialized(new VirtualThreadPool(2), "sched");
    pool.runInThread(this::awaitRelease);

    CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> pool.shutdown(true));
    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(1))
        .until(() -> !shutdown.isDone());
    release.countDown();
    await().atMost(Duration.ofSeconds(5)).until(shutdown::isDone);
    assertThat(pool.getRunningCount()).isZero();
  }

  @Test
  void shutdownWithoutWaitReturnsAtOnceAndReleasesABlockedCaller() {
    VirtualThreadPool pool = initialized(new VirtualThreadPool(1), "sched");
    pool.runInThread(this::awaitRelease);
    await().atMost(Duration.ofSeconds(5)).until(() -> pool.getRunningCount() == 1);
    CompletableFuture<Integer> available =
        CompletableFuture.supplyAsync(pool::blockForAvailableThreads);

    pool.shutdown(false);

    await().atMost(Duration.ofSeconds(5)).until(available::isDone);
    assertThat(available.join()).isZero();
  }

  @Test
  void maxConcurrencyMustBePositive() {
    assertThatThrownBy(() -> new VirtualThreadPool(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static VirtualThreadPool initialized(VirtualThreadPool pool, String schedulerName) {
    pool.setInstanceName(schedulerName);
    try {
      pool.initialize();
    } catch (org.quartz.SchedulerConfigException ex) {
      throw new IllegalStateException(ex);
    }
    return pool;
  }

  private void awaitRelease() {
    awaitLatch(release);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
