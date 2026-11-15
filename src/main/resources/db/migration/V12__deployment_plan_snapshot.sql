ALTER TABLE deploy_request
  ADD COLUMN IF NOT EXISTS plan_snapshot_json LONGTEXT NULL AFTER reason,
  ADD COLUMN IF NOT EXISTS plan_snapshot_hash CHAR(64) NULL AFTER plan_snapshot_json,
  ADD COLUMN IF NOT EXISTS plan_snapshot_version INT NULL AFTER plan_snapshot_hash;

-- 이 마이그레이션 이전 요청에는 당시 실행 계획을 복원할 근거가 없다.
-- 임의로 현재 프로젝트 설정을 과거 snapshot처럼 채우지 않고, 서비스 계층에서 새 요청 생성을 요구한다.
