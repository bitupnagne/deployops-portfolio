-- 적용된 인증서의 신원과 만료를 남긴다. 이전에는 에이전트가 subject/issuer/enddate 를
-- 출력하는데도 앱이 BACKUP_PATH= 만 파싱하고 버려서, 시스템이 인증서 만료를 전혀
-- 추적하지 못했다(만료되면 그냥 장애로 알게 된다).
--
-- V9 와 마찬가지로 V1__baseline.sql 에 미러링하지 않는다. 신규 설치에서는 이 파일이
-- 그대로 컬럼을 만든다. 그래도 IF NOT EXISTS 를 붙여 둔다 — 나중에 누가 V1 에
-- 미러링하더라도 ERROR 1060 으로 신규 설치가 막히지 않게(MigrationLineageTest 참조).
ALTER TABLE nginx_change_request
    ADD COLUMN IF NOT EXISTS certificate_subject VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS certificate_issuer VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS certificate_sans VARCHAR(1000) NULL,
    ADD COLUMN IF NOT EXISTS certificate_not_after DATETIME(6) NULL,
    -- 이미 알린 임계값(30/14/7). 매일 도는 점검이 같은 인증서로 감사로그를 도배하지
    -- 않도록, 더 급한 단계로 넘어갈 때만 다시 남긴다.
    ADD COLUMN IF NOT EXISTS certificate_expiry_notified_days INT NULL;

CREATE INDEX IF NOT EXISTS idx_nginx_change_cert_expiry
    ON nginx_change_request (status, certificate_not_after);
