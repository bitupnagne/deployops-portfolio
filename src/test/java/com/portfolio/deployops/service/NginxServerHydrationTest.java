package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.AuthType;
import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real MyBatis result mapping -> approval/rollback service -> actual SSH target validation.
 * Only JDBC row delivery, repository writes and process execution are mocked; no prebuilt Server hides missing fields.
 */
class NginxServerHydrationTest {
    private static final String BACKUP = "/var/backups/deploy-system/nginx/20260922-demo-app-1";
    private static final String BACKUP_SHA = "a".repeat(64);
    private static final String CURRENT_SHA = "b".repeat(64);
    private static final String CONFIG_SHA = "c".repeat(64);

    @ParameterizedTest
    @ValueSource(strings = {"findById", "findAll", "findCurrentCertificates"})
    void everySharedResultMapReadsThePersistedConnectionFields(String operation) throws Exception {
        NginxChangeRequest request = mappedRequest(operation);

        assertThat(request.getServer().getId()).isEqualTo(5L);
        assertThat(request.getServer().getIpAddress()).isEqualTo("198.18.32.20");
        assertThat(request.getServer().getDeployAccount()).isEqualTo("policy-deploy");
        // Non-default values prove explicit mapping, rather than Lombok defaults, supplied them.
        assertThat(request.getServer().getSshPort()).isEqualTo(2227);
        assertThat(request.getServer().getAuthType()).isEqualTo(AuthType.PASSWORD);
        assertThat(request.getBackupSnapshotHash()).isEqualTo(BACKUP_SHA);
        assertThat(request.getAppliedSnapshotHash()).isEqualTo(CURRENT_SHA);
        assertThat(request.getNativeRequestId()).isEqualTo(77L);
    }

    @Test
    void approvedRequestUsesMappedServerWithoutLosingAccountOrCustomSshPort() throws Exception {
        exerciseExecution(false);
    }

    @Test
    void rollbackUsesTheSameMappedTargetConnectionContract() throws Exception {
        exerciseExecution(true);
    }

    @Test
    void missingNativeProofStillFailsAfterHydratedTargetValidation() throws Exception {
        exerciseExecution(false, true);
    }

    @Test
    void mismatchedFrozenNativeGenerationRefusesRollbackBeforeSsh() throws Exception {
        exerciseExecution(true, true);
    }

    private void exerciseExecution(boolean rollback) throws Exception {
        exerciseExecution(rollback, false);
    }

