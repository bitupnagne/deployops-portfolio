-- Existing groups retain their previous permissive availability behavior.
-- Operators can raise min_available after checking role counts and health probes.
ALTER TABLE server_group
    ADD COLUMN min_available INT NOT NULL DEFAULT 0,
    ADD COLUMN max_unavailable INT NOT NULL DEFAULT 1,
    ADD COLUMN observation_seconds INT NOT NULL DEFAULT 0,
    ADD COLUMN failure_policy VARCHAR(20) NOT NULL DEFAULT 'STOP';

ALTER TABLE deploy_request
    ADD COLUMN selected_server_group_ids TEXT NULL;
