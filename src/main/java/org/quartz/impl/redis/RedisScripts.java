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

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Lua scripts that make every multi-key change of the Redis job store atomic.
 *
 * <p>{@code ARGV[1]} is always the store's key prefix ({@code keyPrefix{schedName}:}); the other
 * keys a script touches are derived from it. Every key of a scheduler contains the {@code
 * {schedName}} hash tag, so a script only touches one hash slot (Redis Cluster safe); {@code
 * KEYS[1]} names a key of that slot for routing. Key parts ({@code k}) are escaped by {@link
 * RedisKeys}.
 *
 * <p>Layout: {@code job:<k>} / {@code trigger:<k>} / {@code fired:<id>} hashes; {@code job_groups},
 * {@code trigger_groups} sets of group names; {@code jobs:<group>}, {@code triggers:<group>},
 * {@code job_triggers:<job k>}, {@code calendar_triggers:<calendar>}, {@code state:<STATE>} sets of
 * keys; {@code waiting} (and {@code waiting_ignore_misfire}) sorted sets of {@code WAITING}
 * triggers scored by next fire time; {@code fired_ids}, {@code fired_by_instance:<id>}, {@code
 * fired_by_job:<k>}, {@code fired_by_trigger:<k>}, {@code fired_instances} for fired triggers.
 */
final class RedisScripts {

  private RedisScripts() {}

  /** ARGV: prefix, job k, escaped group, raw group, field/value pairs... Returns 1. */
  static final RedisScript<Long> STORE_JOB =
      script(
          """
            local P = ARGV[1]
            local h = P .. 'job:' .. ARGV[2]
            redis.call('DEL', h)
            redis.call('HSET', h, '_k', ARGV[2], '_group', ARGV[3], unpack(ARGV, 5))
            redis.call('SADD', P .. 'jobs:' .. ARGV[3], ARGV[2])
            redis.call('SADD', P .. 'job_groups', ARGV[4])
            return 1
            """);

  /** ARGV: prefix, job k, raw group. Returns 1 if the job existed. */
  static final RedisScript<Long> DELETE_JOB =
      script(
          """
            local P = ARGV[1]
            local h = P .. 'job:' .. ARGV[2]
            local group = redis.call('HGET', h, '_group')
            if not group then return 0 end
            redis.call('DEL', h)
            redis.call('SREM', P .. 'jobs:' .. group, ARGV[2])
            if redis.call('SCARD', P .. 'jobs:' .. group) == 0 then
              redis.call('SREM', P .. 'job_groups', ARGV[3])
            end
            return 1
            """);

  /** ARGV: prefix, job k, field/value pairs of the new job data (replaces all data fields). */
  static final RedisScript<Long> UPDATE_JOB_DATA =
      script(
          """
            local P = ARGV[1]
            local h = P .. 'job:' .. ARGV[2]
            if redis.call('EXISTS', h) == 0 then return 0 end
            local fields = redis.call('HKEYS', h)
            for _, f in ipairs(fields) do
              if string.sub(f, 1, 5) == 'data.' or f == 'dataBlob' then redis.call('HDEL', h, f) end
            end
            if #ARGV > 2 then redis.call('HSET', h, unpack(ARGV, 3)) end
            return 1
            """);

  private static final String REMOVE_TRIGGER_INDEXES =
      """
            local function removeTriggerIndexes(P, tk, h)
              local old = redis.call('HMGET', h, '_jobKey', '_group', '_calKey', 'state', 'group')
              if not old[1] then return false end
              redis.call('SREM', P .. 'job_triggers:' .. old[1], tk)
              if old[3] and old[3] ~= '' then redis.call('SREM', P .. 'calendar_triggers:' .. old[3], tk) end
              if old[4] then redis.call('SREM', P .. 'state:' .. old[4], tk) end
              redis.call('ZREM', P .. 'waiting', tk)
              redis.call('ZREM', P .. 'waiting_ignore_misfire', tk)
              redis.call('SREM', P .. 'triggers:' .. old[2], tk)
              if redis.call('SCARD', P .. 'triggers:' .. old[2]) == 0 then
                redis.call('SREM', P .. 'trigger_groups', old[5])
              end
              return true
            end
            """;

