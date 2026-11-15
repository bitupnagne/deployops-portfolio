package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.repository.DeployRequestRepository;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class RetrySupersessionSqlTest {
    @Test
    void actualMapperSqlUsesAttemptTimeOverlapProjectAndEnvironmentNotRequestIdOrder() throws Exception {
        String script = String.join(" ", DeployRequestRepository.class.getMethod("countSupersedingTargetAttempts",
            Long.class, Long.class, String.class, List.class, LocalDateTime.class).getAnnotation(Select.class).value());
        Map<String, Object> params = Map.of("requestId", 20L, "projectId", 3L, "environment", "PROD",
            "targetIds", List.of(11L, 12L), "since", LocalDateTime.of(2026, 10, 8, 2, 30));
        var bound = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retry_supersession;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE deploy_request (id BIGINT PRIMARY KEY, project_id BIGINT, environment VARCHAR(20))");
                statement.execute("CREATE TABLE remote_command_log (id BIGINT PRIMARY KEY, deploy_request_id BIGINT, server_id BIGINT, created_at TIMESTAMP)");
                statement.execute("CREATE TABLE target_deployment_attempt (id BIGINT PRIMARY KEY, deploy_request_id BIGINT, server_id BIGINT, created_at TIMESTAMP)");
                statement.execute("INSERT INTO deploy_request VALUES (20,3,'PROD'),(10,3,'PROD'),(30,4,'PROD'),(40,3,'TEST'),(50,3,'PROD')");
                statement.execute("INSERT INTO remote_command_log VALUES (1,20,11,'2026-10-08 02:31:00'),(2,30,11,'2026-10-08 02:32:00'),(3,40,11,'2026-10-08 02:32:00'),(4,50,99,'2026-10-08 02:32:00'),(5,10,11,'2026-10-08 02:29:00')");
            }
            try (var statement = connection.prepareStatement(bound.getSql())) {
                int index = 1;
                for (var parameter : bound.getParameterMappings()) {
                    Object value = bound.hasAdditionalParameter(parameter.getProperty())
                        ? bound.getAdditionalParameter(parameter.getProperty()) : params.get(parameter.getProperty());
                    statement.setObject(index++, value);
                }
                try (var result = statement.executeQuery()) { result.next(); assertThat(result.getLong(1)).isZero(); }
                try (var insert = connection.createStatement()) {
                    insert.execute("UPDATE remote_command_log SET created_at='2026-10-08 02:32:00' WHERE id=5");
                }
                try (var result = statement.executeQuery()) { result.next(); assertThat(result.getLong(1)).isEqualTo(1L); }
                try (var change = connection.createStatement()) {
                    change.execute("DELETE FROM remote_command_log WHERE id=5");
                    change.execute("INSERT INTO target_deployment_attempt VALUES (100,10,12,'2026-10-08 02:32:00')");
                }
                try (var result = statement.executeQuery()) { result.next(); assertThat(result.getLong(1)).isEqualTo(1L); }
            }
        }
    }
}
