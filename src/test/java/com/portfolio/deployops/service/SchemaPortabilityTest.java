package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스키마가 <b>설치 대상 DB 버전</b>에서 실제로 실행되는지 지킨다.
 *
 * <p>스키마는 개발 PC 의 MariaDB 에서 덤프해 만든다. 그런데 설치 대상인 Rocky 8 의 기본
 * MariaDB 는 <b>10.3</b> 이라, 개발 PC 가 더 최신(11.4+)이면 그 버전에만 있는 문법이
 * 덤프에 딸려 나온다. 그러면 로컬에서는 멀쩡한데 <b>실 설치에서 기동이 실패</b>한다.
 *
 * <p>실제로 겪은 사고 — {@code utf8mb4_uca1400_ai_ci}(11.4+ 전용)가 덤프에 섞여 들어가
 * Rocky 8 노드에서 {@code Unknown collation} 으로 Flyway 가 죽었다. 파일만 보면 알 수 없고
 * 설치를 돌려야 드러나는 부류라, 문자열로 고정해 둔다.
 */
class SchemaPortabilityTest {

    /** 주석은 뺀 실행 SQL 만. 주석에는 "이 collation 을 쓰지 말라"는 설명이 들어 있다. */
    private static String executableSql() throws Exception {
        String raw = Files.readString(Path.of("src", "main", "resources", "db", "migration", "V1__baseline.sql"));
        return raw.lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    @DisplayName("MariaDB 11.4+ 전용 collation 을 쓰지 않는다 (대상은 Rocky 8 의 10.3)")
    void baselineAvoidsCollationsMissingOnTheTargetMariaDb() throws Exception {
        // uca1400 계열은 MariaDB 11.4 에서 추가됐다. 10.3 에는 존재하지 않는다.
        assertThat(executableSql()).doesNotContain("uca1400");
    }

    @Test
    @DisplayName("멱등 시드 INSERT 에 FROM DUAL 이 있다 (10.3 은 FROM 없는 SELECT ... WHERE 를 거절)")
    void idempotentSeedInsertsCarryFromDual() throws Exception {
        String sql = executableSql();

        // "SELECT 값들 WHERE NOT EXISTS(...)" 는 최신 MariaDB 에서는 통과하지만 10.3 에서는
        // 문법 오류다. 개발 PC 에서 드러나지 않고 실 설치에서만 터지는 부류.
        long whereNotExists = sql.lines().filter(l -> l.contains("WHERE NOT EXISTS")).count();
        long withFromDual = sql.lines().filter(l -> l.contains("FROM DUAL WHERE NOT EXISTS")).count();
        assertThat(withFromDual).isEqualTo(whereNotExists);
    }

    @Test
    @DisplayName("설치기가 DB 를 만들 때 쓰는 collation 과 테이블 collation 이 같다")
    void baselineCollationMatchesTheOneInstallerCreatesTheDatabaseWith() throws Exception {
        String installer = Files.readString(Path.of("installer", "components", "common.sh"));

        // 둘이 어긋나면 DB 기본값과 테이블이 달라져, 조인·비교에서 조용히 collation 충돌이 난다.
        assertThat(installer).contains("COLLATE utf8mb4_unicode_ci");
        assertThat(executableSql()).contains("COLLATE=utf8mb4_unicode_ci");
    }
}
