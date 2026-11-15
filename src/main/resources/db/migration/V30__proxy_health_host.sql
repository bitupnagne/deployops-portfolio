-- Optional virtual-host selection for WEB proxy HTTP checks; existing snapshots retain null.
ALTER TABLE deploy_component ADD COLUMN proxy_health_host VARCHAR(253) NULL;
