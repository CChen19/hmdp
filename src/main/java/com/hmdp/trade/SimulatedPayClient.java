package com.hmdp.trade;

/**
 * Explicitly simulated payment — not WeChat / Alipay / wallet.
 * Always "succeeds" when invoked; real status flip is done with a conditional UPDATE.
 */
public class SimulatedPayClient {

    /**
     * @return pay type code written on the order (schema: 1 = balance-style simulated)
     */
    public int simulatePaySuccess(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId required");
        }
        return TradeConstants.SIMULATED_PAY_TYPE;
    }
}
