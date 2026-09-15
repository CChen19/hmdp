package com.hmdp.seckill;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.SeckillDeadLetter;
import com.hmdp.mapper.SeckillDeadLetterMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Persist poison messages and provide a documented replay entry point.
 */
@Slf4j
@Service
public class SeckillDeadLetterService extends ServiceImpl<SeckillDeadLetterMapper, SeckillDeadLetter> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void isolate(String streamId, Long orderId, Long userId, Long voucherId,
                        String payload, String errorMsg) {
        SeckillDeadLetter row = new SeckillDeadLetter();
        row.setStreamId(streamId);
        row.setOrderId(orderId);
        row.setUserId(userId);
        row.setVoucherId(voucherId);
        row.setPayload(payload == null ? "" : truncate(payload, 1000));
        row.setErrorMsg(errorMsg == null ? "unknown" : truncate(errorMsg, 500));
        row.setCreateTime(LocalDateTime.now());
        save(row);
        log.warn("seckill dead-letter id={} streamId={} orderId={} err={}",
                row.getId(), streamId, orderId, errorMsg);
    }

    /**
     * Replay entry point: re-XADD the stored payload fields to {@code stream.orders}.
     * Call from an admin shell / temporary endpoint after fixing root cause.
     *
     * <pre>
     *   seckillDeadLetterService.replay(deadLetterRowId);
     * </pre>
     */
    public void replay(Long deadLetterId) {
        SeckillDeadLetter row = getById(deadLetterId);
        if (row == null) {
            throw new IllegalArgumentException("dead letter not found: " + deadLetterId);
        }
        Map<String, String> fields = parsePayload(row.getPayload());
        if (fields.isEmpty()) {
            // fall back to columns
            if (row.getUserId() != null) {
                fields.put("userId", String.valueOf(row.getUserId()));
            }
            if (row.getVoucherId() != null) {
                fields.put("voucherId", String.valueOf(row.getVoucherId()));
            }
            if (row.getOrderId() != null) {
                fields.put("id", String.valueOf(row.getOrderId()));
            }
        }
        if (!fields.containsKey("userId") || !fields.containsKey("voucherId") || !fields.containsKey("id")) {
            throw new IllegalStateException("cannot replay incomplete payload id=" + deadLetterId);
        }
        MapRecord<String, String, String> record = StreamRecords.mapBacked(fields)
                .withStreamKey(RedisConstants.SECKILL_STREAM_KEY);
        RecordId newId = stringRedisTemplate.opsForStream().add(record);
        log.info("replayed dead-letter {} as stream id {}", deadLetterId, newId);
    }

    private static Map<String, String> parsePayload(String payload) {
        Map<String, String> map = new HashMap<String, String>();
        if (payload == null || payload.isEmpty()) {
            return map;
        }
        // format: k=v,k=v
        String[] parts = payload.split(",");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
