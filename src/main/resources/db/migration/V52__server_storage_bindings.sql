CREATE TABLE server_storage_location (
    server_id BIGINT NOT NULL,
    storage_key VARCHAR(64) NOT NULL,
    kind VARCHAR(8) NOT NULL,
    root_path VARCHAR(512) NOT NULL,
    mount_target VARCHAR(512) NOT NULL,
    mount_source VARCHAR(512) NOT NULL,
    fs_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (server_id, storage_key),
    CONSTRAINT fk_storage_location_server FOREIGN KEY (server_id) REFERENCES server(id) ON DELETE CASCADE
);

CREATE TABLE project_storage_binding (
    project_id BIGINT NOT NULL,
    environment VARCHAR(8) NOT NULL,
    component_id BIGINT NOT NULL DEFAULT 0,
    storage_key VARCHAR(64) NOT NULL,
    relative_path VARCHAR(512) NOT NULL,
    container_path VARCHAR(512) NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (project_id, environment, component_id, container_path),
    CONSTRAINT fk_storage_binding_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
);
