ALTER TABLE nexus_restore_request ADD COLUMN active_check_id BIGINT NULL;
CREATE TABLE nexus_restore_recovery_check (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id BIGINT NOT NULL,
 requested_by_id BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL,
 reason_code VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL,
 completed_at DATETIME(6) NULL,
 CONSTRAINT fk_restore_check_request FOREIGN KEY(request_id) REFERENCES nexus_restore_request(id),
 CONSTRAINT fk_restore_check_actor FOREIGN KEY(requested_by_id) REFERENCES users(id),
 INDEX ix_restore_check_status(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
