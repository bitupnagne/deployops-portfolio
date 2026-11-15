ALTER TABLE project
    ADD COLUMN container_user VARCHAR(120) NULL,
    ADD COLUMN container_privileged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN container_cap_add VARCHAR(500) NULL,
    ADD COLUMN container_read_only_root_fs BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE deploy_component
    ADD COLUMN container_user VARCHAR(120) NULL,
    ADD COLUMN container_privileged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN container_cap_add VARCHAR(500) NULL,
    ADD COLUMN container_read_only_root_fs BOOLEAN NOT NULL DEFAULT FALSE;
