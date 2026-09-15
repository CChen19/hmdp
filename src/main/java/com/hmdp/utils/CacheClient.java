package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Redis cache helpers for shop display data (logical expire + miss fallback).
 * Not for seckill stock or order eligibility.
 */
@Slf4j
@Component
public class CacheClient {
    /** Empty-marker payload: key exists, shop confirmed absent. */
    static final String EMPTY_MARKER = "";

    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<Long>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * Bounded rebuild pool (core 2 / max 10 / queue 200). Rejects with a warn log when saturated
     * so stampede cannot grow threads without bound; callers still get stale/miss handling.
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            2,
            10,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(200),
            r -> {
                Thread t = new Thread(r, "shop-cache-rebuild");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.warn("Shop cache rebuild rejected: pool saturated")
    );

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Serialize object to JSON with physical TTL (jitter applied).
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        long seconds = withJitter(unit.toSeconds(time));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), seconds, TimeUnit.SECONDS);
    }

    /**
     * Write logical-expire wrapper (no Redis TTL; expiry is in JSON).
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * Pass-through cache with empty marker on DB miss.
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isNotEmpty(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (EMPTY_MARKER.equals(json)) {
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null) {
            setEmptyMarker(key);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * Logical-expire shop read with miss → DB fallback, empty markers, and owner-safe rebuild locks.
     */
    public <R, ID> CacheResult<R> queryWithLogicalExpire(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for key={}, falling back to DB", key, e);
            return loadFromDbOnly(id, dbFallback);
        }

        if (json == null) {
            return loadOnMiss(key, id, type, dbFallback, time, unit);
        }
        if (EMPTY_MARKER.equals(json)) {
            return CacheResult.notFound();
        }
        return handleHit(key, id, json, type, dbFallback, time, unit);
    }

    private <R, ID> CacheResult<R> handleHit(
            String key,
            ID id,
            String json,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData == null || redisData.getData() == null) {
            return loadOnMiss(key, id, type, dbFallback, time, unit);
        }
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = BeanUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
            return CacheResult.ok(r);
        }
        // logically expired: return stale, rebuild async under owner lock
        String lockKey = LOCK_SHOP_KEY + id;
        String token = tryLock(lockKey);
        if (token != null) {
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> rebuildAsync(key, id, dbFallback, time, unit, lockKey, token));
            } catch (RuntimeException e) {
                log.warn("Failed to submit cache rebuild for key={}", key, e);
                releaseLock(lockKey, token);
            }
        }
        return CacheResult.ok(r);
    }

    private <R, ID> void rebuildAsync(
            String key,
            ID id,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit,
            String lockKey,
            String token) {
        try {
            R newR = dbFallback.apply(id);
            if (newR == null) {
                setEmptyMarker(key);
            } else {
                this.setWithLogicalExpire(key, newR, time, unit);
            }
        } catch (Exception e) {
            log.error("Cache rebuild failed for key={}", key, e);
        } finally {
            releaseLock(lockKey, token);
        }
    }

    private <R, ID> CacheResult<R> loadOnMiss(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String lockKey = LOCK_SHOP_KEY + id;
        String token = tryLock(lockKey);
        if (token == null) {
            return waitAndRetryAfterMiss(key, id, type, dbFallback, time, unit);
        }
        try {
            String json;
            try {
                json = stringRedisTemplate.opsForValue().get(key);
            } catch (RuntimeException e) {
                log.warn("Redis read failed during miss load for key={}", key, e);
                return loadFromDbOnly(id, dbFallback);
            }
            if (EMPTY_MARKER.equals(json)) {
                return CacheResult.notFound();
            }
            if (StringUtils.isNotEmpty(json)) {
                // Filled by another worker while we waited for the lock — return without nested lock.
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                if (redisData != null && redisData.getData() != null) {
                    JSONObject jsonObject = (JSONObject) redisData.getData();
                    return CacheResult.ok(BeanUtil.toBean(jsonObject, type));
                }
            }
            R r;
            try {
                r = dbFallback.apply(id);
            } catch (RuntimeException e) {
                log.error("DB load failed for id={}, not caching as empty", id, e);
                return CacheResult.unavailable();
            }
            if (r == null) {
                setEmptyMarker(key);
                return CacheResult.notFound();
            }
            try {
                this.setWithLogicalExpire(key, r, time, unit);
            } catch (RuntimeException e) {
                log.warn("Failed to write shop cache for key={}, returning DB value", key, e);
            }
            return CacheResult.ok(r);
        } finally {
            releaseLock(lockKey, token);
        }
    }

    private <R, ID> CacheResult<R> waitAndRetryAfterMiss(
            String key,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        sleepQuietly(50L);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (EMPTY_MARKER.equals(json)) {
                return CacheResult.notFound();
            }
            if (StringUtils.isNotEmpty(json)) {
                return handleHit(key, id, json, type, dbFallback, time, unit);
            }
        } catch (RuntimeException e) {
            log.warn("Redis unavailable after lock wait for key={}", key, e);
            return loadFromDbOnly(id, dbFallback);
        }
        // still miss: bounded DB read so we never treat miss as not-found
        return loadFromDbOnly(id, dbFallback);
    }

    private <R, ID> CacheResult<R> loadFromDbOnly(ID id, Function<ID, R> dbFallback) {
        try {
            R r = dbFallback.apply(id);
            if (r == null) {
                return CacheResult.notFound();
            }
            return CacheResult.ok(r);
        } catch (RuntimeException e) {
            log.error("DB fallback failed for id={}", id, e);
            return CacheResult.unavailable();
        }
    }

    private void setEmptyMarker(String key) {
        long seconds = withJitter(TimeUnit.MINUTES.toSeconds(CACHE_NULL_TTL));
        try {
            stringRedisTemplate.opsForValue().set(key, EMPTY_MARKER, seconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("Failed to write empty marker for key={}", key, e);
        }
    }

    /**
     * TTL jitter (~0–20%) to reduce synchronized expiry stampedes.
     */
    long withJitter(long baseSeconds) {
        if (baseSeconds <= 0) {
            return baseSeconds;
        }
        long span = Math.max(1L, baseSeconds / 5L);
        return baseSeconds + ThreadLocalRandom.current().nextLong(span + 1);
    }

    /**
     * Acquire mutex with owner token; returns token or null.
     */
    String tryLock(String key) {
        String token = UUID.randomUUID().toString(true);
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, token, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? token : null;
    }

    /**
     * Unlock only if this owner still holds the lock (Lua compare-and-del).
     *
     * @return true when the lock was deleted
     */
    public boolean releaseLock(String key, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long result = stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(key),
                    token);
            return result != null && result > 0;
        } catch (RuntimeException e) {
            log.warn("Failed to release lock key={}", key, e);
            return false;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
