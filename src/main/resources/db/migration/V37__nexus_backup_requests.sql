CREATE TABLE nexus_backup_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 requested_by_id BIGINT NOT NULL,
 approved_by_id BIGINT NULL,
 reason VARCHAR(1000) NOT NULL,
 rejection_reason VARCHAR(1000) NULL,
 status VARCHAR(32) NOT NULL,
 stage VARCHAR(32) NOT NULL DEFAULT 'PREFLIGHT',
 submitted BOOLEAN NOT NULL DEFAULT FALSE,
 active_check_id BIGINT NULL,
 error_code VARCHAR(64) NULL,
 artifact_id VARCHAR(100) NULL,
 manifest_sha256 VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL,
 approved_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL,
 CONSTRAINT fk_nexus_backup_requester FOREIGN KEY(requested_by_id) REFERENCES users(id),
 CONSTRAINT fk_nexus_backup_approver FOREIGN KEY(approved_by_id) REFERENCES users(id),
 INDEX ix_nexus_backup_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE deployment_execution_guard ADD COLUMN nexus_maintenance_request_id BIGINT NULL;
CREATE TABLE nexus_backup_recovery_check (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 requested_by_id BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 CONSTRAINT fk_nexus_check_request FOREIGN KEY(request_id) REFERENCES nexus_backup_request(id),
 CONSTRAINT fk_nexus_check_actor FOREIGN KEY(requested_by_id) REFERENCES users(id),
 INDEX ix_nexus_check_status(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
