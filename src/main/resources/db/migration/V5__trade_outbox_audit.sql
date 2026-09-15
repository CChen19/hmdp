-- V5: Phase 3 trade — stock-release outbox, redeem audit, unpaid timeout scan index.
-- Does not drop uk_user_voucher (v1: no rebuy after cancel).

CREATE TABLE IF NOT EXISTS `stock_release_outbox` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键 / event id',
  `order_id` bigint(20) NOT NULL COMMENT '取消的订单 id',
  `voucher_id` bigint(20) NOT NULL COMMENT '要回补 Redis 库存的券 id',
  `event_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '幂等键 cancel:{orderId}',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=DONE',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
  `processed_time` timestamp NULL DEFAULT NULL COMMENT 'Redis 回补完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_event_key` (`event_key`) USING BTREE,
  INDEX `idx_status_id` (`status`, `id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '取消后 Redis 库存回补 outbox' ROW_FORMAT = Compact;

CREATE TABLE IF NOT EXISTS `voucher_order_redeem_audit` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id` bigint(20) NOT NULL COMMENT '订单 id',
  `shop_id` bigint(20) NOT NULL COMMENT '核销店铺 id',
  `voucher_id` bigint(20) NOT NULL COMMENT '券 id',
  `operator_user_id` bigint(20) NOT NULL COMMENT '核销人 user id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '核销时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_id` (`order_id`) USING BTREE,
  INDEX `idx_shop_id` (`shop_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '商户核销审计' ROW_FORMAT = Compact;

-- Index-friendly unpaid timeout scan: status + create_time
SET @idx_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_voucher_order'
    AND index_name = 'idx_voucher_order_unpaid_ctime'
);

SET @idx_sql := IF(
  @idx_exists = 0,
  'ALTER TABLE `tb_voucher_order` ADD INDEX `idx_voucher_order_unpaid_ctime` (`status`, `create_time`)',
  'SELECT 1'
);

PREPARE idx_stmt FROM @idx_sql;
EXECUTE idx_stmt;
DEALLOCATE PREPARE idx_stmt;
