CREATE TABLE project_rollout_route (
    project_id BIGINT NOT NULL,
    environment VARCHAR(16) NOT NULL,
    web_server_id BIGINT NOT NULL,
    policy_name VARCHAR(80) NOT NULL,
    PRIMARY KEY (project_id, environment, web_server_id, policy_name),
    CONSTRAINT uq_rollout_route_owner UNIQUE (environment, web_server_id, policy_name),
    CONSTRAINT fk_rollout_route_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_rollout_route_server FOREIGN KEY (web_server_id) REFERENCES server(id)
);
