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
package org.quartz.impl.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quartz.support.AbstractRedisIT;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * One test per Lua script of the Redis job store, run against the shared container: each checks the
 * keys the script must write, index and clean up.
 */
class RedisLuaScriptsIT extends AbstractRedisIT {

  private final StringRedisTemplate redis = redisTemplate();

  private final RedisKeys keys = new RedisKeys("quartz:", "LuaTest");

  private final String p = keys.prefix();

  private long run(RedisScript<Long> script, String routingKey, String... args) {
    return redis.execute(script, List.of(routingKey), (Object[]) args);
  }

  private void storeTrigger(
      String k,
      String jobK,
      String group,
      String calendar,
      String state,
      String nextFireTime,
      String misfireInstruction) {
    List<String> args =
        new ArrayList<>(
            List.of(
                p,
                k,
                jobK,
                group,
                calendar,
                state,
                group,
                "name",
                "t",
                "group",
                group,
                "misfireInstruction",
                misfireInstruction,
                "priority",
                "5"));
    if (nextFireTime != null) {
      args.add("nextFireTime");
      args.add(nextFireTime);
    }
    run(RedisScripts.STORE_TRIGGER, keys.trigger(k), args.toArray(String[]::new));
  }

  @Test
  void storeJobWritesTheHashAndGroupIndexes() {
    run(
        RedisScripts.STORE_JOB,
        keys.job("g:j"),
        p,
        "g:j",
        "g",
        "g",
        "name",
        "j",
        "jobClass",
        "x.Job");

    assertThat(redis.opsForHash().entries(keys.job("g:j")))
        .containsEntry("name", "j")
        .containsEntry("_group", "g");
    assertThat(redis.opsForSet().members(keys.jobs("g"))).containsExactly("g:j");
    assertThat(redis.opsForSet().members(keys.jobGroups())).containsExactly("g");

    // storing again replaces the hash (stale fields disappear)
    run(RedisScripts.STORE_JOB, keys.job("g:j"), p, "g:j", "g", "g", "name", "j");
    assertThat(redis.opsForHash().hasKey(keys.job("g:j"), "jobClass")).isFalse();
  }

  @Test
  void deleteJobRemovesTheHashAndAnEmptyGroup() {
    run(RedisScripts.STORE_JOB, keys.job("g:j"), p, "g:j", "g", "g", "name", "j");

    assertThat(run(RedisScripts.DELETE_JOB, keys.job("g:j"), p, "g:j", "g")).isEqualTo(1);
    assertThat(redis.hasKey(keys.job("g:j"))).isFalse();
    assertThat(redis.opsForSet().size(keys.jobs("g"))).isZero();
    assertThat(redis.opsForSet().members(keys.jobGroups())).isEmpty();
    assertThat(run(RedisScripts.DELETE_JOB, keys.job("g:j"), p, "g:j", "g")).isZero();
  }

  @Test
  void updateJobDataReplacesOnlyDataFields() {
    run(
        RedisScripts.STORE_JOB,
        keys.job("g:j"),
        p,
        "g:j",
        "g",
        "g",
        "name",
        "j",
        "data.a",
        "s:1",
        "dataBlob",
        "x");

    assertThat(run(RedisScripts.UPDATE_JOB_DATA, keys.job("g:j"), p, "g:j", "data.b", "i:2"))
        .isEqualTo(1);
    assertThat(redis.opsForHash().entries(keys.job("g:j")))
        .containsEntry("name", "j")
        .containsEntry("data.b", "i:2")
        .doesNotContainKeys("data.a", "dataBlob");
    assertThat(run(RedisScripts.UPDATE_JOB_DATA, keys.job("g:missing"), p, "g:missing")).isZero();
  }

  @Test
  void storeTriggerIndexesAndReindexesOnReplace() {
    storeTrigger("tg:t", "g:j", "tg", "cal", "WAITING", "1000", "-1");

    assertThat(redis.opsForSet().members(keys.triggers("tg"))).containsExactly("tg:t");
    assertThat(redis.opsForSet().members(keys.triggerGroups())).containsExactly("tg");
    assertThat(redis.opsForSet().members(keys.jobTriggers("g:j"))).containsExactly("tg:t");
    assertThat(redis.opsForSet().members(keys.calendarTriggers("cal"))).containsExactly("tg:t");
    assertThat(redis.opsForSet().members(keys.state("WAITING"))).containsExactly("tg:t");
    assertThat(redis.opsForZSet().score(keys.waiting(), "tg:t")).isEqualTo(1000d);
    assertThat(redis.opsForZSet().score(keys.waitingIgnoreMisfire(), "tg:t")).isEqualTo(1000d);

    // replaced: other job, no calendar, paused, normal misfire handling
    storeTrigger("tg:t", "g:other", "tg", "", "PAUSED", "2000", "0");
    assertThat(redis.opsForSet().size(keys.jobTriggers("g:j"))).isZero();
    assertThat(redis.opsForSet().members(keys.jobTriggers("g:other"))).containsExactly("tg:t");
    assertThat(redis.opsForSet().size(keys.calendarTriggers("cal"))).isZero();
    assertThat(redis.opsForSet().size(keys.state("WAITING"))).isZero();
    assertThat(redis.opsForSet().members(keys.state("PAUSED"))).containsExactly("tg:t");
    assertThat(redis.opsForZSet().score(keys.waiting(), "tg:t")).isNull();
    assertThat(redis.opsForZSet().score(keys.waitingIgnoreMisfire(), "tg:t")).isNull();
  }

