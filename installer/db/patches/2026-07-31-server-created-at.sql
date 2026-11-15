-- server.created_at 추가 (등록일)
--
-- 왜 기본값을 주지 않는가
--   DEFAULT CURRENT_TIMESTAMP 로 추가하면 이미 있던 행이 전부 "이 SQL 을 돌린 시각" 으로 채워진다.
--   화면에는 그럴듯한 날짜가 뜨지만 전부 틀린 값이다 — 없는 것보다 나쁘다.
--   기존 행은 NULL 로 두고 화면이 "등록일 미상" 이라고 말하게 한다.
--   새로 등록되는 서버는 앱이 INSERT 때 NOW(6) 으로 채운다(ServerRepository#insert).
--
-- 여러 번 돌려도 안전하다.

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server' AND COLUMN_NAME = 'created_at'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `server` ADD COLUMN `created_at` datetime(6) DEFAULT NULL AFTER `status`',
  'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
