ALTER TABLE nginx_change_request
    ADD COLUMN IF NOT EXISTS policy_type VARCHAR(24) NOT NULL DEFAULT 'REVERSE_PROXY' AFTER policy_name,
    ADD COLUMN IF NOT EXISTS web_root VARCHAR(500) NULL AFTER upstream_port;

CREATE INDEX IF NOT EXISTS idx_nginx_change_generation
    ON nginx_change_request (server_id, policy_name, applied_at, id);
