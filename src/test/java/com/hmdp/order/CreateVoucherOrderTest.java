package com.hmdp.order;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.metrics.BusinessMeters;
import com.hmdp.seckill.SeckillConsumeGate;
import com.hmdp.seckill.SeckillDeadLetterService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Phase 2 order create / retry / owner query (no live Redis Stream).
 */
@ExtendWith(MockitoExtension.class)
class CreateVoucherOrderTest {

    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private SeckillConsumeGate seckillConsumeGate;
    @Mock
    private SeckillDeadLetterService seckillDeadLetterService;
    @Mock
    private BusinessMeters businessMeters;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    private VoucherOrder order;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(voucherOrderService, "baseMapper", voucherOrderMapper);
        order = new VoucherOrder();
        order.setId(10001L);
        order.setUserId(42L);
        order.setVoucherId(9001L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void stockUpdateFalse_doesNotSave() {
        when(voucherOrderMapper.selectById(10001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillVoucherService.update(any())).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> voucherOrderService.createVoucherOrder(order));

        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void stockUpdateTrue_savesOrder() {
        when(voucherOrderMapper.selectById(10001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        when(seckillVoucherService.update(any())).thenReturn(true);
        when(voucherOrderMapper.insert(order)).thenReturn(1);

        voucherOrderService.createVoucherOrder(order);

        verify(voucherOrderMapper).insert(order);
    }

    @Test
    void existingOrderId_skipsSecondDecrement() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(10001L);
        existing.setUserId(42L);
        existing.setVoucherId(9001L);
        when(voucherOrderMapper.selectById(10001L)).thenReturn(existing);

        voucherOrderService.createVoucherOrder(order);

        verify(seckillVoucherService, never()).update(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void existingUserVoucher_skipsSecondDecrement() {
        when(voucherOrderMapper.selectById(10001L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(1L);

        voucherOrderService.createVoucherOrder(order);

        verify(seckillVoucherService, never()).update(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void duplicateLua_returnsOriginalOrderIdFromRedisMap() {
        when(seckillConsumeGate.isReady()).thenReturn(true);
        com.hmdp.entity.SeckillVoucher voucher = new com.hmdp.entity.SeckillVoucher();
        voucher.setVoucherId(9001L);
        voucher.setBeginTime(java.time.LocalDateTime.now().minusHours(1));
        voucher.setEndTime(java.time.LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getById(9001L)).thenReturn(voucher);
        when(redisIdWorker.nextId("order")).thenReturn(99999L);
        when(stringRedisTemplate.<Long>execute(
                org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<java.util.List<String>>any(),
                any(), any(), any(), any())).thenReturn(2L);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(eq(RedisConstants.SECKILL_ORDER_ID_MAP_KEY + "9001"), eq("42")))
                .thenReturn("10001");

        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(9001L);

        assertTrue(result.getSuccess());
        assertEquals(10001L, ((Number) result.getData()).longValue());
    }

    @Test
    void queryOrder_rejectsOtherUser() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(10001L);
        existing.setUserId(42L);
        existing.setVoucherId(9001L);
        existing.setStatus(1);
        when(voucherOrderMapper.selectById(10001L)).thenReturn(existing);

        UserDTO other = new UserDTO();
        other.setId(99L);
        UserHolder.saveUser(other);

        Result result = voucherOrderService.queryOrderById(10001L);

        assertFalse(result.getSuccess());
        assertEquals("无权查看该订单", result.getErrorMsg());
    }

    @Test
    void queryOrder_ownerSeesSuccess() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(10001L);
        existing.setUserId(42L);
        existing.setVoucherId(9001L);
        existing.setStatus(1);
        when(voucherOrderMapper.selectById(10001L)).thenReturn(existing);

        UserDTO owner = new UserDTO();
        owner.setId(42L);
        UserHolder.saveUser(owner);

        Result result = voucherOrderService.queryOrderById(10001L);

        assertTrue(result.getSuccess());
        com.hmdp.dto.OrderQueryVO vo = (com.hmdp.dto.OrderQueryVO) result.getData();
        assertEquals("SUCCESS", vo.getState());
    }
}
