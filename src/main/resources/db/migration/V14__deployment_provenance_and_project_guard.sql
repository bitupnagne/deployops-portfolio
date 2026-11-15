-- 자동 승인은 API 입력값이 아니라 GitLab job artifact와 Nexus manifest를
-- 서버가 교차 검증한 provenance가 영속화된 요청에만 허용한다.
ALTER TABLE `deploy_request`
    ADD COLUMN IF NOT EXISTS `release_verification_status`
        ENUM('UNVERIFIED', 'VERIFIED_CI_ARTIFACT') NOT NULL DEFAULT 'UNVERIFIED' AFTER `execution_trigger`,
    ADD COLUMN IF NOT EXISTS `release_verification_job_id`
        BIGINT DEFAULT NULL AFTER `release_verification_status`,
    ADD COLUMN IF NOT EXISTS `release_verification_repository`
        VARCHAR(500) DEFAULT NULL AFTER `release_verification_job_id`,
    ADD COLUMN IF NOT EXISTS `release_verified_at`
        DATETIME(6) DEFAULT NULL AFTER `release_verification_repository`,
    ADD COLUMN IF NOT EXISTS `release_verification_evidence_hash`
        CHAR(64) DEFAULT NULL AFTER `release_verified_at`,
    ADD COLUMN IF NOT EXISTS `auto_dispatch_blocked_at`
        DATETIME(6) DEFAULT NULL AFTER `release_verification_evidence_hash`,
    ADD COLUMN IF NOT EXISTS `auto_dispatch_block_reason`
        VARCHAR(500) DEFAULT NULL AFTER `auto_dispatch_blocked_at`;

CREATE INDEX IF NOT EXISTS `idx_deploy_request_auto_dispatch`
    ON `deploy_request` (`status`, `auto_dispatch_blocked_at`, `id`);
