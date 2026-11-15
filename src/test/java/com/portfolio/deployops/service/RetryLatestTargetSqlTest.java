package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class RetryLatestTargetSqlTest {
    @Test
    void latestOutcomePerSameRequestControlsReuseAndNeverAnOlderSuccess() throws Exception {
        String sql = String.join(" ", RemoteCommandLogRepository.class
            .getMethod("findLatestSuccessfulServerIds", Long.class).getAnnotation(Select.class).value())
            .replace("#{requestId}", "?");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retry_latest;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE remote_command_log (id BIGINT PRIMARY KEY, deploy_request_id BIGINT, server_id BIGINT, success BOOLEAN, exit_code INT, target_attempt_id BIGINT)");
                statement.execute("CREATE TABLE target_deployment_attempt (id BIGINT PRIMARY KEY, deploy_request_id BIGINT, server_id BIGINT)");
                statement.execute("INSERT INTO remote_command_log VALUES (1,9,11,TRUE,0,NULL),(2,9,11,FALSE,1,NULL),(3,9,12,FALSE,1,NULL),(4,9,12,TRUE,0,NULL),(5,8,13,TRUE,0,NULL),(6,9,14,TRUE,1,NULL),(7,9,15,FALSE,0,NULL)");
            }
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, 9L); statement.setLong(2, 9L); statement.setLong(3, 9L);
                var ids = new ArrayList<Long>();
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getLong(1)); }
                assertThat(ids).containsExactly(12L);
                try (var write = connection.createStatement()) {
                    write.execute("INSERT INTO target_deployment_attempt VALUES (100,9,12)");
                }
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isFalse(); }
                try (var write = connection.createStatement()) {
                    write.execute("INSERT INTO remote_command_log VALUES (8,9,12,TRUE,0,100)");
                }
                try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(12L); }
            }
        }
    }
}
