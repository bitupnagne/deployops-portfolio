ALTER TABLE `merge_request_evidence`
  ADD COLUMN IF NOT EXISTS `reviewer_gitlab_user_id` BIGINT DEFAULT NULL AFTER `reviewer_id`;
