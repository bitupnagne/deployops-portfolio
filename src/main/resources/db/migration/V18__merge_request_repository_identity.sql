DROP INDEX IF EXISTS `uk_mr_evidence_head` ON `merge_request_evidence`;

CREATE UNIQUE INDEX IF NOT EXISTS `uk_mr_evidence_repo_head`
  ON `merge_request_evidence` (`project_id`, `gitlab_project_id`, `merge_request_iid`, `source_sha`);

CREATE TABLE IF NOT EXISTS `merge_request_origin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `gitlab_project_id` BIGINT NOT NULL,
  `merge_request_iid` BIGINT NOT NULL,
  `created_by_id` BIGINT NOT NULL,
  `gitlab_author_username` VARCHAR(100) DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mr_origin_repo` (`project_id`,`gitlab_project_id`,`merge_request_iid`),
  CONSTRAINT `fk_mr_origin_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_mr_origin_creator` FOREIGN KEY (`created_by_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
