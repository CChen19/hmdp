package com.hmdp.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.StockReleaseOutbox;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderRedeemAudit;
import com.hmdp.mapper.StockReleaseOutboxMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderRedeemAuditMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserRole;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 trade: pay/cancel race, redeem idempotency, outbox Redis INCR once.
 */
@ExtendWith(MockitoExtension.class)
class TradeOrderServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, VoucherOrder.class);
        TableInfoHelper.initTableInfo(assistant, SeckillVoucher.class);
        TableInfoHelper.initTableInfo(assistant, StockReleaseOutbox.class);
        TableInfoHelper.initTableInfo(assistant, VoucherOrderRedeemAudit.class);
    }

    @Mock
    private SimulatedPayClient simulatedPayClient;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private IVoucherService voucherService;
    @Mock
    private StockReleaseOutboxMapper stockReleaseOutboxMapper;
    @Mock
    private VoucherOrderRedeemAuditMapper redeemAuditMapper;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private TradeOrderService tradeOrderService;

    private StockReleaseOutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tradeOrderService, "baseMapper", voucherOrderMapper);
        // self-proxy for TX path in unit tests: call real methods on same instance
        ReflectionTestUtils.setField(tradeOrderService, "self", tradeOrderService);
        outboxWorker = new StockReleaseOutboxWorker();
        ReflectionTestUtils.setField(outboxWorker, "tradeOrderService", tradeOrderService);
        ReflectionTestUtils.setField(outboxWorker, "stringRedisTemplate", stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private void login(long userId, String role) {
        UserDTO u = new UserDTO();
        u.setId(userId);
        u.setRole(role);
        UserHolder.saveUser(u);
    }

    private VoucherOrder unpaid(long id, long userId, long voucherId) {
        VoucherOrder o = new VoucherOrder();
        o.setId(id);
        o.setUserId(userId);
        o.setVoucherId(voucherId);
        o.setStatus(VoucherOrderStatus.UNPAID);
        return o;
    }

    @Test
    void payWins_cancelConditionalUpdateFails_noStockRelease() {
        login(42L, UserRole.USER);
        VoucherOrder order = unpaid(100L, 42L, 9L);
        when(voucherOrderMapper.selectById(100L)).thenReturn(order);
        when(simulatedPayClient.simulatePaySuccess(100L)).thenReturn(1);
        // pay conditional update succeeds
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);

        Result pay = tradeOrderService.payOrder(100L);
        assertTrue(pay.getSuccess());

        // cancel sees already-paid (simulate lost race: select returns PAID after pay)
        order.setStatus(VoucherOrderStatus.PAID);
        when(voucherOrderMapper.selectById(100L)).thenReturn(order);
        // cancel's conditional update (status=UNPAID) affects 0 rows
        when(voucherOrderMapper.update(any(), any())).thenReturn(0);

        boolean cancelled = tradeOrderService.cancelUnpaidOrder(100L);
        assertFalse(cancelled);
        verify(seckillVoucherService, never()).update(any());
        verify(stockReleaseOutboxMapper, never()).insert(any());
    }

    @Test
    void cancelWins_payFails_stockReleasedOnceInTx() {
        login(42L, UserRole.USER);
        VoucherOrder order = unpaid(101L, 42L, 9L);
        when(voucherOrderMapper.selectById(101L)).thenReturn(order);
        // cancel flip succeeds
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);
        when(seckillVoucherService.update(any())).thenReturn(true);
        when(stockReleaseOutboxMapper.insert(any(StockReleaseOutbox.class))).thenReturn(1);

        assertTrue(tradeOrderService.cancelUnpaidOrder(101L));

        ArgumentCaptor<StockReleaseOutbox> cap = ArgumentCaptor.forClass(StockReleaseOutbox.class);
        verify(stockReleaseOutboxMapper, times(1)).insert(cap.capture());
        assertEquals("cancel:101", cap.getValue().getEventKey());
        assertEquals(Long.valueOf(9L), cap.getValue().getVoucherId());
        verify(seckillVoucherService, times(1)).update(any());

        // pay after cancel
        order.setStatus(VoucherOrderStatus.CANCELLED);
        when(voucherOrderMapper.selectById(101L)).thenReturn(order);
        Result pay = tradeOrderService.payOrder(101L);
        assertFalse(pay.getSuccess());
    }

    @Test
    void nonOwnerPay_fails() {
        login(99L, UserRole.USER);
        when(voucherOrderMapper.selectById(100L)).thenReturn(unpaid(100L, 42L, 9L));
        Result r = tradeOrderService.payOrder(100L);
        assertFalse(r.getSuccess());
        assertEquals("无权支付该订单", r.getErrorMsg());
        verify(simulatedPayClient, never()).simulatePaySuccess(any());
    }

    @Test
    void repeatRedeem_onlyOneAudit() {
        login(7L, UserRole.MERCHANT);
        VoucherOrder paid = unpaid(200L, 42L, 9L);
        paid.setStatus(VoucherOrderStatus.PAID);
        when(voucherOrderMapper.selectById(200L)).thenReturn(paid);
        Voucher v = new Voucher();
        v.setId(9L);
        v.setShopId(55L);
        when(voucherService.getById(9L)).thenReturn(v);
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);
        when(redeemAuditMapper.insert(any(VoucherOrderRedeemAudit.class))).thenReturn(1);

        assertTrue(tradeOrderService.redeemOrder(200L, 55L).getSuccess());
        verify(redeemAuditMapper, times(1)).insert(any());

        // second call: already USED
        paid.setStatus(VoucherOrderStatus.USED);
        when(voucherOrderMapper.selectById(200L)).thenReturn(paid);
        assertTrue(tradeOrderService.redeemOrder(200L, 55L).getSuccess());
        verify(redeemAuditMapper, times(1)).insert(any()); // still once
    }

    @Test
    void wrongShopRedeem_fails() {
        login(7L, UserRole.MERCHANT);
        VoucherOrder paid = unpaid(201L, 42L, 9L);
        paid.setStatus(VoucherOrderStatus.PAID);
        when(voucherOrderMapper.selectById(201L)).thenReturn(paid);
        Voucher v = new Voucher();
        v.setId(9L);
        v.setShopId(55L);
        when(voucherService.getById(9L)).thenReturn(v);

        Result r = tradeOrderService.redeemOrder(201L, 99L);
        assertFalse(r.getSuccess());
        assertEquals("优惠券不属于该店铺", r.getErrorMsg());
        verify(voucherOrderMapper, never()).update(any(), any());
    }

    @Test
    void outboxHandler_sameEventTwice_redisIncrOnce() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        StockReleaseOutbox row = new StockReleaseOutbox();
        row.setId(1L);
        row.setOrderId(101L);
        row.setVoucherId(9L);
        row.setEventKey("cancel:101");
        row.setStatus(TradeConstants.OUTBOX_PENDING);

        String doneKey = RedisConstants.STOCK_RELEASE_DONE_KEY + "cancel:101";
        when(valueOperations.setIfAbsent(eq(doneKey), eq("1")))
                .thenReturn(true)
                .thenReturn(false);
        when(valueOperations.increment(RedisConstants.SECKILL_STOCK_KEY + "9")).thenReturn(11L);
        when(stockReleaseOutboxMapper.update(any(StockReleaseOutbox.class), any())).thenReturn(1);

        assertTrue(outboxWorker.applyOnce(row));
        assertFalse(outboxWorker.applyOnce(row));

        verify(valueOperations, times(1)).increment(RedisConstants.SECKILL_STOCK_KEY + "9");
        verify(valueOperations, times(2)).setIfAbsent(eq(doneKey), eq("1"));
    }

    @Test
    void payIdempotentWhenAlreadyPaid() {
        login(42L, UserRole.USER);
        VoucherOrder paid = unpaid(100L, 42L, 9L);
        paid.setStatus(VoucherOrderStatus.PAID);
        when(voucherOrderMapper.selectById(100L)).thenReturn(paid);
        Result r = tradeOrderService.payOrder(100L);
        assertTrue(r.getSuccess());
        verify(simulatedPayClient, never()).simulatePaySuccess(any());
    }
}
