package com.hmdp.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 3 v1: simulated pay, timeout/manual cancel + outbox stock release, merchant redeem.
 * Pay and cancel race on {@code UPDATE … WHERE status=UNPAID}; only one wins.
 */
@Slf4j
@Service
public class TradeOrderService extends ServiceImpl<VoucherOrderMapper, VoucherOrder> {

    @Resource
    private SimulatedPayClient simulatedPayClient;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private StockReleaseOutboxMapper stockReleaseOutboxMapper;
    @Resource
    private VoucherOrderRedeemAuditMapper redeemAuditMapper;
    /** Self-proxy so {@link #cancelUnpaidOrder} runs inside a Spring TX from same-class callers. */
    @Lazy
    @Resource
    private TradeOrderService self;

    /**
     * Owner-only simulated pay. Idempotent if already paid.
     */
    public Result payOrder(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!user.getId().equals(order.getUserId())) {
            return Result.fail("无权支付该订单");
        }
        if (Integer.valueOf(VoucherOrderStatus.PAID).equals(order.getStatus())) {
            return Result.ok(); // already paid — idempotent
        }
        if (Integer.valueOf(VoucherOrderStatus.CANCELLED).equals(order.getStatus())) {
            return Result.fail("订单已取消");
        }
        if (Integer.valueOf(VoucherOrderStatus.USED).equals(order.getStatus())) {
            return Result.fail("订单已核销");
        }
        if (!Integer.valueOf(VoucherOrderStatus.UNPAID).equals(order.getStatus())) {
            return Result.fail("订单状态不可支付");
        }

