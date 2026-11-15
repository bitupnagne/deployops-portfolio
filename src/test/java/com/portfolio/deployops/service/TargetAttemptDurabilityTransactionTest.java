package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.ExecutionGuardRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class TargetAttemptDurabilityTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:attempt_durability;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean RemoteCommandLogService service(DataSource source) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE attempt_fixture (id BIGINT PRIMARY KEY, request_id BIGINT, target_id BIGINT)");
            var repository = Mockito.mock(RemoteCommandLogRepository.class);
            Mockito.when(repository.insertTargetAttempt(Mockito.any())).thenAnswer(call -> {
                com.portfolio.deployops.domain.TargetDeploymentAttempt attempt = call.getArgument(0);
                jdbc.update("INSERT INTO attempt_fixture VALUES (77,?,?)", attempt.getDeployRequestId(), attempt.getServerId());
                attempt.setId(77L); return 1;
            });
            var guard = Mockito.mock(ExecutionGuardRepository.class);
            Mockito.when(guard.lockSingleton()).thenReturn(1);
            return new RemoteCommandLogService(repository, new SecretMaskingService(), guard);
        }
    }

    @Test
    void requiresNewAttemptRemainsCommittedWhenOuterExecutionThrowsAndRollsBack() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var service = context.getBean(RemoteCommandLogService.class);
            var outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> outer.execute(status -> {
                service.beginTargetAttempt(DeployRequest.builder().id(10L).planSnapshotHash("a".repeat(64)).build(),
                    Server.builder().id(20L).build(), "registry/app@sha256:fixed");
                throw new RemoteExecutionException("SSH timed out after attempt commit");
            })).hasMessageContaining("SSH timed out");
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attempt_fixture WHERE request_id=10 AND target_id=20", Integer.class)).isEqualTo(1);
        }
    }
}
