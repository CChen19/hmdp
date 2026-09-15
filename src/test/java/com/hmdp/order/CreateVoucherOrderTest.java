package com.hmdp.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for createVoucherOrder insert-gate (mocked stock / mapper).
 * Does not start the app or touch shared Redis/DB.
 */
@ExtendWith(MockitoExtension.class)
class CreateVoucherOrderTest {

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

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

    @Test
    void stockUpdateFalse_doesNotSave() {
        when(voucherOrderMapper.selectById(10001L)).thenReturn(null);
        when(seckillVoucherService.update(any(Wrapper.class))).thenReturn(false);

        assertThrows(RuntimeException.class, () -> voucherOrderService.createVoucherOrder(order));

        verify(voucherOrderMapper, never()).insert(any());
    }

    @Test
    void stockUpdateTrue_savesOrder() {
        when(voucherOrderMapper.selectById(10001L)).thenReturn(null);
        when(seckillVoucherService.update(any(Wrapper.class))).thenReturn(true);
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

        verify(seckillVoucherService, never()).update(any(Wrapper.class));
        verify(voucherOrderMapper, never()).insert(any());
    }
}
