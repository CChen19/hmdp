package com.hmdp.seckill;

/**
 * Outcome of handling one Stream record (unit-testable contract).
 */
public enum ConsumeOutcome {
    /** Persist ok or already done — ACK. */
    SUCCESS,
    /** Transient — leave pending, retry later. */
    RETRY,
    /** Permanent / poison — dead-letter then ACK. */
    POISON
}
