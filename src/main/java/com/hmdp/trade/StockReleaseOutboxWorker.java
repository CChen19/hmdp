package com.hmdp.trade;

import com.hmdp.entity.StockReleaseOutbox;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * Applies Redis {@code INCR seckill:stock:{voucherId}} for cancel outbox events.
 * Idempotent via SETNX on event_key — replaying the same event does not double-INCR.
 */
@Slf4j
@Component
public class StockReleaseOutboxWorker {

    @Resource
    private TradeOrderService tradeOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(fixedDelay = 5_000L)
    public void pollAndApply() {
        List<StockReleaseOutbox> pending = tradeOrderService.listPendingOutbox(TradeConstants.OUTBOX_SCAN_BATCH);
        for (StockReleaseOutbox row : pending) {
            try {
                applyOnce(row);
            } catch (Exception e) {
                log.warn("outbox apply failed id={} eventKey={}: {}", row.getId(), row.getEventKey(), e.getMessage());
            }
        }
    }

    /**
     * Apply one outbox event. Safe to call twice for the same event_key.
     *
     * @return true if Redis INCR ran on this call (SETNX won)
     */
    public boolean applyOnce(StockReleaseOutbox row) {
        if (row == null || row.getEventKey() == null || row.getVoucherId() == null) {
            return false;
        }
        String doneKey = RedisConstants.STOCK_RELEASE_DONE_KEY + row.getEventKey();
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(doneKey, "1");
        boolean incrRan = Boolean.TRUE.equals(first);
        if (incrRan) {
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + row.getVoucherId());
        }
        tradeOrderService.markOutboxDone(row.getId());
        return incrRan;
    }
}
