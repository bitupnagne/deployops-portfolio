-- 배포시스템 스키마 — 단일 베이스라인.
--
-- 증분 이력(구 V2~V9)을 하나로 합쳤다. 스키마 변경은 V2 를 만들지 말고 이 파일을 직접 고친다.
--
-- ★ 단, 이미 설치된 DB 는 V1 이 "적용됨"으로 기록돼 있어 이 파일을 고쳐도 반영되지 않는다.
--   그런 곳에는 같은 변경을 installer/db/patches/ 의 SQL 로 한 번 더 적용해야 한다.
--   (앱은 정상 기동하고 헬스체크도 통과하므로, 빠뜨리면 해당 화면에서만 500 이 뜬다)
-- (구 V5 는 기존 project 행 백필이라 빈 DB 에서는 no-op — 합치면서 제외했다)
--
-- ★ collation 은 utf8mb4_unicode_ci 로 고정한다.
--   utf8mb4_uca1400_ai_ci 는 MariaDB 11.4+ 에만 있는데, 설치 대상인 Rocky 8 의 기본
--   MariaDB 는 10.3 이라 "Unknown collation" 으로 기동이 실패한다. 개발 PC 의 MariaDB 가
--   더 최신이면 덤프에 그 collation 이 딸려 나오므로, 스키마를 다시 뽑을 때마다 확인할 것.
--   설치기가 CREATE DATABASE 에 쓰는 값(common.sh)과도 이 값이 일치해야 한다.

