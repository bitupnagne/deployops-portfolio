CREATE TABLE nexus_dependency_upload (
  id VARCHAR(36) PRIMARY KEY,
  project_id BIGINT NOT NULL,
  owner_id BIGINT NOT NULL,
  repository_name VARCHAR(60) NOT NULL,
  repository_format VARCHAR(16) NOT NULL,
  nexus_base_url VARCHAR(1024) NOT NULL,
  target_path VARCHAR(1024) NOT NULL,
  total_bytes BIGINT NOT NULL,
  received_bytes BIGINT NOT NULL DEFAULT 0,
  published_bytes BIGINT NOT NULL DEFAULT 0,
  content_sha256 VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  message VARCHAR(500),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_nexus_upload_project (project_id, owner_id, created_at),
  INDEX idx_nexus_upload_status (status, updated_at)
);
