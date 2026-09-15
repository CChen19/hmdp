package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Async seckill order result view for GET /voucher-order/{id}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderQueryVO {
    /** PROCESSING | SUCCESS | FAILED */
    private String state;
    private Long orderId;
    private Long voucherId;
    private Integer status;
}
