-- Optional environment overrides; legacy common Host and approved snapshot bytes remain unchanged.
ALTER TABLE deploy_component
    ADD COLUMN proxy_health_host_test VARCHAR(253) NULL,
    ADD COLUMN proxy_health_host_prod VARCHAR(253) NULL;
