ALTER TABLE project_user_permissions MODIFY COLUMN permission ENUM('PROJECT_VIEW','DEPLOY_REQUEST','MR_REVIEW','MR_APPROVE','MR_MERGE','TEST_APPROVE','PROD_APPROVE','TEST_EXECUTE','PROD_EXECUTE','ROLLBACK_EXECUTE','CI_VARIABLE_MANAGE','RUNTIME_CONFIG_MANAGE','RUNTIME_LOG_READ') NOT NULL;

CREATE TABLE runtime_env_revisions (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 environment VARCHAR(8) NOT NULL,
 component_id BIGINT NOT NULL DEFAULT 0,
 encrypted_payload LONGTEXT NOT NULL,
 encrypted_reason TEXT NOT NULL,
 record_fingerprint CHAR(64) NOT NULL,
 key_names TEXT NOT NULL,
 creator_id BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (project_id) REFERENCES project(id),
 INDEX ix_runtime_revision_scope(project_id,environment,component_id)
);
CREATE TABLE runtime_bindings (
 project_id BIGINT NOT NULL,
 environment VARCHAR(8) NOT NULL,
 component_id BIGINT NOT NULL DEFAULT 0,
 mode VARCHAR(24) NOT NULL,
 revision_id BIGINT NULL,
 persistent_logs BOOLEAN NOT NULL DEFAULT FALSE,
 tmp_mib INT NOT NULL DEFAULT 0,
 encrypted_reason TEXT NOT NULL,
 PRIMARY KEY(project_id,environment,component_id),
 FOREIGN KEY (project_id) REFERENCES project(id),
 FOREIGN KEY (revision_id) REFERENCES runtime_env_revisions(id)
);
