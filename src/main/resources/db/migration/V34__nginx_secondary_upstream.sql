-- Optional typed second backend; historical single-backend rows remain unchanged.
ALTER TABLE nginx_change_request
    ADD COLUMN secondary_upstream_host VARCHAR(255) NULL,
    ADD COLUMN secondary_upstream_port INT NULL,
    ADD COLUMN upstream_max_fails INT NOT NULL DEFAULT 1,
    ADD COLUMN upstream_fail_timeout_sec INT NOT NULL DEFAULT 10,
    ADD COLUMN proxy_connect_timeout_sec INT NOT NULL DEFAULT 3;
