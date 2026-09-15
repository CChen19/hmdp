-- User role column (aligns with authfix change to hmdp.sql).
-- Values: USER / MERCHANT / ADMIN; default USER.
-- Safe if `role` already exists (e.g. DB created/updated from post-authfix hmdp.sql).
-- DBs migrated only through V1/V2 get the column applied here.

SET @role_exists := (
  SELECT COUNT(1)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_user'
    AND column_name = 'role'
);

SET @role_sql := IF(
  @role_exists = 0,
  'ALTER TABLE `tb_user` ADD COLUMN `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER/MERCHANT/ADMIN'' AFTER `icon`',
  'SELECT 1'
);

PREPARE role_stmt FROM @role_sql;
EXECUTE role_stmt;
DEALLOCATE PREPARE role_stmt;
