ALTER TABLE server_group ADD COLUMN was_vip_enabled BOOLEAN NULL, ADD COLUMN was_vip_address VARCHAR(64) NULL;
ALTER TABLE project ADD COLUMN was_vip_port INT NULL;
