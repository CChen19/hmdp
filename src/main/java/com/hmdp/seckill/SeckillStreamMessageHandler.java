package com.hmdp.seckill;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Classifies and applies one seckill Stream message (no ACK here).
 */
@Slf4j
@Component
public class SeckillStreamMessageHandler {

    /** After this many deliveries, treat as poison even if error looks transient. */
    public static final long MAX_DELIVERIES = 5L;

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillDeadLetterService deadLetterService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Handle one record. Caller ACKs on SUCCESS or POISON; leaves pending on RETRY.
     */
    public ConsumeOutcome handle(MapRecord<String, Object, Object> record, long deliveryCount) {
        String streamId = record.getId() == null ? null : record.getId().getValue();
        Map<Object, Object> values = record.getValue();
        String payload = formatPayload(values);

        VoucherOrder voucherOrder;
        try {
            voucherOrder = parseOrder(values);
        } catch (PoisonMessageException e) {
            deadLetterService.isolate(streamId, null, null, null, payload, e.getMessage());
            return ConsumeOutcome.POISON;
        }

        if (deliveryCount >= MAX_DELIVERIES) {
            deadLetterService.isolate(streamId, voucherOrder.getId(), voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(), payload,
                    "max deliveries exceeded: " + deliveryCount);
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.POISON;
        }

        // Permanent: voucher row missing (will never succeed)
        SeckillVoucher voucher = seckillVoucherService.getById(voucherOrder.getVoucherId());
        if (voucher == null) {
            deadLetterService.isolate(streamId, voucherOrder.getId(), voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(), payload, "voucher not found");
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.POISON;
        }

        try {
            applyWithLock(voucherOrder);
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.SUCCESS;
        } catch (DuplicateKeyException e) {
            // Unique (user, voucher) — treat as success, no second logical order
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.SUCCESS;
        } catch (PoisonMessageException e) {
            deadLetterService.isolate(streamId, voucherOrder.getId(), voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(), payload, e.getMessage());
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.POISON;
        } catch (DataAccessException e) {
            log.warn("transient DB error on stream {}: {}", streamId, e.getMessage());
            return ConsumeOutcome.RETRY;
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                log.warn("transient error on stream {}: {}", streamId, e.getMessage());
                return ConsumeOutcome.RETRY;
            }
            deadLetterService.isolate(streamId, voucherOrder.getId(), voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(), payload, e.getMessage());
            clearProcessingHints(voucherOrder);
            return ConsumeOutcome.POISON;
        }
    }

    private void applyWithLock(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new TransientConsumeException("order lock busy for user " + userId);
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    static VoucherOrder parseOrder(Map<Object, Object> values) {
        if (values == null || values.isEmpty()) {
            throw new PoisonMessageException("empty stream payload");
        }
        Object idObj = first(values, "id");
        Object userObj = first(values, "userId");
        Object voucherObj = first(values, "voucherId");
        if (idObj == null || userObj == null || voucherObj == null) {
            throw new PoisonMessageException("missing id/userId/voucherId");
        }
        try {
            VoucherOrder order = new VoucherOrder();
            order.setId(Long.valueOf(String.valueOf(idObj)));
            order.setUserId(Long.valueOf(String.valueOf(userObj)));
            order.setVoucherId(Long.valueOf(String.valueOf(voucherObj)));
            // also tolerate BeanUtil path for extra fields
            BeanUtil.fillBeanWithMap(values, order, true);
            if (order.getId() == null || order.getUserId() == null || order.getVoucherId() == null) {
                throw new PoisonMessageException("unparseable id/userId/voucherId");
            }
            return order;
        } catch (NumberFormatException e) {
            throw new PoisonMessageException("bad numeric fields: " + e.getMessage());
        }
    }

    private static Object first(Map<Object, Object> values, String key) {
        if (values.containsKey(key)) {
            return values.get(key);
        }
        // Redis may deserialize keys as byte-ish strings already
        for (Map.Entry<Object, Object> e : values.entrySet()) {
            if (key.equals(String.valueOf(e.getKey()))) {
                return e.getValue();
            }
        }
        return null;
    }

    private void clearProcessingHints(VoucherOrder order) {
        if (order == null || order.getUserId() == null || order.getId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForSet().remove(
                    RedisConstants.SECKILL_PROCESSING_KEY + order.getUserId(),
                    String.valueOf(order.getId()));
            stringRedisTemplate.delete(RedisConstants.SECKILL_ACCEPT_KEY + order.getId());
        } catch (Exception e) {
            log.debug("clear processing hints failed: {}", e.getMessage());
        }
    }

    static String formatPayload(Map<Object, Object> values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, Object> e : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static boolean isTransient(RuntimeException e) {
        if (e instanceof TransientConsumeException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("deadlock")
                || lower.contains("lock")
                || lower.contains("库存不足")
                || lower.contains("connection");
    }

    /** Marker for poison payloads. */
    public static class PoisonMessageException extends RuntimeException {
        public PoisonMessageException(String message) {
            super(message);
        }
    }

    /** Marker for retryable failures. */
    public static class TransientConsumeException extends RuntimeException {
        public TransientConsumeException(String message) {
            super(message);
        }
    }
}
