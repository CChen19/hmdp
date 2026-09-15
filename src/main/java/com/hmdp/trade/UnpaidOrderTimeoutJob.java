package com.hmdp.trade;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * Batch-closes unpaid orders older than {@link TradeConstants#PAY_TIMEOUT_MINUTES}.
 * Multi-instance safe: each cancel uses {@code UPDATE … WHERE status=UNPAID}.
 */
@Slf4j
@Component
public class UnpaidOrderTimeoutJob {

    @Resource
    private TradeOrderService tradeOrderService;

    @Scheduled(fixedDelay = 30_000L)
    public void closeTimedOut() {
        int closed = tradeOrderService.closeTimedOutUnpaidOrders();
        if (closed > 0) {
            log.info("unpaid timeout closed {} orders", closed);
        }
    }
}
