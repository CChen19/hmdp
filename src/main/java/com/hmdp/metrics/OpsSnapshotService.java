package com.hmdp.metrics;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.StockReleaseOutbox;
import com.hmdp.mapper.StockReleaseOutboxMapper;
import com.hmdp.seckill.SeckillConsumeGate;
import com.hmdp.trade.TradeConstants;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refreshes stream/outbox/gate gauges and builds the thin ops snapshot JSON.
 */
@Slf4j
@Component
public class OpsSnapshotService {

    private static final int PENDING_SAMPLE = 50;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private StockReleaseOutboxMapper stockReleaseOutboxMapper;
    @Resource
    private SeckillConsumeGate seckillConsumeGate;
    @Resource
    private BusinessMeters businessMeters;

    @Scheduled(fixedDelay = 5000L, initialDelay = 3000L)
    public void refreshGauges() {
        try {
            refresh();
        } catch (Exception e) {
            log.debug("ops gauge refresh failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> snapshot() {
        refresh();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("gateReady", businessMeters.isGateReady());
        out.put("streamPending", businessMeters.getStreamPending());
        out.put("oldestPendingIdleMs", businessMeters.getOldestPendingIdleMs());
        out.put("outboxPending", businessMeters.getOutboxPending());
        out.put("streamKey", RedisConstants.SECKILL_STREAM_KEY);
        out.put("streamGroup", RedisConstants.SECKILL_STREAM_GROUP);
        return out;
    }

    void refresh() {
        boolean ready = seckillConsumeGate.isReady();
        businessMeters.updateGateReady(ready);

        long pending = 0L;
        long oldestIdleMs = 0L;
        try {
            PendingMessagesSummary summary = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.SECKILL_STREAM_KEY, RedisConstants.SECKILL_STREAM_GROUP);
            if (summary != null) {
                pending = summary.getTotalPendingMessages();
            }
            if (pending > 0) {
                PendingMessages sample = stringRedisTemplate.opsForStream().pending(
                        RedisConstants.SECKILL_STREAM_KEY,
                        RedisConstants.SECKILL_STREAM_GROUP,
                        Range.unbounded(),
                        (long) PENDING_SAMPLE);
                if (sample != null) {
                    for (PendingMessage pm : sample) {
                        long idle = pm.getElapsedTimeSinceLastDelivery().toMillis();
                        if (idle > oldestIdleMs) {
                            oldestIdleMs = idle;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("stream pending probe failed: {}", e.getMessage());
        }
        businessMeters.updateStreamPending(pending, oldestIdleMs);

        long outbox = 0L;
        try {
            Long count = stockReleaseOutboxMapper.selectCount(
                    new LambdaQueryWrapper<StockReleaseOutbox>()
                            .eq(StockReleaseOutbox::getStatus, TradeConstants.OUTBOX_PENDING));
            outbox = count == null ? 0L : count;
        } catch (Exception e) {
            log.debug("outbox pending probe failed: {}", e.getMessage());
        }
        businessMeters.updateOutboxPending(outbox);
    }
}
