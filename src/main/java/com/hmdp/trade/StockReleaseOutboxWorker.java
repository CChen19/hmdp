package com.hmdp.trade;

import com.hmdp.entity.StockReleaseOutbox;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * Applies Redis {@code INCR seckill:stock:{voucherId}} for cancel outbox events.
 * Idempotent via atomic Lua (SETNX + INCR) — replaying the same event does not double-INCR,
 * and a crash cannot leave the done key set without having incremented.
 */
@Slf4j
@Component
public class StockReleaseOutboxWorker {

    private static final DefaultRedisScript<Long> STOCK_RELEASE_SCRIPT;

    static {
        STOCK_RELEASE_SCRIPT = new DefaultRedisScript<Long>();
        STOCK_RELEASE_SCRIPT.setLocation(new ClassPathResource("stock_release.lua"));
        STOCK_RELEASE_SCRIPT.setResultType(Long.class);
    }

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
     * Only marks DONE after Redis Lua succeeds (SETNX+INCR atomic, or already applied).
     *
     * @return true if Redis INCR ran on this call (SETNX won inside Lua)
     */
    public boolean applyOnce(StockReleaseOutbox row) {
        if (row == null || row.getEventKey() == null || row.getVoucherId() == null) {
            return false;
        }
        String doneKey = RedisConstants.STOCK_RELEASE_DONE_KEY + row.getEventKey();
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + row.getVoucherId();
        Long result = stringRedisTemplate.execute(
                STOCK_RELEASE_SCRIPT,
                Arrays.asList(doneKey, stockKey));
        // Lua failed / Redis error → do not markDone; next poll retries
        if (result == null) {
            throw new IllegalStateException("stock_release.lua returned null for eventKey=" + row.getEventKey());
        }
        boolean incrRan = result == 1L;
        tradeOrderService.markOutboxDone(row.getId());
        return incrRan;
    }
}
