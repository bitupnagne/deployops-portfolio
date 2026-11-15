-- 기존 설치본에 display_order 컬럼을 추가한다.
--
-- 왜 필요한가: 이 컬럼은 V1__baseline.sql 안에 들어 있는데, 이미 설치된 DB 는
-- V1 이 "적용됨"으로 기록돼 있어 그 ALTER 가 영원히 실행되지 않는다.
-- 앱은 정상 기동하고 헬스체크도 통과하지만 서버 그룹 화면만 500 이 된다.
--
-- IF NOT EXISTS 라서 여러 번 돌려도 안전하다.
ALTER TABLE server_group
  ADD COLUMN IF NOT EXISTS display_order INT NOT NULL DEFAULT 0;

-- 기존 정렬(환경 → 이름)을 그대로 번호로 굳힌다.
-- 이렇게 해야 화면에 보이던 순서가 바뀌지 않는다.
UPDATE server_group sg
  JOIN (
    SELECT id, ROW_NUMBER() OVER (ORDER BY environment ASC, name ASC) - 1 AS rn
    FROM server_group
  ) t ON t.id = sg.id
SET sg.display_order = t.rn;

SELECT id, name, environment, display_order FROM server_group ORDER BY display_order;
