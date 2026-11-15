ALTER TABLE office_notification_outbox
    ADD COLUMN event_key CHAR(64) NULL AFTER link_url,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER delivered_at,
    ADD COLUMN delivery_started_at DATETIME(6) NULL AFTER last_error,
    ADD COLUMN dead_lettered_at DATETIME(6) NULL AFTER delivery_started_at;

-- V4 rows were produced before logical event ids existed. Keep each legacy row
-- addressable while making new rows idempotent by their payload fingerprint.
UPDATE office_notification_outbox
SET event_key = SHA2(CONCAT('legacy:', id), 256)
WHERE event_key IS NULL;

ALTER TABLE office_notification_outbox
    MODIFY COLUMN event_key CHAR(64) NOT NULL,
    ADD UNIQUE KEY uq_office_notification_outbox_event_key (event_key),
    ADD KEY idx_office_notification_outbox_sending (status, delivery_started_at, id);
