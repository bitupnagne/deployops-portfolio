-- Legacy rows remain unverified; no fabricated restoration metadata.
ALTER TABLE nginx_change_request
    ADD COLUMN backup_snapshot_hash VARCHAR(64) NULL,
    ADD COLUMN applied_snapshot_hash VARCHAR(64) NULL,
    ADD COLUMN applied_config_hash VARCHAR(64) NULL,
    ADD COLUMN native_request_id BIGINT NULL,
    ADD COLUMN rollback_snapshot_hash VARCHAR(64) NULL,
    ADD COLUMN expected_native_request_id BIGINT NULL,
    ADD COLUMN expected_current_snapshot_hash VARCHAR(64) NULL,
    ADD COLUMN restore_target_id BIGINT NULL,
    ADD COLUMN restore_absent BIT NOT NULL DEFAULT 0;
