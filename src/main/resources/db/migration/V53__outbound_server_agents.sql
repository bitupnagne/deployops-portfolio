ALTER TABLE server ADD COLUMN IF NOT EXISTS control_mode VARCHAR(16) NOT NULL DEFAULT 'SSH';
ALTER TABLE server ADD COLUMN IF NOT EXISTS agent_binding_id VARCHAR(64) NULL;

CREATE TABLE IF NOT EXISTS server_agent (
  server_id BIGINT NOT NULL PRIMARY KEY,
  generation BIGINT NOT NULL DEFAULT 1,
  state VARCHAR(24) NOT NULL,
  enrollment_hash CHAR(64) NULL,
  enrollment_expires_at DATETIME(6) NULL,
  credential_hash CHAR(64) NULL,
  machine_id VARCHAR(128) NULL,
  hostname VARCHAR(255) NULL,
  agent_version VARCHAR(64) NULL,
  role VARCHAR(8) NULL,
  last_seen_at DATETIME(6) NULL,
  readiness_json TEXT NULL,
  approved_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_agent_credential (credential_hash),
  UNIQUE KEY uk_agent_machine (machine_id),
  CONSTRAINT fk_server_agent_server FOREIGN KEY (server_id) REFERENCES server(id)
);

CREATE TABLE IF NOT EXISTS server_agent_task (
  id CHAR(36) NOT NULL PRIMARY KEY,
  server_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  state VARCHAR(24) NOT NULL,
  payload_encrypted LONGTEXT NOT NULL,
  is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
  timeout_sec INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  exit_code INT NULL,
  stdout_text MEDIUMTEXT NULL,
  stderr_text MEDIUMTEXT NULL,
  duration_ms BIGINT NULL,
  result_hash CHAR(64) NULL,
  artifact_path TEXT NULL,
  artifact_sha256 CHAR(64) NULL,
  artifact_size BIGINT NULL,
  KEY ix_agent_task_server (server_id, generation, state, created_at),
  CONSTRAINT fk_agent_task_server FOREIGN KEY (server_id) REFERENCES server(id)
);
