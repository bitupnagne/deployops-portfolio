ALTER TABLE audit_log
  ADD COLUMN IF NOT EXISTS previous_hash CHAR(64) NULL AFTER ip_address,
  ADD COLUMN IF NOT EXISTS entry_hash CHAR(64) NULL AFTER previous_hash;

CREATE INDEX IF NOT EXISTS idx_audit_log_entry_hash ON audit_log (entry_hash);
