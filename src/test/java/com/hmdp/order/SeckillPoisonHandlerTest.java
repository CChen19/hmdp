package com.hmdp.order;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.seckill.ConsumeOutcome;
import com.hmdp.seckill.SeckillDeadLetterService;
import com.hmdp.seckill.SeckillStreamMessageHandler;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Poison / outcome contract for Stream handler — no live Redis Stream.
 */
@ExtendWith(MockitoExtension.class)
class SeckillPoisonHandlerTest {

    @Mock
    private IVoucherOrderService voucherOrderService;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SeckillDeadLetterService deadLetterService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RLock lock;
    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private SeckillStreamMessageHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void badPayload_isPoisonAndIsolated() {
        Map<Object, Object> values = new HashMap<Object, Object>();
        values.put("userId", "not-a-number");
        values.put("voucherId", "1");
        values.put("id", "1");
        MapRecord<String, Object, Object> record =
                MapRecord.create("stream.orders", values).withId(RecordId.of("1-0"));

        ConsumeOutcome outcome = handler.handle(record, 1L);

        assertEquals(ConsumeOutcome.POISON, outcome);
        verify(deadLetterService).isolate(eq("1-0"), isNull(), isNull(), isNull(), anyString(), anyString());
        verify(voucherOrderService, never()).createVoucherOrder(any());
    }

    @Test
    void missingVoucher_isPoison() {
        MapRecord<String, Object, Object> record = sampleRecord();
        when(seckillVoucherService.getById(9001L)).thenReturn(null);

        ConsumeOutcome outcome = handler.handle(record, 1L);

        assertEquals(ConsumeOutcome.POISON, outcome);
        verify(deadLetterService).isolate(eq("1-0"), eq(10001L), eq(42L), eq(9001L), anyString(), eq("voucher not found"));
    }

    @Test
    void existingOrderPath_successNoSecondCreateFailure() {
        MapRecord<String, Object, Object> record = sampleRecord();
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(9001L);
        when(seckillVoucherService.getById(9001L)).thenReturn(voucher);
        when(lock.tryLock()).thenReturn(true);

        ConsumeOutcome outcome = handler.handle(record, 1L);

        assertEquals(ConsumeOutcome.SUCCESS, outcome);
        verify(voucherOrderService).createVoucherOrder(any(VoucherOrder.class));
        verify(deadLetterService, never()).isolate(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void maxDeliveries_isPoison() {
        MapRecord<String, Object, Object> record = sampleRecord();
        // max deliveries fires after parse, before voucher / lock

        ConsumeOutcome outcome = handler.handle(record, SeckillStreamMessageHandler.MAX_DELIVERIES);

        assertEquals(ConsumeOutcome.POISON, outcome);
        verify(deadLetterService).isolate(eq("1-0"), eq(10001L), eq(42L), eq(9001L), anyString(), anyString());
        verify(voucherOrderService, never()).createVoucherOrder(any());
    }

    private static MapRecord<String, Object, Object> sampleRecord() {
        Map<Object, Object> values = new HashMap<Object, Object>();
        values.put("userId", "42");
        values.put("voucherId", "9001");
        values.put("id", "10001");
        return MapRecord.create("stream.orders", values).withId(RecordId.of("1-0"));
    }
}
