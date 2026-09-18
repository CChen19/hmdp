package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheResult;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        CacheResult<Shop> result = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (result.getStatus() == CacheResult.Status.UNAVAILABLE) {
            return Result.fail("服务暂时不可用");
        }
        if (result.getStatus() == CacheResult.Status.NOT_FOUND || result.getData() == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(result.getData());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        updateById(shop);
        final String cacheKey = CACHE_SHOP_KEY + id;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateShopCache(cacheKey);
                }
            });
        } else {
            invalidateShopCache(cacheKey);
        }
        return Result.ok();
    }

    /**
     * Delete shop display cache after a successful commit. On failure, log and rely on logical TTL.
     */
    public void invalidateShopCache(String cacheKey) {
        try {
            stringRedisTemplate.delete(cacheKey);
        } catch (RuntimeException e) {
            log.warn("Shop cache invalidate failed for key={}, relying on TTL", cacheKey, e);
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = lambdaQuery()
                    .eq(Shop::getTypeId, typeId)
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key
                        , GeoReference.fromCoordinate(x, y)
                        , new Distance(5000)
                        , RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() < from) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        content.stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        if (ids.isEmpty()) {
            // Empty IN () / field(id,) is invalid SQL — return an empty page instead.
            // Reachable when shop:geo:{typeId} is missing or all shops are outside 5km.
            return Result.ok(Collections.emptyList());
        }
        String join = StrUtil.join(",", ids);
        List<Shop> shopList = lambdaQuery().in(Shop::getId, ids).last("order by field(id," + join + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }
}
