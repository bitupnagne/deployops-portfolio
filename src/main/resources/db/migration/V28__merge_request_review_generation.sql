CREATE TABLE merge_request_review_generation (
  project_id BIGINT NOT NULL,
  gitlab_project_id BIGINT NOT NULL,
  merge_request_iid BIGINT NOT NULL,
  generation BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (project_id, gitlab_project_id, merge_request_iid),
  CONSTRAINT fk_mr_review_generation_project FOREIGN KEY (project_id) REFERENCES project(id)
) ENGINE=InnoDB;
