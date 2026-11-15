-- Operational command output can exceed the original 500-character audit field.
-- Preserve the complete, already-secret-masked result so the audit chain records
-- enough context for diagnosis without truncation or dropping the record.
ALTER TABLE `audit_log`
    MODIFY COLUMN `target_desc` TEXT DEFAULT NULL;
