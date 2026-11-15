-- Preserve existing enum indexes; infrastructure agents may enroll dedicated Runner hosts.
ALTER TABLE `server`
  MODIFY COLUMN `server_type` ENUM('DEPLOY','GITLAB','NEXUS','BATCH','DB','WAS','WEB','RUNNER') DEFAULT NULL;
