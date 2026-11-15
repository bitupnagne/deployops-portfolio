ALTER TABLE server_group
    ADD COLUMN approval_policy_id BIGINT NULL;
ALTER TABLE server_group
    ADD CONSTRAINT fk_server_group_approval_policy
        FOREIGN KEY (approval_policy_id) REFERENCES approval_policy(id);

-- One explicit decision per effective step. Existing history remains unchanged.
-- Legacy noncomposed policies retain their request-wide duplicate guard in the service.
CREATE UNIQUE INDEX uk_approval_record_request_step_approver
    ON approval_record (deploy_request_id, step_order, approver_id);
DROP INDEX uk_approval_record_request_approver ON approval_record;
