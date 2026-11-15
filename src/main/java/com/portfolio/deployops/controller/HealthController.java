package com.portfolio.deployops.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 헬스체크 — L4·감시 스크립트·설치 검증이 쓴다.
 *
 * <p>지금까지는 {@code curl /login} 이 200 인지로 살아 있는지를 판단했는데, 로그인 화면은 DB 를
 * 타지 않아 <b>DB 가 끊겨도 200 이 나온다</b>. 배포 이력·승인·서버 정보가 전부 DB 에 있으므로
 * DB 없이 뜬 배포시스템은 살아 있는 게 아니다. 실제 커넥션을 하나 열어 확인한다.
 *
 * <p>인증 없이 접근할 수 있어야 감시가 되므로 {@code SecurityConfig} 에서 permitAll 로 열되,
 * 내부 구조가 드러나지 않도록 응답은 상태와 사유만 담는다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private static final int DB_CHECK_TIMEOUT_SEC = 3;

    private final DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean dbUp = isDatabaseReachable();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("db", dbUp ? "UP" : "DOWN");
        return dbUp ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
    }

    private boolean isDatabaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(DB_CHECK_TIMEOUT_SEC);
        } catch (Exception e) {
            // 감시가 초 단위로 때리는 경로라 스택트레이스로 로그를 채우지 않는다.
            log.warn("헬스체크 DB 확인 실패: {}", e.getMessage());
            return false;
        }
    }
}
