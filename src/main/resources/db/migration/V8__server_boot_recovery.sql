-- ★ V7 과 같은 이유 — 이 컬럼들은 V1__baseline.sql 에도 있다.
--   IF NOT EXISTS 를 빼면 신규 설치에서 ERROR 1060 으로 Flyway 가 멈춘다.
ALTER TABLE server
    ADD COLUMN IF NOT EXISTS auto_start BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS startup_service VARCHAR(160) NULL;

CREATE INDEX IF NOT EXISTS idx_server_auto_start ON server (auto_start, environment);
