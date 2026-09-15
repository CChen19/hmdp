package com.hmdp.order;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.seckill.ConsumeOutcome;
import com.hmdp.seckill.SeckillDeadLetterService;
import com.hmdp.seckill.SeckillOrderStreamConsumer;
import com.hmdp.seckill.SeckillStreamMessageHandler;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pending recover must not tight-loop RETRY into max-deliveries poison+ACK.
 */
@ExtendWith(MockitoExtension.class)
class PendingRecoverRetryTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SeckillDeadLetterService deadLetterService;
    @Mock
    private RLock lock;

    private SeckillStreamMessageHandler handler;
    private SeckillOrderStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        handler = new SeckillStreamMessageHandler();
        ReflectionTestUtils.setField(handler, "voucherOrderService", voucherOrderService);
        ReflectionTestUtils.setField(handler, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(handler, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(handler, "deadLetterService", deadLetterService);
        ReflectionTestUtils.setField(handler, "stringRedisTemplate", stringRedisTemplate);

        consumer = new SeckillOrderStreamConsumer();
        ReflectionTestUtils.setField(consumer, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(consumer, "messageHandler", handler);
        consumer.setConsumerNameForTest("test-consumer");
        consumer.markRunningForTest(true);

        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void recoverOwnPending_retryOncePerScan_doesNotPoisonAtDeliveryFour() {
        RecordId id = RecordId.of("1-0");
        PendingMessage pm = mock(PendingMessage.class);
        when(pm.getId()).thenReturn(id);
        when(pm.getTotalDeliveryCount()).thenReturn(4L);
        PendingMessages pending = mock(PendingMessages.class);
        when(pending.isEmpty()).thenReturn(false);
        when(pending.iterator()).thenReturn(Collections.singletonList(pm).iterator());

        when(streamOperations.pending(
                eq(RedisConstants.SECKILL_STREAM_KEY),
                any(Consumer.class),
                any(Range.class),
                eq(50L))).thenReturn(pending);

        Map<Object, Object> values = new HashMap<Object, Object>();
        values.put("userId", "42");
        values.put("voucherId", "9001");
        values.put("id", "10001");
        MapRecord<String, Object, Object> body =
                MapRecord.create(RedisConstants.SECKILL_STREAM_KEY, values).withId(id);
        when(streamOperations.range(eq(RedisConstants.SECKILL_STREAM_KEY), any(Range.class)))
                .thenReturn(Collections.singletonList(body));

        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(9001L);
        when(seckillVoucherService.getById(9001L)).thenReturn(voucher);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false); // transient → RETRY

        // Two recover scans (~15s apart): each touches the id once at delivery=4
        consumer.recoverOwnPending();
        when(pending.iterator()).thenReturn(Collections.singletonList(pm).iterator());
        consumer.recoverOwnPending();

        verify(deadLetterService, never()).isolate(anyString(), any(), any(), any(), anyString(), anyString());
        verify(streamOperations, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        // Own-pending recover must not XREADGROUP (which would bump delivery in a tight loop)
        verify(streamOperations, never()).read(any(Consumer.class), any(), any());
        // Once per scan, not five burst attempts
        verify(lock, times(2)).tryLock();
    }

    @Test
    void maxDeliveriesStillPoisonsWhenPelCountAlreadyHigh() {
        Map<Object, Object> values = new HashMap<Object, Object>();
        values.put("userId", "42");
        values.put("voucherId", "9001");
        values.put("id", "10001");
        MapRecord<String, Object, Object> record =
                MapRecord.create(RedisConstants.SECKILL_STREAM_KEY, values).withId(RecordId.of("1-0"));

        ConsumeOutcome outcome = handler.handle(record, SeckillStreamMessageHandler.MAX_DELIVERIES);

        assertEquals(ConsumeOutcome.POISON, outcome);
        verify(deadLetterService).isolate(eq("1-0"), eq(10001L), eq(42L), eq(9001L), anyString(), anyString());
    }
}
