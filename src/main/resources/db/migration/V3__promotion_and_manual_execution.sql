ALTER TABLE deploy_request
  ADD COLUMN promoted_from_request_id BIGINT NULL AFTER executed_by_id,
  ADD KEY idx_deploy_request_promoted_from (promoted_from_request_id),
  ADD CONSTRAINT fk_deploy_request_promoted_from
    FOREIGN KEY (promoted_from_request_id) REFERENCES deploy_request(id);

CREATE TABLE manual_server_execution (
  deploy_request_id BIGINT NOT NULL,
  server_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  executed_by_id BIGINT NOT NULL,
  error_message VARCHAR(1000) NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (deploy_request_id, server_id),
  KEY idx_manual_server_execution_user (executed_by_id),
  CONSTRAINT fk_manual_execution_request FOREIGN KEY (deploy_request_id) REFERENCES deploy_request(id),
  CONSTRAINT fk_manual_execution_server FOREIGN KEY (server_id) REFERENCES server(id),
  CONSTRAINT fk_manual_execution_user FOREIGN KEY (executed_by_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