  @Test
  void deleteTriggerRemovesEveryIndexEntry() {
    storeTrigger("tg:t", "g:j", "tg", "cal", "WAITING", "1000", "-1");

    assertThat(run(RedisScripts.DELETE_TRIGGER, keys.trigger("tg:t"), p, "tg:t")).isEqualTo(1);
    assertThat(redis.hasKey(keys.trigger("tg:t"))).isFalse();
    assertThat(redis.opsForSet().size(keys.triggers("tg"))).isZero();
    assertThat(redis.opsForSet().members(keys.triggerGroups())).isEmpty();
    assertThat(redis.opsForSet().size(keys.jobTriggers("g:j"))).isZero();
    assertThat(redis.opsForSet().size(keys.calendarTriggers("cal"))).isZero();
    assertThat(redis.opsForSet().size(keys.state("WAITING"))).isZero();
    assertThat(redis.opsForZSet().size(keys.waiting())).isZero();
    assertThat(redis.opsForZSet().size(keys.waitingIgnoreMisfire())).isZero();
    assertThat(run(RedisScripts.DELETE_TRIGGER, keys.trigger("tg:t"), p, "tg:t")).isZero();
  }

  @Test
  void setStateIsConditionalAndMaintainsTheWaitingSet() {
    storeTrigger("tg:a", "g:j", "tg", "", "WAITING", "1000", "0");
    storeTrigger("tg:b", "g:j", "tg", "", "PAUSED", "2000", "0");

    // single trigger, conditional
    assertThat(
            run(RedisScripts.SET_STATE, keys.trigger("tg:a"), p, "", "tg:a", "ACQUIRED", "WAITING"))
        .isEqualTo(1);
    assertThat(
            run(RedisScripts.SET_STATE, keys.trigger("tg:a"), p, "", "tg:a", "ACQUIRED", "WAITING"))
        .isZero();
    assertThat(redis.opsForHash().get(keys.trigger("tg:a"), "state")).isEqualTo("ACQUIRED");
    assertThat(redis.opsForZSet().score(keys.waiting(), "tg:a")).isNull();

    // a whole set, from any of the given states
    assertThat(
            run(
                RedisScripts.SET_STATE,
                keys.jobTriggers("g:j"),
                p,
                keys.jobTriggers("g:j"),
                "",
                "WAITING",
                "ACQUIRED",
                "PAUSED"))
        .isEqualTo(2);
    assertThat(redis.opsForSet().members(keys.state("WAITING")))
        .containsExactlyInAnyOrder("tg:a", "tg:b");
    assertThat(redis.opsForSet().size(keys.state("ACQUIRED"))).isZero();
    assertThat(redis.opsForSet().size(keys.state("PAUSED"))).isZero();
    assertThat(redis.opsForZSet().score(keys.waiting(), "tg:b")).isEqualTo(2000d);

    // unconditional on a missing trigger changes nothing
    assertThat(run(RedisScripts.SET_STATE, keys.trigger("tg:none"), p, "", "tg:none", "ERROR"))
        .isZero();
  }

  private void insertFired(String id, String instance) {
    run(
        RedisScripts.INSERT_FIRED,
        keys.fired(id),
        p,
        id,
        instance,
        "g:j",
        "tg:t",
        instance,
        "fireInstanceId",
        id,
        "instanceId",
        instance,
        "state",
        "ACQUIRED");
  }

  @Test
  void insertFiredIndexesTheEntry() {
    insertFired("f1", "node-a");

    assertThat(redis.opsForHash().entries(keys.fired("f1"))).containsEntry("state", "ACQUIRED");
    assertThat(redis.opsForSet().members(keys.firedIds())).containsExactly("f1");
    assertThat(redis.opsForSet().members(keys.firedByInstance("node-a"))).containsExactly("f1");
    assertThat(redis.opsForSet().members(keys.firedByJob("g:j"))).containsExactly("f1");
    assertThat(redis.opsForSet().members(keys.firedByTrigger("tg:t"))).containsExactly("f1");
    assertThat(redis.opsForSet().members(keys.firedInstances())).containsExactly("node-a");
  }

