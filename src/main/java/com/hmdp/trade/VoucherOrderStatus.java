package com.hmdp.trade;

/**
 * {@code tb_voucher_order.status} numbers (aligned with schema comments).
 */
public final class VoucherOrderStatus {
    /** 待支付 / 未支付 */
    public static final int UNPAID = 1;
    /** 已支付 */
    public static final int PAID = 2;
    /** 已核销 */
    public static final int USED = 3;
    /** 已取消 */
    public static final int CANCELLED = 4;

    private VoucherOrderStatus() {
    }
}
