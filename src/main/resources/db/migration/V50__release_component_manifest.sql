ALTER TABLE deploy_request
    ADD COLUMN release_manifest_sha256 VARCHAR(64) NULL;
