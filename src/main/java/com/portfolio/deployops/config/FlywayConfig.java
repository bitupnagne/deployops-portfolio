package com.portfolio.deployops.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 검증 실패를 운영 사고 신호로 보존한 채 마이그레이션한다.
 *
 * <p>V1은 운영 반입 시점부터 동결한다. 적용된 SQL의 checksum이 달라지면 정상 기동을
 * 거부해야 하며, 부팅 때 자동으로 {@code repair()}해서는 안 된다. 과거 개발 DB를 한 번
 * 정리해야 하는 예외 상황만 명시적인 두 설정을 동시에 넣어 허용하고 WARN 로그를 남긴다.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(
        @Value("${deploy.flyway.repair-on-migrate:false}") boolean repairOnMigrate,
        @Value("${deploy.flyway.repair-confirmation:}") String repairConfirmation,
        @Value("${app.public-demo.enabled:false}") boolean publicDemo,
        @Value("${app.public-demo.username:${PORTFOLIO_DEMO_USERNAME:}}") String demoUsername,
        @Value("${app.public-demo.password:${PORTFOLIO_DEMO_PASSWORD:}}") String demoPassword
    ) {
        return flyway -> {
            if (publicDemo) {
                com.portfolio.deployops.init.PublicDemoBootstrapGuard.requireCredentials(demoUsername, demoPassword);
                try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                    com.portfolio.deployops.init.PublicDemoBootstrapGuard.requireDatabaseName(connection.getCatalog());
                } catch (java.sql.SQLException error) {
                    throw new IllegalStateException("공개 데모 전용 데이터베이스를 확인할 수 없습니다.");
                }
            }
            if (repairOnMigrate) {
                if (!"I_ACKNOWLEDGE_FLYWAY_CHECKSUM_REWRITE".equals(repairConfirmation)) {
                    throw new IllegalStateException(
                        "Flyway repair 요청이 거부되었습니다. 일회성 확인 문구와 운영 승인 기록을 확인하세요."
                    );
                }
                log.warn("명시적으로 승인된 일회성 Flyway repair를 실행합니다. 실행 후 설정을 즉시 제거하세요.");
                flyway.repair();
            }
            flyway.migrate();
        };
    }
}
