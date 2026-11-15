ALTER TABLE nginx_change_request
    ADD COLUMN requested_control_mode VARCHAR(16) NULL,
    ADD COLUMN requested_agent_binding_id VARCHAR(64) NULL;
