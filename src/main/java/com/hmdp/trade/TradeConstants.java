package com.hmdp.trade;

/**
 * Phase 3 trade constants. Not a real payment gateway.
 */
public final class TradeConstants {
    /**
     * Unpaid orders older than this are closed by the timeout job.
     * Measured from {@code create_time} (not pay_time).
     */
    public static final int PAY_TIMEOUT_MINUTES = 15;

    /** Outbox row still waiting for Redis INCR. */
    public static final int OUTBOX_PENDING = 0;
    /** Outbox Redis stock release applied (or skipped as duplicate). */
    public static final int OUTBOX_DONE = 1;

    /** Simulated pay type written on success (schema: 1 = balance). */
    public static final int SIMULATED_PAY_TYPE = 1;

    /** Batch size for unpaid timeout scan. */
    public static final int TIMEOUT_SCAN_BATCH = 50;

    /** Batch size for outbox poller. */
    public static final int OUTBOX_SCAN_BATCH = 50;

    private TradeConstants() {
    }
}
