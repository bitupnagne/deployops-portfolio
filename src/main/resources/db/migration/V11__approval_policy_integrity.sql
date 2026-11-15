-- 잘못된 과거 중복은 가장 먼저 처리된 한 건만 남긴다.
-- 승인/반려 서비스는 요청 행을 잠그지만 DB 제약도 함께 두어 우회 경로를 차단한다.
DELETE duplicate_record
FROM approval_record duplicate_record
JOIN approval_record first_record
  ON first_record.deploy_request_id = duplicate_record.deploy_request_id
 AND first_record.approver_id = duplicate_record.approver_id
 AND first_record.id < duplicate_record.id;

CREATE UNIQUE INDEX IF NOT EXISTS `uk_approval_record_request_approver`
  ON `approval_record` (`deploy_request_id`, `approver_id`);
