-- HTTP 포트가 없는 배치/PROC 컨테이너도 실행 상태를 운영 헬스 기준으로 사용할 수 있게 한다.
ALTER TABLE `project`
  MODIFY COLUMN `health_check_mode`
    enum('HTTP_DIRECT','NONE','SSH_LOCAL_CURL','CONTAINER_RUNNING') DEFAULT NULL;

ALTER TABLE `deploy_component`
  MODIFY COLUMN `health_check_mode`
    enum('HTTP_DIRECT','NONE','SSH_LOCAL_CURL','CONTAINER_RUNNING') DEFAULT NULL;
