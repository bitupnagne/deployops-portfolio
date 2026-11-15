-- 기존 실행 이력은 소급하여 추적 완료로 간주하지 않는다.
ALTER TABLE deploy_request ADD COLUMN target_attempt_tracking_started_at DATETIME(6) NULL;
CREATE TABLE target_deployment_attempt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 deploy_request_id BIGINT NOT NULL,
 server_id BIGINT NOT NULL,
 plan_hash CHAR(64) NOT NULL,
 artifact_hash CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (deploy_request_id) REFERENCES deploy_request(id),
 FOREIGN KEY (server_id) REFERENCES server(id),
 INDEX ix_target_attempt_request_server(deploy_request_id,server_id,id),
 INDEX ix_target_attempt_server_time(server_id,created_at)
);
ALTER TABLE remote_command_log ADD COLUMN target_attempt_id BIGINT NULL;
ALTER TABLE remote_command_log ADD CONSTRAINT fk_remote_command_log_target_attempt
 FOREIGN KEY (target_attempt_id) REFERENCES target_deployment_attempt(id);
