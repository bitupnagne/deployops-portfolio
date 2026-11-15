ALTER TABLE runtime_bindings ADD COLUMN stop_grace_seconds INT NOT NULL DEFAULT 30;
ALTER TABLE runtime_bindings ADD CONSTRAINT ck_runtime_stop_grace CHECK (stop_grace_seconds BETWEEN 1 AND 300);
