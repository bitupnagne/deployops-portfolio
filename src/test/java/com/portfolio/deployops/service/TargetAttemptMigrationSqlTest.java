package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.deployops.domain.TargetDeploymentAttempt;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class TargetAttemptMigrationSqlTest {
    @Test
    void actualMigrationAndInsertRequireRunningRequestWithSamePlanBeforeAnyAttempt() throws Exception {
        String script = String.join(" ", RemoteCommandLogRepository.class.getMethod("insertTargetAttempt",
            TargetDeploymentAttempt.class).getAnnotation(Insert.class).value());
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:attempt_migration;MODE=MySQL")) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE deploy_request (id BIGINT PRIMARY KEY, plan_snapshot_hash CHAR(64), status VARCHAR(32))");
                sql.execute("CREATE TABLE server (id BIGINT PRIMARY KEY)");
                sql.execute("CREATE TABLE remote_command_log (id BIGINT PRIMARY KEY)");
                sql.execute("INSERT INTO server VALUES (20)");
                sql.execute("INSERT INTO deploy_request VALUES (1,'" + "a".repeat(64) + "','PROD_DEPLOYING'),(2,'" + "a".repeat(64) + "','CANCELLED'),(3,'" + "b".repeat(64) + "','PROD_DEPLOYING')");
            }
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/db/migration/V33__durable_target_deployment_attempt.sql"));
            try (var sql = connection.createStatement()) {
                sql.execute("UPDATE deploy_request SET target_attempt_tracking_started_at=NOW(6) WHERE id IN (1,2,3)");
                sql.execute("INSERT INTO deploy_request(id,plan_snapshot_hash,status) VALUES(4,'" + "a".repeat(64) + "','PROD_DEPLOYING')");
            }
            for (long requestId : new long[]{1L, 2L, 3L, 4L}) {
                Map<String, Object> params = Map.of("deployRequestId", requestId, "serverId", 20L,
                    "planHash", "a".repeat(64), "artifactHash", "c".repeat(64));
                var bound = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class).getBoundSql(params);
                try (var sql = connection.prepareStatement(bound.getSql(), Statement.RETURN_GENERATED_KEYS)) {
                    int index = 1;
                    for (var parameter : bound.getParameterMappings()) sql.setObject(index++, params.get(parameter.getProperty()));
                    assertThat(sql.executeUpdate()).isEqualTo(requestId == 1L ? 1 : 0);
                    if (requestId == 1L) {
                        try (var keys = sql.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); assertThat(keys.getLong(1)).isPositive(); }
                    }
                }
            }
            try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM target_deployment_attempt")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(1L);
            }
        }
    }
    @Test
    void initialCasStampsTrackingButFailedRetryNeverBackfillsLegacyHistory() throws Exception {
        String script = String.join(" ", com.portfolio.deployops.repository.DeployRequestRepository.class
            .getMethod("compareAndSetExecutionStart", Long.class, com.portfolio.deployops.domain.DeployStatus.class,
                com.portfolio.deployops.domain.DeployStatus.class, com.portfolio.deployops.domain.ExecutionTrigger.class)
            .getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        try (var connection=DriverManager.getConnection("jdbc:h2:mem:tracking_cas;MODE=MySQL")) {
            try(var sql=connection.createStatement()) {
                sql.execute("CREATE TABLE deploy_request(id BIGINT PRIMARY KEY,status VARCHAR(32),execution_trigger VARCHAR(32),updated_at DATETIME(6),target_attempt_tracking_started_at DATETIME(6))");
                sql.execute("INSERT INTO deploy_request(id,status) VALUES(1,'APPROVAL_COMPLETED'),(2,'PROD_FAILED')");
            }
            for(long id:new long[]{1L,2L}) {
                var from=id==1L?com.portfolio.deployops.domain.DeployStatus.APPROVAL_COMPLETED:com.portfolio.deployops.domain.DeployStatus.PROD_FAILED;
                Map<String,Object> params=Map.of("id",id,"from",from.name(),"to","PROD_DEPLOYING","trigger","MANUAL");
                var bound=new XMLLanguageDriver().createSqlSource(new Configuration(),script,Map.class).getBoundSql(params);
                try(var sql=connection.prepareStatement(bound.getSql())) {
                    int n=1;for(var parameter:bound.getParameterMappings())sql.setObject(n++,params.get(parameter.getProperty()));
                    assertThat(sql.executeUpdate()).isEqualTo(1);
                }
            }
            try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT id,target_attempt_tracking_started_at FROM deploy_request ORDER BY id")) {
                rows.next();assertThat(rows.getTimestamp(2)).isNotNull();
                rows.next();assertThat(rows.getTimestamp(2)).isNull();
            }
        }
    }
    @Test
    void untrackedHistoryQueryIncludesLowerIdLaterExecutionButExcludesOtherEnvironmentAndProject() throws Exception {
        String full=String.join(" ",com.portfolio.deployops.repository.DeployRequestRepository.class
            .getMethod("findUntrackedExecutionsSince",Long.class,Long.class,com.portfolio.deployops.domain.DeployEnvironment.class,java.time.LocalDateTime.class)
            .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String script="SELECT r.id FROM deploy_request r "+full.substring(full.indexOf("WHERE r.id !="));
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:legacy_history;MODE=MySQL")) {
            try(var sql=connection.createStatement()) {
                sql.execute("CREATE TABLE deploy_request(id BIGINT PRIMARY KEY,project_id BIGINT,environment VARCHAR(8),target_attempt_tracking_started_at DATETIME(6))");
                sql.execute("CREATE TABLE status_history(deploy_request_id BIGINT,status VARCHAR(32),created_at DATETIME(6))");
                sql.execute("INSERT INTO deploy_request VALUES(10,3,'PROD',NULL),(11,4,'PROD',NULL),(12,3,'TEST',NULL),(13,3,'PROD',NULL),(14,3,'PROD',NOW(6)),(20,3,'PROD',NULL)");
                sql.execute("INSERT INTO status_history VALUES(10,'PROD_DEPLOYING','2026-10-08 02:31:00'),(11,'PROD_DEPLOYING','2026-10-08 02:31:00'),(12,'TEST_DEPLOYING','2026-10-08 02:31:00'),(13,'PROD_DEPLOYING','2026-10-08 02:29:00'),(14,'PROD_DEPLOYING','2026-10-08 02:31:00'),(20,'PROD_DEPLOYING','2026-10-08 02:31:00')");
            }
            Map<String,Object> params=Map.of("requestId",20L,"projectId",3L,"environment","PROD","since",java.time.LocalDateTime.of(2026,10,8,2,30));
            var bound=new XMLLanguageDriver().createSqlSource(new Configuration(),script,Map.class).getBoundSql(params);
            try(var sql=connection.prepareStatement(bound.getSql())) {
                int n=1;for(var parameter:bound.getParameterMappings())sql.setObject(n++,params.get(parameter.getProperty()));
                try(var rows=sql.executeQuery()) {assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(10L);assertThat(rows.next()).isFalse();}
            }
        }
    }

    @Test
    void publicArtifactContainsDurableAttemptMigration() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V33__durable_target_deployment_attempt.sql")) {
            assertThat(resource).isNotNull();
            assertThat(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("CREATE TABLE", "deployment_attempt");
        }
    }
}
