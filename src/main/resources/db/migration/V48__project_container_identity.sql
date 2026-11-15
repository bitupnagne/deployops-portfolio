-- Do not rename containers already deployed by older versions.
-- New projects receive deploy-project-{id}; legacy NULL keeps the previous name.
ALTER TABLE project ADD COLUMN container_name VARCHAR(255) NULL;
