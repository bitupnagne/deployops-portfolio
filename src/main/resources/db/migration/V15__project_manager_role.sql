-- Java/User 화면에서 지원하는 PROJECT_MANAGER 역할을 신규·기존 DB에서 저장할 수 있게 한다.
-- 기존 ENUM 순서를 유지하고 마지막에 추가하여 저장된 기존 역할의 인덱스를 바꾸지 않는다.
ALTER TABLE `users`
    MODIFY COLUMN `role` ENUM('APPROVER', 'DEVELOPER', 'OPERATOR', 'SUPER_ADMIN', 'PROJECT_MANAGER') NOT NULL;
