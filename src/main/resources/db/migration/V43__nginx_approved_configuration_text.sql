ALTER TABLE nginx_change_request
    ADD COLUMN raw_config MEDIUMTEXT NULL,
    ADD COLUMN raw_config_sha256 CHAR(64) NULL;
