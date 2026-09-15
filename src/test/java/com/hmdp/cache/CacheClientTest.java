package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheResult;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);
        cacheClient = new CacheClient(stringRedisTemplate);
    }

    @Test
    void miss_dbHit_returnsShopAndWritesLogicalExpireCache() {
        Long id = 1L;
        Shop shop = new Shop().setId(id).setName("cafe");
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_SHOP_KEY + id), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> shop, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.OK, result.getStatus());
        assertEquals("cafe", result.getData().getName());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(CACHE_SHOP_KEY + id), jsonCaptor.capture());
        RedisData stored = JSONUtil.toBean(jsonCaptor.getValue(), RedisData.class);
        assertNotNull(stored.getExpireTime());
        assertTrue(stored.getExpireTime().isAfter(LocalDateTime.now()));
    }

    @Test
    void miss_dbMiss_returnsNotFoundAndWritesEmptyMarker() {
        Long id = 99L;
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_SHOP_KEY + id), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> null, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.NOT_FOUND, result.getStatus());
        verify(valueOperations).set(eq(CACHE_SHOP_KEY + id), eq(""), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void emptyMarker_returnsNotFoundWithoutDb() {
        Long id = 5L;
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenReturn("");
        AtomicReference<Boolean> dbCalled = new AtomicReference<>(false);

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> {
                    dbCalled.set(true);
                    return new Shop();
                }, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.NOT_FOUND, result.getStatus());
        assertFalse(dbCalled.get());
    }

    @Test
    void logicalExpire_returnsStaleAndTriggersRebuild() throws Exception {
        Long id = 2L;
        Shop stale = new Shop().setId(id).setName("old");
        Shop fresh = new Shop().setId(id).setName("new");
        RedisData redisData = new RedisData();
        redisData.setData(stale);
        redisData.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenReturn(JSONUtil.toJsonStr(redisData));
        when(valueOperations.setIfAbsent(eq(LOCK_SHOP_KEY + id), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> fresh, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.OK, result.getStatus());
        assertEquals("old", result.getData().getName());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, timeout(2000)).set(eq(CACHE_SHOP_KEY + id), jsonCaptor.capture());
        assertTrue(jsonCaptor.getValue().contains("new"));
    }

    @Test
    void releaseLock_doesNotDeleteLockOwnedBySomeoneElse() {
        Map<String, String> locks = new HashMap<String, String>();
        locks.put(LOCK_SHOP_KEY + 1L, "owner-a");
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any())).thenAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            String token = invocation.getArgument(2);
            String key = String.valueOf(keys.get(0));
            if (token.equals(locks.get(key))) {
                locks.remove(key);
                return 1L;
            }
            return 0L;
        });

        assertFalse(cacheClient.releaseLock(LOCK_SHOP_KEY + 1L, "owner-b"));
        assertEquals("owner-a", locks.get(LOCK_SHOP_KEY + 1L));
        assertTrue(cacheClient.releaseLock(LOCK_SHOP_KEY + 1L, "owner-a"));
        assertFalse(locks.containsKey(LOCK_SHOP_KEY + 1L));
    }

    @Test
    void redisDown_dbHit_returnsOkNotNotFound() {
        Long id = 3L;
        Shop shop = new Shop().setId(id).setName("fallback");
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenThrow(new RuntimeException("redis down"));

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> shop, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.OK, result.getStatus());
        assertEquals("fallback", result.getData().getName());
    }

    @Test
    void dbFailureOnMiss_returnsUnavailable_andDoesNotWriteEmpty() {
        Long id = 7L;
        when(valueOperations.get(CACHE_SHOP_KEY + id)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(LOCK_SHOP_KEY + id), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, ignored -> {
                    throw new RuntimeException("db down");
                }, 30L, TimeUnit.MINUTES);

        assertEquals(CacheResult.Status.UNAVAILABLE, result.getStatus());
        verify(valueOperations, never()).set(eq(CACHE_SHOP_KEY + id), eq(""), anyLong(), any());
    }
}
