CREATE TABLE IF NOT EXISTS `merge_request_evidence` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `gitlab_project_id` BIGINT NOT NULL,
  `merge_request_iid` BIGINT NOT NULL,
  `source_sha` VARCHAR(64) NOT NULL,
  `target_branch` VARCHAR(200) NOT NULL,
  `diff_base_sha` VARCHAR(64) DEFAULT NULL,
  `diff_start_sha` VARCHAR(64) DEFAULT NULL,
  `reviewer_id` BIGINT NOT NULL,
  `reviewer_gitlab_username` VARCHAR(100) NOT NULL,
  `token_source` VARCHAR(32) NOT NULL,
  `status` ENUM('APPROVED','MERGED') NOT NULL,
  `merged_result_sha` VARCHAR(64) DEFAULT NULL,
  `merger_id` BIGINT DEFAULT NULL,
  `approved_at` DATETIME(6) NOT NULL,
  `merged_at` DATETIME(6) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mr_evidence_head` (`project_id`,`merge_request_iid`,`source_sha`),
  KEY `idx_mr_evidence_merged` (`project_id`,`merge_request_iid`,`status`),
  CONSTRAINT `fk_mr_evidence_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_mr_evidence_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_mr_evidence_merger` FOREIGN KEY (`merger_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE `deploy_request`
  ADD COLUMN IF NOT EXISTS `expected_commit_sha` VARCHAR(64) DEFAULT NULL AFTER `commit_sha`,
  ADD COLUMN IF NOT EXISTS `source_mr_iid` BIGINT DEFAULT NULL AFTER `expected_commit_sha`,
  ADD COLUMN IF NOT EXISTS `mr_evidence_id` BIGINT DEFAULT NULL AFTER `source_mr_iid`,
  ADD COLUMN IF NOT EXISTS `immutable_build_ref` VARCHAR(255) DEFAULT NULL AFTER `mr_evidence_id`;

CREATE INDEX IF NOT EXISTS `idx_deploy_request_mr_evidence` ON `deploy_request` (`mr_evidence_id`);
