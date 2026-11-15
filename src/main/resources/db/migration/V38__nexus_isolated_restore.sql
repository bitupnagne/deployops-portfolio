CREATE TABLE nexus_restore_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 backup_request_id BIGINT NOT NULL, artifact_id VARCHAR(100) NOT NULL, manifest_sha256 CHAR(64) NOT NULL,
 requested_by_id BIGINT NOT NULL, approved_by_id BIGINT NULL, reason VARCHAR(1000) NOT NULL,
 rejection_reason VARCHAR(1000) NULL, status VARCHAR(32) NOT NULL, stage VARCHAR(32) NOT NULL DEFAULT 'PREFLIGHT',
 submitted BOOLEAN NOT NULL DEFAULT FALSE, error_code VARCHAR(64) NULL,
 clone_container_id CHAR(64) NULL, clone_image_id VARCHAR(71) NULL, clone_name VARCHAR(100) NULL,
 clone_volume VARCHAR(100) NULL, clone_access LONGTEXT NULL,
    clone_network VARCHAR(100) NULL, expected_artifacts LONGTEXT NULL,
 original_projection_preserved BOOLEAN NOT NULL DEFAULT FALSE, mutation_uncertain BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME(6) NOT NULL, approved_at DATETIME(6) NULL, completed_at DATETIME(6) NULL,
 confirmed_by_id BIGINT NULL, confirmed_at DATETIME(6) NULL, operator_checklist LONGTEXT NULL,
 artifact_confirmations LONGTEXT NULL,
 FOREIGN KEY (backup_request_id) REFERENCES nexus_backup_request(id),
 FOREIGN KEY (requested_by_id) REFERENCES users(id), FOREIGN KEY (approved_by_id) REFERENCES users(id),
 FOREIGN KEY (confirmed_by_id) REFERENCES users(id), INDEX idx_restore_status (status,id)
);
ALTER TABLE deployment_execution_guard ADD COLUMN nexus_restore_request_id BIGINT NULL,
 ADD CONSTRAINT fk_guard_nexus_restore FOREIGN KEY (nexus_restore_request_id) REFERENCES nexus_restore_request(id);
