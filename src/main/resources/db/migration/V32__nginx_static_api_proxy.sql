ALTER TABLE nginx_change_request
    ADD COLUMN api_proxy_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN api_prefix VARCHAR(80) NULL,
    ADD COLUMN api_upstream_host VARCHAR(255) NULL,
    ADD COLUMN api_upstream_port INT NULL;