    private void exerciseExecution(boolean rollback, boolean invalidEvidence) throws Exception {
        NginxChangeRequest pending = mappedRequest("findById");
        NginxChangeRequest completed = mappedRequest("findById");
        if (rollback) {
            pending.setRollbackOfId(99L);
            pending.setRollbackSnapshotHash(BACKUP_SHA);
            pending.setExpectedCurrentSnapshotHash(CURRENT_SHA);
            pending.setExpectedNativeRequestId(invalidEvidence ? 76L : 77L);
            pending.setRestoreTargetId(44L);
        }
        pending.setBackupPath(BACKUP);
        NginxChangeStatus expected = rollback ? NginxChangeStatus.ROLLED_BACK : NginxChangeStatus.APPLIED;
        completed.setStatus(expected);
        var requests = mock(NginxChangeRequestRepository.class);
        var ssh = mock(SshCommandService.class);
        var guard = mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
        var service = new NginxChangeService(requests, mock(ServerRepository.class), ssh, guard);
        when(guard.lockSingleton()).thenReturn(1);
        when(requests.findById(1L)).thenReturn(Optional.of(pending), Optional.of(completed));
        when(requests.acquireApproval(1L, 20L)).thenReturn(1);
        when(requests.storeNativeProof(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(1);
        if (rollback) {
            NginxChangeRequest source = mappedRequest("findById");
            source.setId(99L);
            source.setStatus(NginxChangeStatus.APPLIED);
            source.setBackupPath(BACKUP);
            when(requests.findById(99L)).thenReturn(Optional.of(source));
            when(requests.findLatestCompletedId(5L, "demo-app")).thenReturn(99L);
            when(requests.acquireRollbackApproval(1L, 20L, 99L, 5L, "demo-app")).thenReturn(1);
        }
        when(ssh.execute(any(), any())).thenAnswer(invocation -> {
            Server server = invocation.getArgument(0);
            assertThat(server.isAgentControlled()).isTrue();
            assertThat(server.getAgentBindingId()).isEqualTo("web-binding");
            assertThat(server.getId()).isEqualTo(5L);
            assertThat(server.getDeployAccount()).isEqualTo("policy-deploy");
            assertThat(server.getSshPort()).isEqualTo(2227);
            LocalDateTime now = LocalDateTime.now();
            String actualSnapshot = rollback ? BACKUP_SHA : CURRENT_SHA;
            long nativeId = rollback ? 44L : 1L;
            String proof = "BACKUP_PATH=" + BACKUP + "\nBACKUP_SNAPSHOT_SHA=" + CURRENT_SHA
                + "\nCURRENT_SNAPSHOT_SHA=" + actualSnapshot + "\nCURRENT_CONFIG_SHA=" + CONFIG_SHA
                + "\nCURRENT_REQUEST_ID=" + nativeId + "\nCURRENT_CONFIG_PRESENT=true\n";
            return new RemoteCommandResult(0, invalidEvidence ? "BACKUP_PATH=" + BACKUP : proof,
                "", "ssh", now, now, 1L);
        });

        if (invalidEvidence) {
            if (rollback) {
                assertThatThrownBy(() -> service.approveAndApply(1L, 20L))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("현재 세대 정보가 다릅니다");
                verifyNoInteractions(ssh);
                verify(requests, never()).acquireRollbackApproval(any(), any(), any(), any(), any());
            } else {
                assertThatThrownBy(() -> service.approveAndApply(1L, 20L))
                    .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("digest 증거");
                verify(ssh).execute(eq(pending.getServer()), any());
                verify(requests).finish(eq(1L), eq(NginxChangeStatus.FAILED), eq(BACKUP), anyString());
            }
            verify(requests, never()).storeNativeProof(any(), any(), any(), any(), any());
            return;
        }

        assertThat(service.approveAndApply(1L, 20L).getStatus()).isEqualTo(expected);

        verify(requests).finish(1L, expected, BACKUP, null);
        verify(requests).storeNativeProof(1L, CURRENT_SHA, rollback ? BACKUP_SHA : CURRENT_SHA,
            CONFIG_SHA, rollback ? 44L : 1L);
        if (rollback) {
            verify(ssh).execute(eq(pending.getServer()), eq(List.of("sudo", "-n", NginxChangeService.AGENT,
                "rollback", "--backup-dir", BACKUP, "--policy-name", "demo-app",
                "--expected-current-request-id", "77", "--expected-current-snapshot", CURRENT_SHA,
                "--expected-snapshot", BACKUP_SHA)));
            verify(requests).markSourceRolledBack(99L);
        } else {
            verify(ssh).execute(eq(pending.getServer()), any());
        }
    }

    private NginxChangeRequest mappedRequest(String operation) throws Exception {
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(NginxChangeRequestRepository.class);
        var statement = config.getMappedStatement(NginxChangeRequestRepository.class.getName() + "." + operation);
        var boundSql = statement.getBoundSql(1L);
        assertThat(boundSql.getSql()).contains("s.ssh_port AS server_ssh_port",
            "s.deploy_account AS server_deploy_account", "s.auth_type AS server_auth_type", "s.id = n.server_id");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("server_control_mode", "AGENT"); row.put("server_agent_binding_id", "web-binding");
        row.put("requested_control_mode", "AGENT"); row.put("requested_agent_binding_id", "web-binding");
        row.put("id", 1L); row.put("server_id", 5L); row.put("server_display_name", "devweb1");
        row.put("server_ip", "198.18.32.20"); row.put("server_type", "WEB");
        row.put("server_ssh_port", 2227); row.put("server_deploy_account", "policy-deploy"); row.put("server_auth_type", "PASSWORD");
        row.put("requested_by_id", 10L); row.put("requester_name", "operator"); row.put("requester_login_id", "operator");
        row.put("status", "PENDING"); row.put("policy_name", "demo-app"); row.put("server_name", "demo-app.localhost");
        row.put("backup_snapshot_hash", BACKUP_SHA); row.put("applied_snapshot_hash", CURRENT_SHA);
        row.put("applied_config_hash", CONFIG_SHA); row.put("native_request_id", 77L);
        row.put("listen_port", 80); row.put("upstream_host", "198.18.33.11"); row.put("upstream_port", 9090);
        row.put("tls_protocols", "TLSv1.2 TLSv1.3"); row.put("client_max_body_mb", 50);
        row.put("proxy_read_timeout_sec", 30); row.put("proxy_send_timeout_sec", 30);
        List<String> columns = new ArrayList<>(row.keySet());
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index); Object value = row.get(column);
            when(metadata.getColumnLabel(index + 1)).thenReturn(column);
            when(metadata.getColumnName(index + 1)).thenReturn(column);
            when(metadata.getColumnType(index + 1)).thenReturn(value instanceof Number ? Types.BIGINT : Types.VARCHAR);
            when(metadata.getColumnClassName(index + 1)).thenReturn(value.getClass().getName());
        }
        var rows = mock(ResultSet.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(anyString())).thenAnswer(call -> String.valueOf(row.get(call.getArgument(0))));
        when(rows.getLong(anyString())).thenAnswer(call -> ((Number) row.get(call.getArgument(0))).longValue());
        when(rows.getInt(anyString())).thenAnswer(call -> ((Number) row.get(call.getArgument(0))).intValue());
        var jdbcStatement = mock(Statement.class);
        when(jdbcStatement.getResultSet()).thenReturn(rows);
        when(jdbcStatement.getUpdateCount()).thenReturn(-1);
        var executor = mock(org.apache.ibatis.executor.Executor.class);
        when(executor.createCacheKey(any(),any(),any(),any())).thenReturn(new org.apache.ibatis.cache.CacheKey());
        when(executor.query(any(),any(),any(),any(),any(),any())).thenReturn(List.of());
        var handler = new DefaultResultSetHandler(executor, statement, null, null, boundSql, RowBounds.DEFAULT);
        return (NginxChangeRequest) handler.handleResultSets(jdbcStatement).getFirst();
    }
}
