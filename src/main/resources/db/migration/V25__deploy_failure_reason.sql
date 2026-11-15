ALTER TABLE `deploy_request`
    ADD COLUMN `failure_reason` varchar(1800) DEFAULT NULL AFTER `reason`;
