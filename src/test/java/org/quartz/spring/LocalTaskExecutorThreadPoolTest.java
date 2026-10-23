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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class LocalTaskExecutorThreadPoolTest {

  private final ExecutorService executor = Executors.newCachedThreadPool();

  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() {
    release.countDown();
    executor.shutdownNow();
  }

  @Test
  void capacityOfAThreadPoolTaskExecutorIsItsCorePoolSizeWhenItQueues() {
    ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
    taskExecutor.setCorePoolSize(3);
    taskExecutor.setMaxPoolSize(8);
    taskExecutor.initialize();
    try {
      assertThat(initialized(new LocalTaskExecutorThreadPool(taskExecutor)).getPoolSize())
          .isEqualTo(3);
    } finally {
      taskExecutor.shutdown();
    }
  }

  @Test
  void capacityOfAThreadPoolExecutorWithoutAQueueIsItsMaximumPoolSize() {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(0, 5, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    try {
      assertThat(initialized(new LocalTaskExecutorThreadPool(pool)).getPoolSize()).isEqualTo(5);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void capacityOfAThreadPoolExecutorWithABoundedQueueIsItsCorePoolSize() {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(2, 5, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
    try {
      assertThat(initialized(new LocalTaskExecutorThreadPool(pool)).getPoolSize()).isEqualTo(2);
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void capacityOfASimpleAsyncTaskExecutorIsItsConcurrencyLimit() {
    SimpleAsyncTaskExecutor limited = new SimpleAsyncTaskExecutor();
    limited.setConcurrencyLimit(4);
    assertThat(initialized(new LocalTaskExecutorThreadPool(limited)).getPoolSize()).isEqualTo(4);
    assertThat(
            initialized(new LocalTaskExecutorThreadPool(new SimpleAsyncTaskExecutor()))
                .getPoolSize())
        .isEqualTo(-1);
  }

  @Test
  void explicitCapacityWins() {
    assertThat(initialized(new LocalTaskExecutorThreadPool(executor, 7)).getPoolSize())
        .isEqualTo(7);
  }

  @Test
  void unknownCapacityOffersOneJobAtATime() {
    LocalTaskExecutorThreadPool pool =
        initialized(new LocalTaskExecutorThreadPool(executor::execute));
    assertThat(pool.getPoolSize()).isEqualTo(-1);
    assertThat(pool.runInThread(this::awaitRelease)).isTrue();
    assertThat(pool.blockForAvailableThreads()).isEqualTo(1);
  }

  @Test
  void reportsRoomLeftAndBlocksWhileFull() {
    LocalTaskExecutorThreadPool pool = initialized(new LocalTaskExecutorThreadPool(executor, 2));
    assertThat(pool.blockForAvailableThreads()).isEqualTo(2);
    CountDownLatch first = new CountDownLatch(1);
    pool.runInThread(() -> awaitLatch(first));
    assertThat(pool.blockForAvailableThreads()).isEqualTo(1);
    pool.runInThread(this::awaitRelease);

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
  void rejectedJobFreesItsSlot() {
    LocalTaskExecutorThreadPool pool =
        initialized(
            new LocalTaskExecutorThreadPool(
                runnable -> {
                  throw new RejectedExecutionException("full");
                },
                1));
    assertThat(pool.runInThread(() -> {})).isFalse();
    assertThat(pool.blockForAvailableThreads()).isEqualTo(1);
  }

  @Test
  void shutdownWithWaitWaitsForRunningJobs() {
    LocalTaskExecutorThreadPool pool = initialized(new LocalTaskExecutorThreadPool(executor, 2));
    pool.runInThread(this::awaitRelease);

    CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> pool.shutdown(true));
    await()
        .during(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(1))
        .until(() -> !shutdown.isDone());
    release.countDown();
    await().atMost(Duration.ofSeconds(5)).until(shutdown::isDone);
  }

  @Test
  void shutdownWithoutWaitReturnsAtOnceAndReleasesABlockedCaller() {
    LocalTaskExecutorThreadPool pool = initialized(new LocalTaskExecutorThreadPool(executor, 1));
    pool.runInThread(this::awaitRelease);
    CompletableFuture<Integer> available =
        CompletableFuture.supplyAsync(pool::blockForAvailableThreads);

    pool.shutdown(false);

    await().atMost(Duration.ofSeconds(5)).until(available::isDone);
    assertThat(available.join()).isZero();
  }

  private static LocalTaskExecutorThreadPool initialized(LocalTaskExecutorThreadPool pool) {
    pool.initialize();
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
