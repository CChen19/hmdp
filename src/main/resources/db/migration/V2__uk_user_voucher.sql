-- Unique one-order-per-user-per-voucher (aligns with orderfix change to hmdp.sql).
-- Safe if uk_user_voucher already exists (e.g. DB created/updated from post-orderfix hmdp.sql).
-- DBs migrated only through V1 get the key applied here.

SET @uk_exists := (
  SELECT COUNT(1)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_voucher_order'
    AND index_name = 'uk_user_voucher'
);

SET @uk_sql := IF(
  @uk_exists = 0,
  'ALTER TABLE `tb_voucher_order` ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`)',
  'SELECT 1'
);

PREPARE uk_stmt FROM @uk_sql;
EXECUTE uk_stmt;
DEALLOCATE PREPARE uk_stmt;
