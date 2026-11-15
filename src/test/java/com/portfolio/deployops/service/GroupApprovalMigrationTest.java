package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class GroupApprovalMigrationTest {
    @Test
    void migrationPreservesOldDecisionsAllowsNextStageAndRejectsSameStageReplay() throws Exception {
        try (var connection=DriverManager.getConnection("jdbc:h2:mem:group_approval_v49;MODE=MySQL");
             var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE approval_policy(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE server_group(id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE approval_record(id BIGINT PRIMARY KEY,deploy_request_id BIGINT NOT NULL,step_order INT NOT NULL,approver_id BIGINT NOT NULL)");
            sql.execute("CREATE UNIQUE INDEX uk_approval_record_request_approver ON approval_record(deploy_request_id,approver_id)");
            sql.execute("INSERT INTO approval_policy VALUES(1)");
            sql.execute("INSERT INTO server_group VALUES(10)");
            sql.execute("INSERT INTO approval_record VALUES(1,100,1,5)");
            ScriptUtils.executeSqlScript(connection,new FileSystemResource("src/main/resources/db/migration/V49__server_group_approval_policy.sql"));
            try(var rows=sql.executeQuery("SELECT approval_policy_id FROM server_group WHERE id=10")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getObject(1)).isNull();
            }
            sql.execute("UPDATE server_group SET approval_policy_id=1 WHERE id=10");
            assertThatThrownBy(() -> sql.execute("DELETE FROM approval_policy WHERE id=1")).isInstanceOf(SQLException.class);
            sql.execute("INSERT INTO approval_record VALUES(2,100,2,5)");
            assertThatThrownBy(() -> sql.execute("INSERT INTO approval_record VALUES(3,100,2,5)")).isInstanceOf(SQLException.class);
            try(var rows=sql.executeQuery("SELECT step_order FROM approval_record ORDER BY id")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