  private static final String INDEX_WAITING =
      """
            local function indexWaiting(P, tk, state, nft, misfire)
              if state == 'WAITING' and nft and nft ~= '' then
                redis.call('ZADD', P .. 'waiting', nft, tk)
                if misfire == '-1' then redis.call('ZADD', P .. 'waiting_ignore_misfire', nft, tk) end
              else
                redis.call('ZREM', P .. 'waiting', tk)
                redis.call('ZREM', P .. 'waiting_ignore_misfire', tk)
              end
            end
            """;

  /**
   * ARGV: prefix, trigger k, job k, escaped group, escaped calendar ('' for none), state, raw
   * group, field/value pairs... Replaces the trigger and all its index entries.
   */
  static final RedisScript<Long> STORE_TRIGGER =
      script(
          REMOVE_TRIGGER_INDEXES
              + INDEX_WAITING
              + """
            local P = ARGV[1]
            local tk = ARGV[2]
            local h = P .. 'trigger:' .. tk
            removeTriggerIndexes(P, tk, h)
            redis.call('DEL', h)
            redis.call('HSET', h, '_k', tk, '_jobKey', ARGV[3], '_group', ARGV[4], '_calKey', ARGV[5],
              'state', ARGV[6], unpack(ARGV, 8))
            redis.call('SADD', P .. 'triggers:' .. ARGV[4], tk)
            redis.call('SADD', P .. 'trigger_groups', ARGV[7])
            redis.call('SADD', P .. 'job_triggers:' .. ARGV[3], tk)
            if ARGV[5] ~= '' then redis.call('SADD', P .. 'calendar_triggers:' .. ARGV[5], tk) end
            redis.call('SADD', P .. 'state:' .. ARGV[6], tk)
            local cur = redis.call('HMGET', h, 'nextFireTime', 'misfireInstruction')
            indexWaiting(P, tk, ARGV[6], cur[1], cur[2])
            return 1
            """);

  /** ARGV: prefix, trigger k. Returns 1 if the trigger existed. */
  static final RedisScript<Long> DELETE_TRIGGER =
      script(
          REMOVE_TRIGGER_INDEXES
              + """
            local P = ARGV[1]
            local h = P .. 'trigger:' .. ARGV[2]
            if not removeTriggerIndexes(P, ARGV[2], h) then return 0 end
            redis.call('DEL', h)
            return 1
            """);

  /**
   * Changes trigger states. ARGV: prefix, source set key ('' for a single trigger), single trigger
   * k, new state, then the old states the change applies to (none = any). Returns the number of
   * triggers changed.
   */
  static final RedisScript<Long> SET_STATE =
      script(
          INDEX_WAITING
              + """
            local P = ARGV[1]
            local newState = ARGV[4]
            local olds = {}
            local anyOld = #ARGV < 5
            for i = 5, #ARGV do olds[ARGV[i]] = true end
            local members
            if ARGV[2] ~= '' then members = redis.call('SMEMBERS', ARGV[2]) else members = { ARGV[3] } end
            local count = 0
            for _, tk in ipairs(members) do
              local h = P .. 'trigger:' .. tk
              local cur = redis.call('HMGET', h, 'state', 'nextFireTime', 'misfireInstruction')
              if cur[1] and (anyOld or olds[cur[1]]) then
                redis.call('SREM', P .. 'state:' .. cur[1], tk)
                redis.call('SADD', P .. 'state:' .. newState, tk)
                redis.call('HSET', h, 'state', newState)
                indexWaiting(P, tk, newState, cur[2], cur[3])
                count = count + 1
              end
            end
            return count
            """);

