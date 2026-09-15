package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.trade.TradeOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private TradeOrderService tradeOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /** List current user's orders (login required). Literal path before {id}. */
    @GetMapping("/mine")
    public Result myOrders() {
        return voucherOrderService.listMyOrders();
    }

    /**
     * Simulated pay (not WeChat/Alipay). Login + owner. Conditional UNPAID→PAID.
     */
    @PostMapping("/{id}/pay")
    public Result pay(@PathVariable("id") Long id) {
        return tradeOrderService.payOrder(id);
    }

    /**
     * Owner cancel unpaid order (releases DB stock + outbox for Redis).
     */
    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable("id") Long id) {
        return tradeOrderService.cancelOrder(id);
    }

    /**
     * Merchant/ADMIN redeem. Requires {@code shopId}; voucher must belong to that shop.
     */
    @PostMapping("/{id}/redeem")
    public Result redeem(@PathVariable("id") Long id, @RequestParam("shopId") Long shopId) {
        return tradeOrderService.redeemOrder(id, shopId);
    }

    /** Owner-only order query: PROCESSING / SUCCESS / FAILED. */
    @GetMapping("/{id}")
    public Result queryOrder(@PathVariable("id") Long id) {
        return voucherOrderService.queryOrderById(id);
    }
}
