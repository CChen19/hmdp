package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Cancel → Redis stock INCR outbox row (same MySQL TX as order cancel + DB stock++).
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("stock_release_outbox")
public class StockReleaseOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long voucherId;

    /** Idempotent key, e.g. cancel:{orderId}. */
    private String eventKey;

    /** 0=PENDING, 1=DONE */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime processedTime;
}
