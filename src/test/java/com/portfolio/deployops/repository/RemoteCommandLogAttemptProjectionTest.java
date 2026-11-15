package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import tools.jackson.databind.json.JsonMapper;
import com.portfolio.deployops.controller.DeployRequestApiController;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.service.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RemoteCommandLogAttemptProjectionTest {
    @Test
    void actualMapperQueriesAndEvidenceKeepDurableAttemptLinksAndLegacyNull() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:log_attempt_projection;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE deploy_request (id BIGINT PRIMARY KEY, project_id BIGINT)");
            sql.execute("INSERT INTO deploy_request VALUES (11,1)");
            sql.execute("""
                CREATE TABLE remote_command_log (
                    id BIGINT PRIMARY KEY, deploy_request_id BIGINT, target_attempt_id BIGINT,
                    server_id BIGINT, server_name VARCHAR(200), command_display VARCHAR(200),
                    stdout_text VARCHAR(200), stderr_text VARCHAR(200), exit_code INT, success BOOLEAN,
                    started_at TIMESTAMP, finished_at TIMESTAMP, duration_ms BIGINT, created_at TIMESTAMP)
                """);
            sql.execute("INSERT INTO remote_command_log VALUES (7,11,70,3,'was1','deploy','ok','',0,TRUE,NOW(),NOW(),10,NOW())");
            sql.execute("INSERT INTO remote_command_log VALUES (8,11,80,4,'was2','deploy','ok','',0,TRUE,NOW(),NOW(),20,NOW())");
            sql.execute("INSERT INTO remote_command_log VALUES (6,11,NULL,3,'legacy','deploy','ok','',0,TRUE,NOW(),NOW(),30,NOW())");
        }
        var config = new Configuration(new Environment("fixture",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(RemoteCommandLogRepository.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var repo = session.getMapper(RemoteCommandLogRepository.class);
            assertThat(repo.findByDeployRequestId(11L)).extracting(RemoteCommandLog::getTargetAttemptId)
                .containsExactly(null,70L,80L);
            assertThat(repo.findBoundedByRequest(11L,2)).extracting(RemoteCommandLog::getTargetAttemptId)
                .containsExactly(80L,70L);
            assertThat(repo.findRecentByProjectIdAndServerId(1L,3L,10))
                .extracting(RemoteCommandLog::getTargetAttemptId).containsExactly(70L,null);
            assertThat(repo.findRecentByProjectId(1L,2)).extracting(RemoteCommandLog::getTargetAttemptId)
                .containsExactly(80L,70L);
            var request = DeployRequest.builder().id(11L).reqNo("QA-11")
                .project(Project.builder().id(1L).name("fixture").build()).environment(DeployEnvironment.PROD).status(DeployStatus.COMPLETED).build();
            var requests = mock(DeployRequestService.class);
            when(requests.findById(11L,null)).thenReturn(request);
            var mapper = JsonMapper.builder().findAndAddModules().build();
            var controller = new DeployRequestApiController(requests,mock(AuditLogService.class),
                mock(ExecutionPolicyService.class),mapper,repo);
            var body = mapper.readTree(controller.evidence(11L,null).getBody());
            assertThat(body.path("remoteCommandLogs").get(1).path("targetAttemptId").asLong()).isEqualTo(70L);
            assertThat(body.path("remoteCommandLogs").get(2).path("targetAttemptId").asLong()).isEqualTo(80L);
            assertThat(body.path("remoteCommandLogs").get(0).path("targetAttemptId").isNull()).isTrue();
            assertThat(body.path("items").size()).isZero();
        }
    }
}
