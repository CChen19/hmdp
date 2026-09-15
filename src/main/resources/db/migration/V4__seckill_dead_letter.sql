-- V4: poison / permanent-failure isolation for seckill Stream messages.
-- Replay: re-XADD payload to stream.orders (see SeckillDeadLetterService / docs).

CREATE TABLE IF NOT EXISTS `seckill_dead_letter` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stream_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'Redis Stream record id',
  `order_id` bigint(20) NULL DEFAULT NULL COMMENT '订单 id（若可解析）',
  `user_id` bigint(20) NULL DEFAULT NULL COMMENT '用户 id（若可解析）',
  `voucher_id` bigint(20) NULL DEFAULT NULL COMMENT '优惠券 id（若可解析）',
  `payload` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '原始消息字段',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '失败原因',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '隔离时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_order_id`(`order_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '秒杀毒消息死信' ROW_FORMAT = Compact;
