ALTER TABLE `deploy_request`
  ADD COLUMN IF NOT EXISTS `approval_decision_source`
    enum('MANUAL','AUTO_VERIFIED_PROMOTION','NOT_REQUIRED') DEFAULT NULL AFTER `plan_snapshot_version`,
  ADD COLUMN IF NOT EXISTS `execution_trigger`
    enum('MANUAL','AUTO') DEFAULT NULL AFTER `approval_decision_source`;

CREATE TABLE IF NOT EXISTS `deployment_execution_guard` (
  `id` tinyint(3) unsigned NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT current_timestamp(6) ON UPDATE current_timestamp(6),
  PRIMARY KEY (`id`),
  CONSTRAINT `chk_deployment_execution_guard_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO `deployment_execution_guard` (`id`) VALUES (1);
