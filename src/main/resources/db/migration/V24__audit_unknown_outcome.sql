-- 외부 시스템 timeout은 적용 실패를 증명하지 않으므로 결과 불명을 따로 저장한다.
-- 기존 ENUM 값 순서를 보존하여 과거 감사로그와 해시 체인을 바꾸지 않는다.
ALTER TABLE `audit_log`
    MODIFY COLUMN `result` ENUM('FAIL','SUCCESS','UNKNOWN') DEFAULT NULL;