-- 테이블을 알파벳 순으로 만들다 보니 참조 대상이 아직 없을 수 있다.
-- 세션 한정으로 FK 검사를 끄고 만든 뒤 되돌린다(베이스라인 관례).
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE IF NOT EXISTS `approval_policy` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `allow_self_approval` bit(1) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `policy_type` enum('ALL','ANY_ONE','STEP') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `approval_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `action` enum('APPROVED','REJECTED') DEFAULT NULL,
  `comment` varchar(1000) DEFAULT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  `step_order` int(11) NOT NULL,
  `approver_id` bigint(20) DEFAULT NULL,
  `deploy_request_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_approval_record_approver_id` (`approver_id`),
  KEY `idx_approval_record_deploy_request_id` (`deploy_request_id`),
  CONSTRAINT `fk_approval_record_approver` FOREIGN KEY (`approver_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_approval_record_deploy_request` FOREIGN KEY (`deploy_request_id`) REFERENCES `deploy_request` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `approval_step` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `required_count` int(11) NOT NULL,
  `step_order` int(11) NOT NULL,
  `policy_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_approval_step_policy_id` (`policy_id`),
  CONSTRAINT `fk_approval_step_policy` FOREIGN KEY (`policy_id`) REFERENCES `approval_policy` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `approval_step_approvers` (
  `step_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  KEY `idx_approval_step_approvers_step_id` (`step_id`),
  KEY `idx_approval_step_approvers_user_id` (`user_id`),
  CONSTRAINT `fk_approval_step_approvers_step` FOREIGN KEY (`step_id`) REFERENCES `approval_step` (`id`),
  CONSTRAINT `fk_approval_step_approvers_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `action` varchar(200) NOT NULL,
  `actor` varchar(100) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `ip_address` varchar(50) DEFAULT NULL,
  `result` enum('FAIL','SUCCESS') DEFAULT NULL,
  `target_desc` varchar(500) DEFAULT NULL,
  `target_id` varchar(100) DEFAULT NULL,
  `target_type` varchar(100) DEFAULT NULL,
  `previous_hash` char(64) DEFAULT NULL,
  `entry_hash` char(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_log_entry_hash` (`entry_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `assistant_chat` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `login_id` varchar(100) NOT NULL,
  `role` varchar(10) NOT NULL,
  `content` mediumtext NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_assistant_chat_login` (`login_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `deploy_component` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `project_id` bigint(20) NOT NULL,
  `code` varchar(50) NOT NULL,
  `display_name` varchar(100) DEFAULT NULL,
  `role` enum('WAS','WEB') NOT NULL,
  `artifact_type` enum('CONTAINER','STATIC_BUNDLE','PROXY_RELOAD') NOT NULL,
  `image_repository` varchar(255) DEFAULT NULL,
  `web_root` varchar(500) DEFAULT NULL,
  `build_tool` enum('GRADLE','MAVEN','NONE','NPM','STATIC','C') DEFAULT NULL,
  `dockerfile_path` varchar(500) DEFAULT NULL,
  `docker_context_path` varchar(500) DEFAULT NULL,
  `build_command` varchar(500) DEFAULT NULL,
  `artifact_path` varchar(500) DEFAULT NULL,
  `health_check_mode` enum('HTTP_DIRECT','NONE','SSH_LOCAL_CURL') DEFAULT NULL,
  `health_check_path` varchar(255) DEFAULT NULL,
  `health_check_port` int(11) DEFAULT NULL,
  `deploy_order` int(11) NOT NULL DEFAULT 0,
  `active` bit(1) NOT NULL DEFAULT b'1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_deploy_component_project_code` (`project_id`,`code`),
  KEY `idx_deploy_component_project` (`project_id`),
  CONSTRAINT `fk_deploy_component_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `deploy_request` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `branch` varchar(200) NOT NULL,
  `commit_sha` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `environment` enum('PROD','TEST') NOT NULL,
  `deploy_scope` enum('ALL','WAS_ONLY','WEB_ONLY') NOT NULL DEFAULT 'ALL',
  `deploy_strategy` enum('SAFE','WAVE','MANUAL') NOT NULL DEFAULT 'SAFE',
  `gitlab_pipeline_id` bigint(20) DEFAULT NULL,
  `image_digest` varchar(500) DEFAULT NULL,
  `image_tag` varchar(200) DEFAULT NULL,
  `reason` varchar(1000) DEFAULT NULL,
  `req_no` varchar(30) NOT NULL,
  `status` enum('APPROVAL_COMPLETED','APPROVAL_PENDING','COMPLETED','PROD_DEPLOYING','PROD_FAILED','REJECTED','ROLLED_BACK','TEST_BUILDING','TEST_CONFIRMED','TEST_DEPLOYED','TEST_DEPLOYING','TEST_FAILED','TEST_REQUESTED') NOT NULL,
  `test_confirmed_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `project_id` bigint(20) NOT NULL,
  `requested_by_id` bigint(20) DEFAULT NULL,
  `test_confirmed_by_id` bigint(20) DEFAULT NULL,
  `executed_by_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_deploy_request_req_no` (`req_no`),
  KEY `idx_deploy_request_project_id` (`project_id`),
  KEY `idx_deploy_request_requested_by_id` (`requested_by_id`),
  KEY `idx_deploy_request_test_confirmed_by_id` (`test_confirmed_by_id`),
  CONSTRAINT `fk_deploy_request_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_deploy_request_requested_by` FOREIGN KEY (`requested_by_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_deploy_request_test_confirmed_by` FOREIGN KEY (`test_confirmed_by_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `deploy_request_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `deploy_request_id` bigint(20) NOT NULL,
  `component_id` bigint(20) DEFAULT NULL,
  `component_code` varchar(50) DEFAULT NULL,
  `image_tag` varchar(200) DEFAULT NULL,
  `image_digest` varchar(500) DEFAULT NULL,
  `bundle_ref` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_deploy_request_item_request` (`deploy_request_id`),
  CONSTRAINT `fk_deploy_request_item_request` FOREIGN KEY (`deploy_request_id`) REFERENCES `deploy_request` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `office_sso_link` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `office_user_id` bigint(20) NOT NULL,
  `office_username` varchar(100) NOT NULL,
  `deploy_user_id` bigint(20) NOT NULL,
  `active` bit(1) NOT NULL DEFAULT b'1',
  `linked_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_office_sso_link_office_user` (`office_user_id`),
  UNIQUE KEY `uk_office_sso_link_deploy_user` (`deploy_user_id`),
  KEY `idx_office_sso_link_username` (`office_username`),
  CONSTRAINT `fk_office_sso_link_deploy_user` FOREIGN KEY (`deploy_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `project` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `default_branch` varchar(200) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `gitlab_project_id` bigint(20) DEFAULT NULL,
  `gitlab_repo_url` varchar(500) DEFAULT NULL,
  `mr_target_branch` varchar(200) DEFAULT NULL,
  `mr_assignee_gitlab_username` varchar(100) DEFAULT NULL,
  `mr_reviewer_gitlab_usernames` varchar(500) DEFAULT NULL,
  `auto_merge_on_approval` bit(1) NOT NULL DEFAULT b'0',
  `delete_source_branch_on_merge` bit(1) NOT NULL DEFAULT b'1',
  `name` varchar(100) NOT NULL,
  `pipeline_type` enum('DOCKER','JAR','STATIC') DEFAULT NULL,
  `rendering_type` enum('SSR','CSR','API_ONLY','STATIC_ONLY','HYBRID') NOT NULL DEFAULT 'SSR',
  `approval_policy_id` bigint(20) DEFAULT NULL,
  `artifact_path` varchar(500) DEFAULT NULL,
  `build_command` varchar(500) DEFAULT NULL,
  `build_file_path` varchar(300) DEFAULT NULL,
  `build_tool` enum('GRADLE','MAVEN','NONE','NPM','STATIC','C') DEFAULT NULL,
  `build_config_mode` varchar(16) NOT NULL DEFAULT 'AUTO',
  `source_entry_path` varchar(500) DEFAULT NULL,
  `compiler_options` varchar(500) DEFAULT NULL,
  `runtime_base_image` varchar(500) DEFAULT NULL,
  `docker_context_path` varchar(500) DEFAULT NULL,
  `dockerfile_path` varchar(500) DEFAULT NULL,
  `health_check_mode` enum('HTTP_DIRECT','NONE','SSH_LOCAL_CURL') DEFAULT NULL,
  `health_check_path` varchar(255) DEFAULT NULL,
  `health_check_port` int(11) DEFAULT NULL,
  `image_repository` varchar(255) DEFAULT NULL,
  `image_tag_pattern` varchar(255) DEFAULT NULL,
  `nexus_docker_repository` varchar(120) DEFAULT NULL,
  `nexus_maven_release_repository` varchar(120) DEFAULT NULL,
  `nexus_maven_snapshot_repository` varchar(120) DEFAULT NULL,
  `remote_deploy_script_path` varchar(500) DEFAULT NULL,
  `docker_network` varchar(120) DEFAULT NULL,
  `env_file_path` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_name` (`name`),
  KEY `idx_project_approval_policy_id` (`approval_policy_id`),
  CONSTRAINT `fk_project_approval_policy` FOREIGN KEY (`approval_policy_id`) REFERENCES `approval_policy` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `project_image_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `branch` varchar(200) DEFAULT NULL,
  `commit_sha` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `environment` enum('PROD','TEST') DEFAULT NULL,
  `gitlab_pipeline_id` bigint(20) DEFAULT NULL,
  `history_status` enum('BUILD_COMPLETED','PROD_DEPLOYED','ROLLED_BACK','TEST_DEPLOYED') NOT NULL,
  `image_digest` varchar(500) DEFAULT NULL,
  `image_repository` varchar(255) DEFAULT NULL,
  `image_tag` varchar(200) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `deploy_request_id` bigint(20) DEFAULT NULL,
  `project_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_image_history_request_status` (`deploy_request_id`,`history_status`),
  KEY `idx_project_image_history_project_created_at` (`project_id`,`created_at`),
  KEY `idx_project_image_history_deploy_request_id` (`deploy_request_id`),
  CONSTRAINT `fk_project_image_history_deploy_request` FOREIGN KEY (`deploy_request_id`) REFERENCES `deploy_request` (`id`),
  CONSTRAINT `fk_project_image_history_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `project_server_groups` (
  `project_id` bigint(20) NOT NULL,
  `server_group_id` bigint(20) NOT NULL,
  KEY `idx_project_server_groups_project_id` (`project_id`),
  KEY `idx_project_server_groups_server_group_id` (`server_group_id`),
  CONSTRAINT `fk_project_server_groups_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_project_server_groups_server_group` FOREIGN KEY (`server_group_id`) REFERENCES `server_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `project_user_permissions` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `project_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `permission` enum('PROJECT_VIEW','DEPLOY_REQUEST','MR_REVIEW','MR_APPROVE','MR_MERGE','TEST_APPROVE','PROD_APPROVE','TEST_EXECUTE','PROD_EXECUTE','ROLLBACK_EXECUTE') NOT NULL,
  `environment` enum('ALL','TEST','PROD') NOT NULL DEFAULT 'ALL',
  `active` bit(1) NOT NULL DEFAULT b'1',
  `created_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_user_permission` (`project_id`,`user_id`,`permission`,`environment`),
  KEY `idx_project_user_permissions_project_id` (`project_id`),
  KEY `idx_project_user_permissions_user_id` (`user_id`),
  KEY `idx_project_user_permissions_lookup` (`user_id`,`project_id`,`permission`,`environment`,`active`),
  CONSTRAINT `fk_project_user_permissions_project` FOREIGN KEY (`project_id`) REFERENCES `project` (`id`),
  CONSTRAINT `fk_project_user_permissions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `remote_command_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `deploy_request_id` bigint(20) DEFAULT NULL,
  `server_id` bigint(20) DEFAULT NULL,
  `server_name` varchar(100) DEFAULT NULL,
  `command_display` text DEFAULT NULL,
  `stdout_text` mediumtext DEFAULT NULL,
  `stderr_text` mediumtext DEFAULT NULL,
  `exit_code` int(11) DEFAULT NULL,
  `success` bit(1) NOT NULL DEFAULT b'0',
  `started_at` datetime(6) DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `duration_ms` bigint(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_remote_command_log_request` (`deploy_request_id`),
  KEY `idx_remote_command_log_server` (`server_id`),
  KEY `idx_remote_command_log_created_at` (`created_at`),
  CONSTRAINT `fk_remote_command_log_request` FOREIGN KEY (`deploy_request_id`) REFERENCES `deploy_request` (`id`),
  CONSTRAINT `fk_remote_command_log_server` FOREIGN KEY (`server_id`) REFERENCES `server` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `server` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `auth_type` enum('PASSWORD','SSH_KEY') DEFAULT NULL,
  `deploy_account` varchar(50) DEFAULT NULL,
  `deploy_path` varchar(500) DEFAULT NULL,
  `environment` enum('DEV','PROD','TEST') DEFAULT NULL,
  `ip_address` varchar(50) NOT NULL,
  `last_checked_at` datetime(6) DEFAULT NULL,
  `memo` varchar(500) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `order_index` int(11) NOT NULL,
  `server_type` enum('DEPLOY','GITLAB','NEXUS','BATCH','DB','WAS','WEB') DEFAULT NULL,
  `ssh_port` int(11) NOT NULL,
  `status` enum('ERROR','NORMAL','UNKNOWN') DEFAULT NULL,
  -- 등록일. DB 기본값을 주지 않고 앱이 INSERT 때 채운다.
  --   DEFAULT CURRENT_TIMESTAMP 로 두면 기존 설치본에 컬럼을 추가할 때
  --   이미 있던 행이 전부 '마이그레이션 시각' 으로 채워져 틀린 날짜를 보여준다.
  --   그런 행은 NULL 로 남겨 화면이 '등록일 미상' 이라고 말하게 한다.
  `created_at` datetime(6) DEFAULT NULL,
  `auto_start` boolean NOT NULL DEFAULT FALSE,
  `startup_service` varchar(160) DEFAULT NULL,
  `server_group_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_server_name` (`name`),
  KEY `idx_server_group_id` (`server_group_id`),
  CONSTRAINT `fk_server_group` FOREIGN KEY (`server_group_id`) REFERENCES `server_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `server_group` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `description` varchar(500) DEFAULT NULL,
  `environment` enum('DEV','PROD','TEST') DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `rolling_interval_sec` int(11) NOT NULL,
  -- 화면에 뿌리는 순서. 운영자가 직접 바꾼다(배포 순서는 서버의 order_index 가 따로 정한다).
  `display_order` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `sso_link_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `code` varchar(32) NOT NULL,
  `deploy_user_id` bigint(20) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sso_link_code_code` (`code`),
  KEY `idx_sso_link_code_user` (`deploy_user_id`),
  CONSTRAINT `fk_sso_link_code_user` FOREIGN KEY (`deploy_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `sso_login_ticket` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `ticket` varchar(64) NOT NULL,
  `office_user_id` bigint(20) NOT NULL,
  `deploy_user_id` bigint(20) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT current_timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sso_login_ticket_ticket` (`ticket`),
  KEY `idx_sso_login_ticket_user` (`deploy_user_id`),
  CONSTRAINT `fk_sso_login_ticket_user` FOREIGN KEY (`deploy_user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `status_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `status` enum('APPROVAL_COMPLETED','APPROVAL_PENDING','COMPLETED','PROD_DEPLOYING','PROD_FAILED','REJECTED','ROLLED_BACK','TEST_BUILDING','TEST_CONFIRMED','TEST_DEPLOYED','TEST_DEPLOYING','TEST_FAILED','TEST_REQUESTED') DEFAULT NULL,
  `deploy_request_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_status_history_deploy_request_id` (`deploy_request_id`),
  CONSTRAINT `fk_status_history_deploy_request` FOREIGN KEY (`deploy_request_id`) REFERENCES `deploy_request` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `system_setting` (
  `setting_key` varchar(200) NOT NULL,
  `category` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `setting_value` text DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `teams` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `leader_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teams_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `department` varchar(100) DEFAULT NULL,
  `force_password_change` bit(1) NOT NULL,
  `gitlab_token` text DEFAULT NULL,
  `gitlab_username` varchar(100) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `login_id` varchar(50) NOT NULL,
  `name` varchar(50) NOT NULL,
  `password` varchar(255) NOT NULL,
  `password_changed_at` datetime(6) DEFAULT NULL,
  `role` enum('APPROVER','DEVELOPER','OPERATOR','SUPER_ADMIN') NOT NULL,
  `team_id` bigint(20) DEFAULT NULL,
  `otp_secret` varchar(64) DEFAULT NULL,
  `otp_enabled` tinyint(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_login_id` (`login_id`),
  KEY `idx_users_team_id` (`team_id`),
  CONSTRAINT `fk_users_team` FOREIGN KEY (`team_id`) REFERENCES `teams` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초기 조직 시드. 멱등이라 재실행해도 중복되지 않는다.
-- ★ FROM DUAL 이 필요하다 — MariaDB 10.3(설치 대상)은 FROM 절 없는 "SELECT 값 WHERE 조건"을
--   문법 오류로 거절한다. 최신 버전에서는 통과해서 개발 PC 에서는 드러나지 않는다.
INSERT INTO teams (name, description, leader_id, active, created_at)
SELECT '플랫폼개발팀', '오피스시스템과 배포시스템 개발 및 운영 조직', NULL, b'1', NOW(6)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM teams WHERE name = '플랫폼개발팀');

INSERT INTO teams (name, description, leader_id, active, created_at)
SELECT '서비스운영팀', '예제 서비스 운영 및 배포 승인 조직', NULL, b'1', NOW(6)
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM teams WHERE name = '서비스운영팀');

SET FOREIGN_KEY_CHECKS = 1;