  /** ARGV: prefix, id, escaped instance, job k, trigger k, raw instance, field/value pairs. */
  static final RedisScript<Long> INSERT_FIRED =
      script(
          """
            local P = ARGV[1]
            local h = P .. 'fired:' .. ARGV[2]
            redis.call('HSET', h, '_inst', ARGV[3], '_jobKey', ARGV[4], '_trigKey', ARGV[5], unpack(ARGV, 7))
            redis.call('SADD', P .. 'fired_ids', ARGV[2])
            redis.call('SADD', P .. 'fired_by_instance:' .. ARGV[3], ARGV[2])
            redis.call('SADD', P .. 'fired_by_job:' .. ARGV[4], ARGV[2])
            redis.call('SADD', P .. 'fired_by_trigger:' .. ARGV[5], ARGV[2])
            redis.call('SADD', P .. 'fired_instances', ARGV[6])
            return 1
            """);

  /** ARGV: prefix, id, field/value pairs. Updates an existing entry only. */
  static final RedisScript<Long> UPDATE_FIRED =
      script(
          """
            local h = ARGV[1] .. 'fired:' .. ARGV[2]
            if redis.call('EXISTS', h) == 0 then return 0 end
            redis.call('HSET', h, unpack(ARGV, 3))
            return 1
            """);

  private static final String DELETE_FIRED_FUNCTION =
      """
            local function deleteFired(P, id)
              local h = P .. 'fired:' .. id
              local f = redis.call('HMGET', h, '_inst', '_jobKey', '_trigKey', 'instanceId')
              if not f[1] then return 0 end
              redis.call('DEL', h)
              redis.call('SREM', P .. 'fired_ids', id)
              redis.call('SREM', P .. 'fired_by_instance:' .. f[1], id)
              redis.call('SREM', P .. 'fired_by_job:' .. f[2], id)
              redis.call('SREM', P .. 'fired_by_trigger:' .. f[3], id)
              if redis.call('SCARD', P .. 'fired_by_instance:' .. f[1]) == 0 then
                redis.call('SREM', P .. 'fired_instances', f[4])
              end
              return 1
            end
            """;

  /** ARGV: prefix, id. Returns 1 if the entry existed. */
  static final RedisScript<Long> DELETE_FIRED =
      script(
          DELETE_FIRED_FUNCTION
              + """
            return deleteFired(ARGV[1], ARGV[2])
            """);

  /** ARGV: prefix, source set key (all ids, or one instance's). Returns the number deleted. */
  static final RedisScript<Long> DELETE_FIRED_SET =
      script(
          DELETE_FIRED_FUNCTION
              + """
            local count = 0
            for _, id in ipairs(redis.call('SMEMBERS', ARGV[2])) do
              count = count + deleteFired(ARGV[1], id)
            end
            return count
            """);

  /**
   * Deletes all scheduling data of the scheduler (not check-ins or locks). ARGV: prefix, glob
   * pattern of the scheduler's keys. O(number of keys).
   */
  static final RedisScript<Long> CLEAR_ALL =
      script(
          """
            local P = ARGV[1]
            local keep = { [P .. 'schedulers'] = true }
            local count = 0
            for _, key in ipairs(redis.call('KEYS', ARGV[2])) do
              if not keep[key] and string.sub(key, #P + 1, #P + 5) ~= 'lock:' then
                redis.call('DEL', key)
                count = count + 1
              end
            end
            return count
            """);

  /** KEYS: lock key. ARGV: owner, lease ms. Returns 1 if the owner now holds the lock. */
  static final RedisScript<Long> ACQUIRE_LOCK =
      script(
          """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
            return 0
            """);

  /** KEYS: lock key. ARGV: owner. Deletes the lock only if the owner holds it. */
  static final RedisScript<Long> RELEASE_LOCK =
      script(
          """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """);

  private static RedisScript<Long> script(String source) {
    return new DefaultRedisScript<>(source, Long.class);
  }
}
