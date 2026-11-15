-- 명시적 다중 TCP 게시 포트. 기존 프로젝트는 NULL로 기존 health_check_port 매핑을 유지한다.
ALTER TABLE project ADD COLUMN container_ports VARCHAR(2048) NULL;
