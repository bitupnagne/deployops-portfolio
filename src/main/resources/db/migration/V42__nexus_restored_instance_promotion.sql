CREATE TABLE nexus_promotion_request (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 restore_request_id BIGINT NOT NULL,
 requested_by_id BIGINT NOT NULL,
 approved_by_id BIGINT NULL,
 reason VARCHAR(1000) NOT NULL,
 rejection_reason VARCHAR(1000) NULL,
 status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
 submitted BOOLEAN NOT NULL DEFAULT FALSE,
 canonical_container_id VARCHAR(64) NULL,
 rollback_container_id VARCHAR(64) NULL,
 error_code VARCHAR(80) NULL,
 created_at DATETIME(6) NOT NULL,
 approved_at DATETIME(6) NULL,
 completed_at DATETIME(6) NULL,
 INDEX ix_nexus_promotion_restore (restore_request_id),
 CONSTRAINT fk_promotion_restore FOREIGN KEY (restore_request_id) REFERENCES nexus_restore_request(id),
 CONSTRAINT fk_promotion_requester FOREIGN KEY (requested_by_id) REFERENCES users(id),
 CONSTRAINT fk_promotion_approver FOREIGN KEY (approved_by_id) REFERENCES users(id)
);
ALTER TABLE deployment_execution_guard ADD COLUMN nexus_promotion_request_id BIGINT NULL,
 ADD CONSTRAINT fk_guard_nexus_promotion FOREIGN KEY (nexus_promotion_request_id) REFERENCES nexus_promotion_request(id);
