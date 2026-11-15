package com.portfolio.deployops.init;

import org.springframework.jdbc.core.JdbcTemplate;

/** Check before either initial schema data or a shared visitor account can be written. */
public final class PublicDemoBootstrapGuard {
    private PublicDemoBootstrapGuard() {}

    public static void requireCredentials(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank() || password.length() < 12) {
            throw new IllegalStateException("공개 데모 계정과 12자 이상의 비밀번호를 외부 설정으로 제공해 주세요.");
        }
    }

    public static void requireDatabaseName(String database) {
        if (!"deployops_demo".equals(database)) {
            throw new IllegalStateException("공개 데모는 별도의 전용 데이터베이스에서만 시작할 수 있습니다.");
        }
    }

    static void requireIsolatedDatabase(JdbcTemplate jdbc, String username, String password) {
        requireCredentials(username, password);
        requireDatabaseName(jdbc.queryForObject("SELECT DATABASE()", String.class));
    }
}