  @Test
  void updateFiredOnlyTouchesExistingEntries() {
    insertFired("f1", "node-a");

    assertThat(run(RedisScripts.UPDATE_FIRED, keys.fired("f1"), p, "f1", "state", "EXECUTING"))
        .isEqualTo(1);
    assertThat(redis.opsForHash().get(keys.fired("f1"), "state")).isEqualTo("EXECUTING");
    assertThat(run(RedisScripts.UPDATE_FIRED, keys.fired("gone"), p, "gone", "state", "EXECUTING"))
        .isZero();
    assertThat(redis.hasKey(keys.fired("gone"))).isFalse();
  }

  @Test
  void deleteFiredRemovesTheEntryAndItsIndexes() {
    insertFired("f1", "node-a");

    assertThat(run(RedisScripts.DELETE_FIRED, keys.fired("f1"), p, "f1")).isEqualTo(1);
    assertThat(redis.hasKey(keys.fired("f1"))).isFalse();
    assertThat(redis.opsForSet().size(keys.firedIds())).isZero();
    assertThat(redis.opsForSet().size(keys.firedByInstance("node-a"))).isZero();
    assertThat(redis.opsForSet().size(keys.firedByJob("g:j"))).isZero();
    assertThat(redis.opsForSet().size(keys.firedByTrigger("tg:t"))).isZero();
    assertThat(redis.opsForSet().members(keys.firedInstances())).isEmpty();
  }

  @Test
  void deleteFiredSetDeletesOneInstancesEntriesOrAll() {
    insertFired("f1", "node-a");
    insertFired("f2", "node-a");
    insertFired("f3", "node-b");

    String nodeA = keys.firedByInstance("node-a");
    assertThat(run(RedisScripts.DELETE_FIRED_SET, nodeA, p, nodeA)).isEqualTo(2);
    assertThat(redis.opsForSet().members(keys.firedIds())).containsExactly("f3");
    assertThat(redis.opsForSet().members(keys.firedInstances())).containsExactly("node-b");

    assertThat(run(RedisScripts.DELETE_FIRED_SET, keys.firedIds(), p, keys.firedIds()))
        .isEqualTo(1);
    assertThat(redis.opsForSet().size(keys.firedIds())).isZero();
  }

  @Test
  void clearAllKeepsCheckInsAndLocksAndOtherSchedulers() {
    run(RedisScripts.STORE_JOB, keys.job("g:j"), p, "g:j", "g", "g", "name", "j");
    storeTrigger("tg:t", "g:j", "tg", "", "WAITING", "1000", "0");
    redis.opsForHash().put(keys.schedulers(), "node-a", "1,2");
    redis.opsForValue().set(keys.lock("TRIGGER_ACCESS"), "owner");
    RedisKeys other = new RedisKeys("quartz:", "OtherScheduler");
    redis.opsForValue().set(other.job("g:j"), "kept");

    assertThat(run(RedisScripts.CLEAR_ALL, keys.schedulers(), p, keys.pattern())).isPositive();

    assertThat(redis.keys(p + "*"))
        .containsExactlyInAnyOrder(keys.schedulers(), keys.lock("TRIGGER_ACCESS"));
    assertThat(redis.hasKey(other.job("g:j"))).isTrue();
  }

  @Test
  void acquireLockIsExclusiveAndReentrantForItsOwner() {
    String lock = keys.lock("TRIGGER_ACCESS");

    assertThat(run(RedisScripts.ACQUIRE_LOCK, lock, "owner-a", "10000")).isEqualTo(1);
    assertThat(run(RedisScripts.ACQUIRE_LOCK, lock, "owner-b", "10000")).isZero();
    assertThat(run(RedisScripts.ACQUIRE_LOCK, lock, "owner-a", "10000")).isEqualTo(1);
    assertThat(redis.getExpire(lock)).isPositive();
  }

  @Test
  void releaseLockOnlyDeletesTheOwnersLock() {
    String lock = keys.lock("TRIGGER_ACCESS");
    run(RedisScripts.ACQUIRE_LOCK, lock, "owner-a", "10000");

    assertThat(run(RedisScripts.RELEASE_LOCK, lock, "owner-b")).isZero();
    assertThat(redis.opsForValue().get(lock)).isEqualTo("owner-a");
    assertThat(run(RedisScripts.RELEASE_LOCK, lock, "owner-a")).isEqualTo(1);
    assertThat(redis.hasKey(lock)).isFalse();
  }
}