        int payType = simulatedPayClient.simulatePaySuccess(orderId);
        LocalDateTime payTime = LocalDateTime.now();
        boolean won = update(new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.UNPAID)
                .set(VoucherOrder::getStatus, VoucherOrderStatus.PAID)
                .set(VoucherOrder::getPayTime, payTime)
                .set(VoucherOrder::getPayType, payType));
        if (won) {
            return Result.ok();
        }
        // Lost race (timeout cancel or concurrent pay) — re-read
        VoucherOrder again = getById(orderId);
        if (again != null && Integer.valueOf(VoucherOrderStatus.PAID).equals(again.getStatus())) {
            return Result.ok();
        }
        return Result.fail("支付失败，订单已取消或状态已变更");
    }

    /**
     * Cancel unpaid order + DB stock++ + outbox insert in one TX.
     * Conditional on status=UNPAID. Does not touch Redis here.
     * Does not clear seckill:order set — v1 forbids rebuy after cancel.
     *
     * @return true if this call flipped UNPAID→CANCELLED (stock released at most once)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelUnpaidOrder(Long orderId) {
        if (orderId == null) {
            return false;
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return false;
        }
        if (Integer.valueOf(VoucherOrderStatus.CANCELLED).equals(order.getStatus())) {
            return false; // already cancelled — idempotent no-op
        }
        boolean cancelled = update(new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.UNPAID)
                .set(VoucherOrder::getStatus, VoucherOrderStatus.CANCELLED));
        if (!cancelled) {
            return false;
        }
        // DB stock release (paired with Redis via outbox)
        boolean stockOk = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, order.getVoucherId())
                        .setSql("stock=stock+1"));
        if (!stockOk) {
            // Seckill row missing — still cancel order but fail TX so ops notice
            throw new IllegalStateException("seckill voucher missing for stock release orderId=" + orderId);
        }
        StockReleaseOutbox outbox = new StockReleaseOutbox();
        outbox.setOrderId(orderId);
        outbox.setVoucherId(order.getVoucherId());
        outbox.setEventKey(eventKeyForCancel(orderId));
        outbox.setStatus(TradeConstants.OUTBOX_PENDING);
        outbox.setCreateTime(LocalDateTime.now());
        if (stockReleaseOutboxMapper.insert(outbox) < 1) {
            throw new IllegalStateException("outbox insert failed orderId=" + orderId);
        }
        return true;
    }

    /**
     * Public cancel for owner (optional) or timeout job. Idempotent success if already cancelled.
     */
    public Result cancelOrder(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!user.getId().equals(order.getUserId())) {
            return Result.fail("无权取消该订单");
        }
        if (Integer.valueOf(VoucherOrderStatus.CANCELLED).equals(order.getStatus())) {
            return Result.ok();
        }
        if (!Integer.valueOf(VoucherOrderStatus.UNPAID).equals(order.getStatus())) {
            return Result.fail("仅待支付订单可取消");
        }
        boolean won = self.cancelUnpaidOrder(orderId);
        if (won) {
            return Result.ok();
        }
        VoucherOrder again = getById(orderId);
        if (again != null && Integer.valueOf(VoucherOrderStatus.CANCELLED).equals(again.getStatus())) {
            return Result.ok();
        }
        return Result.fail("取消失败，订单已支付或状态已变更");
    }

    /**
     * Merchant/ADMIN redeem for a shop. Idempotent if already used.
     * No use-window beyond seckill activity (documented in TRADE-FLOW.md).
     */
    @Transactional(rollbackFor = Exception.class)
    public Result redeemOrder(Long orderId, Long shopId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        if (!UserRole.isPrivileged(UserRole.normalize(user.getRole()))) {
            return Result.fail("需要商户权限");
        }
        if (shopId == null) {
            return Result.fail("缺少店铺 id");
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (Integer.valueOf(VoucherOrderStatus.USED).equals(order.getStatus())) {
            return Result.ok(); // already redeemed
        }
        if (!Integer.valueOf(VoucherOrderStatus.PAID).equals(order.getStatus())) {
            return Result.fail("仅已支付订单可核销");
        }
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher == null || voucher.getShopId() == null) {
            return Result.fail("优惠券不存在");
        }
        if (!shopId.equals(voucher.getShopId())) {
            return Result.fail("优惠券不属于该店铺");
        }

        LocalDateTime useTime = LocalDateTime.now();
        boolean flipped = update(new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.PAID)
                .set(VoucherOrder::getStatus, VoucherOrderStatus.USED)
                .set(VoucherOrder::getUseTime, useTime));
        if (!flipped) {
            VoucherOrder again = getById(orderId);
            if (again != null && Integer.valueOf(VoucherOrderStatus.USED).equals(again.getStatus())) {
                return Result.ok();
            }
            return Result.fail("核销失败，订单状态已变更");
        }
        VoucherOrderRedeemAudit audit = new VoucherOrderRedeemAudit();
        audit.setOrderId(orderId);
        audit.setShopId(shopId);
        audit.setVoucherId(order.getVoucherId());
        audit.setOperatorUserId(user.getId());
        audit.setCreateTime(useTime);
        try {
            redeemAuditMapper.insert(audit);
        } catch (Exception e) {
            // uk_order_id: concurrent second redeem after flip — treat as idempotent
            log.info("redeem audit insert conflict orderId={}: {}", orderId, e.getMessage());
            VoucherOrderRedeemAudit existing = redeemAuditMapper.selectOne(
                    new LambdaQueryWrapper<VoucherOrderRedeemAudit>()
                            .eq(VoucherOrderRedeemAudit::getOrderId, orderId));
            if (existing == null) {
                throw e;
            }
        }
        return Result.ok();
    }

    /**
     * Scan unpaid orders past timeout and cancel (multi-instance safe via conditional update).
     */
    public int closeTimedOutUnpaidOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(TradeConstants.PAY_TIMEOUT_MINUTES);
        List<VoucherOrder> batch = lambdaQuery()
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.UNPAID)
                .lt(VoucherOrder::getCreateTime, deadline)
                .orderByAsc(VoucherOrder::getCreateTime)
                .last("LIMIT " + TradeConstants.TIMEOUT_SCAN_BATCH)
                .list();
        int closed = 0;
        for (VoucherOrder o : batch) {
            try {
                if (self.cancelUnpaidOrder(o.getId())) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("timeout cancel failed orderId={}: {}", o.getId(), e.getMessage());
            }
        }
        return closed;
    }

    public static String eventKeyForCancel(Long orderId) {
        return "cancel:" + orderId;
    }

    /** Pending outbox rows for the stock-release worker. */
    public List<StockReleaseOutbox> listPendingOutbox(int limit) {
        return stockReleaseOutboxMapper.selectList(
                new LambdaQueryWrapper<StockReleaseOutbox>()
                        .eq(StockReleaseOutbox::getStatus, TradeConstants.OUTBOX_PENDING)
                        .orderByAsc(StockReleaseOutbox::getId)
                        .last("LIMIT " + limit));
    }

    /**
     * Mark outbox DONE after Redis apply (or duplicate SETNX miss).
     *
     * @return true if this call flipped PENDING→DONE
     */
    public boolean markOutboxDone(Long outboxId) {
        StockReleaseOutbox patch = new StockReleaseOutbox();
        patch.setStatus(TradeConstants.OUTBOX_DONE);
        patch.setProcessedTime(LocalDateTime.now());
        int n = stockReleaseOutboxMapper.update(patch,
                new LambdaUpdateWrapper<StockReleaseOutbox>()
                        .eq(StockReleaseOutbox::getId, outboxId)
                        .eq(StockReleaseOutbox::getStatus, TradeConstants.OUTBOX_PENDING));
        return n > 0;
    }
}
