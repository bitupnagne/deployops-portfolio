-- ★ 이 컬럼들은 V1__baseline.sql 에도 들어 있다(스키마 정본은 V1 하나로 본다는 정책).
--   그래서 신규 설치에서는 V1 이 이미 만들어 둔 뒤 이 파일이 돈다 —
--   IF NOT EXISTS 가 없으면 ERROR 1060 (Duplicate column name) 으로 Flyway 가 멈추고
--   앱이 기동하지 못한다. 기존 DB 에서만 실제로 컬럼이 추가된다.
--   V1 에 미러링하는 ALTER 는 반드시 IF NOT EXISTS 를 붙인다(V2 와 같은 방식).
ALTER TABLE project
    ADD COLUMN IF NOT EXISTS build_config_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' AFTER build_tool,
    ADD COLUMN IF NOT EXISTS source_entry_path VARCHAR(500) NULL AFTER build_command,
    ADD COLUMN IF NOT EXISTS compiler_options VARCHAR(500) NULL AFTER source_entry_path,
    ADD COLUMN IF NOT EXISTS runtime_base_image VARCHAR(500) NULL AFTER compiler_options;

-- MODIFY 는 몇 번 돌려도 같은 결과라 그대로 둔다.
ALTER TABLE project
    MODIFY COLUMN build_tool ENUM('GRADLE','MAVEN','NONE','NPM','STATIC','C') DEFAULT NULL;

ALTER TABLE deploy_component
    MODIFY COLUMN build_tool ENUM('GRADLE','MAVEN','NONE','NPM','STATIC','C') DEFAULT NULL;
