package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.domain.NginxChangeRequest;
import com.portfolio.deployops.domain.NginxChangeStatus;
import com.portfolio.deployops.domain.NginxPolicyType;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerControlMode;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.dto.NginxPolicyForm;
import com.portfolio.deployops.repository.NginxChangeRequestRepository;
import com.portfolio.deployops.repository.ServerRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class NginxChangeServiceTest {
    @Test
    void uncertainAgentExecutionIsNotRecordedAsAnOrdinaryFailedApply() {
        var pending = request(10L);
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        when(sshCommandService.execute(eq(pending.getServer()), any()))
            .thenThrow(new AgentOutcomeUnknownException("에이전트 실행 결과를 확인해 주세요."));

        assertThatThrownBy(() -> service.approveAndApply(7L, 20L))
            .isInstanceOf(AgentOutcomeUnknownException.class);

        verify(repository).finish(7L, NginxChangeStatus.RESULT_UNKNOWN, null, "에이전트 실행 결과를 확인해 주세요.");
        verify(repository, never()).finish(eq(7L), eq(NginxChangeStatus.FAILED), any(), any());
        verify(repository, never()).markSourceRolledBack(any());
    }

    @Test
    void newAgentPolicyPinsRegistrationAndUnregisteredAgentCannotCreateARequest() {
        Server agent = webServer();
        agent.setAgentBindingId(null);
        agent.setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        when(serverRepository.findById(1L)).thenReturn(Optional.of(agent));
        assertThatThrownBy(() -> service.create(form(), null, null, 10L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("등록을 승인");
        verify(repository, never()).insert(any());
        agent.setAgentBindingId("web-registration-1");
        var created = service.create(form(), null, null, 10L);
        org.assertj.core.api.Assertions.assertThat(created.getRequestedControlMode())
            .isEqualTo(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        org.assertj.core.api.Assertions.assertThat(created.getRequestedAgentBindingId()).isEqualTo("web-registration-1");
    }

    @Test
    void replacingAgentRegistrationRejectsApprovalBeforeNativeCommands() {
        var pending = request(10L);
        pending.setRequestedControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        pending.setRequestedAgentBindingId("old-web-registration");
        pending.getServer().setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        pending.getServer().setAgentBindingId("replacement-web-registration");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approveAndApply(7L, 20L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("새 설정 변경 요청");
        verify(repository, never()).acquireApproval(any(), any());
        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void legacySshApprovalCannotBeReusedAfterAgentMigration() {
        var pending = request(10L);
        pending.setRequestedControlMode(null);
        pending.setRequestedAgentBindingId(null);
        pending.getServer().setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        pending.getServer().setAgentBindingId("web-registration-1");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approveAndApply(7L, 20L))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("접속 방식");
        verify(repository, never()).acquireApproval(any(), any());
        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void freshAgentApprovalExecutesWithTheCapturedRegistration() {
        var pending = request(10L);
        pending.getServer().setControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        pending.getServer().setAgentBindingId("web-registration-1");
        pending.setRequestedControlMode(com.portfolio.deployops.domain.ServerControlMode.AGENT);
        pending.setRequestedAgentBindingId("web-registration-1");
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        when(sshCommandService.execute(eq(pending.getServer()), any())).thenReturn(new RemoteCommandResult(
            0, "BACKUP_PATH=/var/backups/deploy-system/nginx/agent-7\nBACKUP_SNAPSHOT_SHA=" + NginxRollbackSnapshotTest.A + "\n"
                + NginxRollbackSnapshotTest.proof("CURRENT_", NginxRollbackSnapshotTest.A, NginxRollbackSnapshotTest.C, "7"),
            "", "agent", LocalDateTime.now(), LocalDateTime.now(), 1));

        service.approveAndApply(7L, 20L);

        verify(sshCommandService).execute(eq(pending.getServer()), any());
        verify(repository).finish(7L, NginxChangeStatus.APPLIED, "/var/backups/deploy-system/nginx/agent-7", null);
    }

    private final NginxChangeRequestRepository repository = mock(NginxChangeRequestRepository.class);
    private final ServerRepository serverRepository = mock(ServerRepository.class);
    private final SshCommandService sshCommandService = mock(SshCommandService.class);
    private final com.portfolio.deployops.repository.ExecutionGuardRepository guard = mock(com.portfolio.deployops.repository.ExecutionGuardRepository.class);
    private final NginxChangeService service = new NginxChangeService(repository, serverRepository, sshCommandService, guard);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "uploadDir", System.getProperty("java.io.tmpdir") + "/nginx-change-test");
        when(guard.lockSingleton()).thenReturn(1);
        when(repository.storeNativeProof(any(),any(),any(),any(),any())).thenReturn(1);
    }

    @Test
    void createRejectsNonWebServer() {
        Server was = Server.builder().id(1L).name("was01").serverType(ServerType.WAS).build();
        when(serverRepository.findById(1L)).thenReturn(Optional.of(was));

        assertThatThrownBy(() -> service.create(form(), null, null, 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("WEB 서버");

        verify(repository, never()).insert(any());
    }

    @Test
    void createRequiresCertificateAndKeyTogether() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(webServer()));
        MockMultipartFile cert = new MockMultipartFile("certificate", "server.pem", "application/x-pem-file", "certificate".getBytes());

        assertThatThrownBy(() -> service.create(form(), cert, null, 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("함께 업로드");
    }

    @Test
    void approveRejectsSelfApproval() {
        NginxChangeRequest request = request(10L);
        when(repository.findById(7L)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approveAndApply(7L, 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("달라야");

        verify(repository, never()).acquireApproval(any(), any());
        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void approvePassesOnlyWhitelistedPolicyArgumentsToAgent() {
        NginxChangeRequest pending = request(10L);
        NginxChangeRequest applied = request(10L);
        applied.setStatus(NginxChangeStatus.APPLIED);
        applied.setBackupPath("/var/backups/deploy-system/nginx/20260828-office-main-7");
        when(repository.findById(7L)).thenReturn(Optional.of(pending), Optional.of(applied));
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        LocalDateTime now = LocalDateTime.now();
        when(sshCommandService.execute(eq(pending.getServer()), any())).thenReturn(new RemoteCommandResult(
            0, "BACKUP_PATH=/var/backups/deploy-system/nginx/20260828-office-main-7\nBACKUP_SNAPSHOT_SHA=" + NginxRollbackSnapshotTest.A + "\n" + NginxRollbackSnapshotTest.proof("CURRENT_", NginxRollbackSnapshotTest.A, NginxRollbackSnapshotTest.C, "7"), "", "ssh", now, now, 10));

        service.approveAndApply(7L, 20L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(eq(pending.getServer()), args.capture());
        org.assertj.core.api.Assertions.assertThat(args.getValue()).containsSubsequence(
            "sudo", "-n", NginxChangeService.AGENT, "apply",
            "--request-id", "7",
            "--policy-name", "office-main",
            "--policy-type", "REVERSE_PROXY",
            "--server-name", "office.company.local",
            "--listen", "443",
            "--upstream-host", "198.18.12.40",
            "--upstream-port", "8080"
        ).doesNotContain("server {", "proxy_pass");
        verify(repository).finish(7L, NginxChangeStatus.APPLIED,
            "/var/backups/deploy-system/nginx/20260828-office-main-7", null);
    }

    @Test
    void staticSpaUsesManagedWebRootWithoutUpstream() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(webServer()));
        NginxPolicyForm form = form();
        form.setPolicyType("STATIC_SPA");
        form.setUpstreamHost(null);
        form.setWebRoot("/var/www/portal");

        service.create(form, null, null, 10L);

        ArgumentCaptor<NginxChangeRequest> saved = ArgumentCaptor.forClass(NginxChangeRequest.class);
        verify(repository).insert(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getPolicyType()).isEqualTo(NginxPolicyType.STATIC_SPA);
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getWebRoot()).isEqualTo("/var/www/portal");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getUpstreamHost()).isEqualTo("static");
    }

    @Test
    void staticPolicyRejectsWebRootOutsideManagedRoots() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(webServer()));
        NginxPolicyForm form = form();
        form.setPolicyType("STATIC_FILES");
        form.setUpstreamHost(null);
        form.setWebRoot("/etc/nginx");

        assertThatThrownBy(() -> service.create(form, null, null, 10L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("/var/www");
        verify(repository, never()).insert(any());
    }

    @Test
    void rollbackRequestRejectsAnOlderAppliedGeneration() {
        NginxChangeRequest old = request(10L);
        old.setStatus(NginxChangeStatus.APPLIED);
        old.setBackupPath("/var/backups/old");
        when(repository.findById(7L)).thenReturn(Optional.of(old));
        when(repository.findLatestCompletedId(1L, "office-main")).thenReturn(8L);

        assertThatThrownBy(() -> service.requestRollback(7L, 20L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("더 최신");
        verify(repository, never()).insert(any());
    }

    @Test
    void rollbackApprovalRechecksGenerationAfterRequestCreation() {
        NginxChangeRequest rollback = request(10L);
        rollback.setId(9L);
        rollback.setRollbackOfId(7L);
        rollback.setBackupPath("/var/backups/old");
        NginxChangeRequest source = request(10L);
        source.setStatus(NginxChangeStatus.APPLIED);
        source.setBackupPath("/var/backups/old");
        when(repository.findById(9L)).thenReturn(Optional.of(rollback));
        when(repository.findById(7L)).thenReturn(Optional.of(source));
        when(repository.findLatestCompletedId(1L, "office-main")).thenReturn(8L);

        assertThatThrownBy(() -> service.approveAndApply(9L, 20L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("더 최신");
        verify(repository, never()).acquireApproval(any(), any());
        verify(sshCommandService, never()).execute(any(), any());
    }

    @Test
    void staticApiRequestCapturesTypedRouteAndDisabledRequestClearsStaleFields() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(webServer()));
        NginxPolicyForm form = apiForm();
        NginxChangeRequest enabled = service.create(form, null, null, 10L);
        org.assertj.core.api.Assertions.assertThat(enabled.isApiProxyEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(enabled.getApiPrefix()).isEqualTo("/api/");
        org.assertj.core.api.Assertions.assertThat(enabled.getApiUpstreamHost()).isEqualTo("198.18.12.40");
        org.assertj.core.api.Assertions.assertThat(enabled.getApiUpstreamPort()).isEqualTo(9090);
        form.setApiProxyEnabled(false);
        NginxChangeRequest disabled = service.create(form, null, null, 10L);
        org.assertj.core.api.Assertions.assertThat(disabled.isApiProxyEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(disabled.getApiPrefix()).isNull();
        org.assertj.core.api.Assertions.assertThat(disabled.getApiUpstreamHost()).isNull();
        org.assertj.core.api.Assertions.assertThat(disabled.getApiUpstreamPort()).isNull();
    }

    @Test
    void staticApiRejectsInjectionRootAndMissingTargetBeforeSaving() {
        when(serverRepository.findById(1L)).thenReturn(Optional.of(webServer()));
        for (String prefix : List.of("/", "/api", "/api/../", "/api/;return 200;", "/api/$uri/", "/api//", "/api/?q/")) {
            NginxPolicyForm form = apiForm(); form.setApiPrefix(prefix);
            assertThatThrownBy(() -> service.create(form, null, null, 10L)).isInstanceOf(ResponseStatusException.class);
        }
        NginxPolicyForm missing = apiForm(); missing.setApiUpstreamPort(null);
        assertThatThrownBy(() -> service.create(missing, null, null, 10L)).hasMessageContaining("API upstream 포트");
        missing.setApiUpstreamPort(9090); missing.setApiUpstreamHost("198.18.8.1;return 200");
        assertThatThrownBy(() -> service.create(missing, null, null, 10L)).hasMessageContaining("API upstream 호스트");
        NginxPolicyForm reverse = apiForm(); reverse.setPolicyType("REVERSE_PROXY");
        assertThatThrownBy(() -> service.create(reverse, null, null, 10L)).hasMessageContaining("정적 SPA");
        verify(repository, never()).insert(any());
    }

    @Test
    void approvedStaticApiUsesFrozenRouteAndRollbackRequestRetainsSnapshot() {
        NginxChangeRequest pending = request(10L);
        pending.setPolicyType(NginxPolicyType.STATIC_FILES); pending.setWebRoot("/srv/web/portal");
        pending.setApiProxyEnabled(true); pending.setApiPrefix("/backend/api/");
        pending.setApiUpstreamHost("198.18.8.11"); pending.setApiUpstreamPort(9090);
        when(repository.findById(7L)).thenReturn(Optional.of(pending));
        when(repository.acquireApproval(7L, 20L)).thenReturn(1);
        LocalDateTime now = LocalDateTime.now();
        when(sshCommandService.execute(eq(pending.getServer()), any())).thenReturn(new RemoteCommandResult(
            0, "BACKUP_PATH=/var/backups/deploy-system/nginx/snapshot\nBACKUP_SNAPSHOT_SHA=" + NginxRollbackSnapshotTest.A + "\n" + NginxRollbackSnapshotTest.proof("CURRENT_", NginxRollbackSnapshotTest.A, NginxRollbackSnapshotTest.C, "7"), "", "ssh", now, now, 10));
        service.approveAndApply(7L, 20L);
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(sshCommandService).execute(eq(pending.getServer()), args.capture());
        org.assertj.core.api.Assertions.assertThat(args.getValue()).containsSubsequence("--api-proxy", "true",
            "--api-prefix", "/backend/api/", "--api-upstream-host", "198.18.8.11", "--api-upstream-port", "9090");
        pending.setBackupSnapshotHash(NginxRollbackSnapshotTest.A); pending.setAppliedSnapshotHash(NginxRollbackSnapshotTest.A);pending.setAppliedConfigHash(NginxRollbackSnapshotTest.C);pending.setNativeRequestId(7L);
        when(sshCommandService.execute(any(),any())).thenReturn(NginxRollbackSnapshotTest.result(NginxRollbackSnapshotTest.proof("TARGET_",NginxRollbackSnapshotTest.A,NginxRollbackSnapshotTest.C,"7")));
        pending.setStatus(NginxChangeStatus.APPLIED); pending.setBackupPath("/var/backups/deploy-system/nginx/snapshot");
        when(repository.findLatestCompletedId(1L, "office-main")).thenReturn(7L);
        NginxChangeRequest rollback = service.requestRollback(7L, 30L);
        org.assertj.core.api.Assertions.assertThat(rollback.isApiProxyEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(rollback.getApiPrefix()).isEqualTo("/backend/api/");
        org.assertj.core.api.Assertions.assertThat(rollback.getApiUpstreamHost()).isEqualTo("198.18.8.11");
        org.assertj.core.api.Assertions.assertThat(rollback.getApiUpstreamPort()).isEqualTo(9090);
    }

    private NginxPolicyForm apiForm() {
        NginxPolicyForm form = form(); form.setPolicyType("STATIC_SPA"); form.setWebRoot("/var/www/portal");
        form.setApiProxyEnabled(true); form.setApiPrefix(" /api/ ");
        form.setApiUpstreamHost(" 198.18.12.40 "); form.setApiUpstreamPort(9090);
        return form;
    }

    private NginxPolicyForm form() {
        NginxPolicyForm form = new NginxPolicyForm();
        form.setServerId(1L);
        form.setPolicyName("office-main");
        form.setServerName("office.company.local");
        form.setListenPort(443);
        form.setUpstreamHost("198.18.12.40");
        form.setUpstreamPort(8080);
        form.setTlsEnabled(true);
        form.setTls12(true);
        form.setTls13(true);
        return form;
    }

    private Server webServer() {
        return Server.builder().id(1L).name("web01").ipAddress("198.18.8.10")
            .serverType(ServerType.WEB).controlMode(ServerControlMode.AGENT).agentBindingId("web-registration-1").build();
    }

    private NginxChangeRequest request(Long requesterId) {
        return NginxChangeRequest.builder()
            .requestedControlMode(ServerControlMode.AGENT).requestedAgentBindingId("web-registration-1")
            .id(7L).server(webServer()).requestedBy(User.builder().id(requesterId).name("요청자").build())
            .status(NginxChangeStatus.PENDING).policyName("office-main").serverName("office.company.local")
            .listenPort(443).upstreamHost("198.18.12.40").upstreamPort(8080)
            .websocketEnabled(true).tlsEnabled(true).tlsProtocols("TLSv1.2 TLSv1.3")
            .http2Enabled(true).redirectHttp(true).hstsEnabled(true).clientMaxBodyMb(50)
            .proxyReadTimeoutSec(300).proxySendTimeoutSec(300).proxyBuffering(true).securityHeaders(true)
            .certificateName("main").createdAt(LocalDateTime.now()).build();
    }
}
