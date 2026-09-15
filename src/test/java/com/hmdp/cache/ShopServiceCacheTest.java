package com.hmdp.cache;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopServiceCacheTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private CacheClient cacheClient;

    @Spy
    @InjectMocks
    private ShopServiceImpl shopService;

    @Test
    void queryById_missPathResult_okMapsToShop() {
        Shop shop = new Shop().setId(1L).setName("s1");
        when(cacheClient.queryWithLogicalExpire(eq(CACHE_SHOP_KEY), eq(1L), eq(Shop.class),
                any(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(CacheResult.ok(shop));

        Result result = shopService.queryById(1L);

        assertTrue(result.getSuccess());
        assertEquals(shop, result.getData());
    }

    @Test
    void queryById_notFound_returnsBusinessMessage() {
        when(cacheClient.queryWithLogicalExpire(eq(CACHE_SHOP_KEY), eq(1L), eq(Shop.class),
                any(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(CacheResult.notFound());

        Result result = shopService.queryById(1L);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在", result.getErrorMsg());
    }

    @Test
    void queryById_unavailable_doesNotSayShopMissing() {
        when(cacheClient.queryWithLogicalExpire(eq(CACHE_SHOP_KEY), eq(1L), eq(Shop.class),
                any(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(CacheResult.unavailable());

        Result result = shopService.queryById(1L);

        assertFalse(result.getSuccess());
        assertEquals("服务暂时不可用", result.getErrorMsg());
        assertNotEquals("店铺不存在", result.getErrorMsg());
    }

    @Test
    void update_invalidatesCacheAfterCommit() {
        Shop shop = new Shop().setId(10L).setName("n");
        doReturn(true).when(shopService).updateById(shop);

        TransactionSynchronizationManager.initSynchronization();
        try {
            Result result = shopService.update(shop);
            assertTrue(result.getSuccess());
            verify(stringRedisTemplate, never()).delete(CACHE_SHOP_KEY + 10L);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(stringRedisTemplate).delete(CACHE_SHOP_KEY + 10L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void update_invalidateFailure_isLoggedAndDoesNotThrow() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).delete(CACHE_SHOP_KEY + 11L);
        assertDoesNotThrow(() -> shopService.invalidateShopCache(CACHE_SHOP_KEY + 11L));
    }
}
