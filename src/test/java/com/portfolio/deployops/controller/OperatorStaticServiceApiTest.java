package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.HealthCheckMode;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperatorStaticServiceApiTest {

    private final SshCommandService ssh = mock(SshCommandService.class);
    private final RemoteDeployService remote = new RemoteDeployService(ssh, mock(RemoteCommandLogService.class),
        mock(SystemSettingService.class), mock(ImageArtifactService.class));
    private final ProjectService projects = mock(ProjectService.class);
    private final SecurityUserDetails user = mock(SecurityUserDetails.class);
    private final OperatorApiController controller = new OperatorApiController(
        mock(DeployRequestService.class), mock(AuditLogService.class), mock(DeployLogService.class),
        mock(OperatorExecutionService.class), remote, mock(ProjectPermissionService.class), projects,
        mock(RemoteCommandLogRepository.class), mock(DeployRequestRepository.class), mock(ArchitectureService.class),
        mock(DeploymentDutySeparationService.class), mock(ExecutionPolicyService.class), mock(ManualServerExecutionService.class));
    private final Server server = Server.builder().id(21L).name("devweb").serverType(ServerType.WEB)
        .environment(ServerEnvironment.TEST).deployAccount("deploy").build();
    private final DeployComponent component = DeployComponent.builder().id(1L).role(ServerType.WEB)
        .artifactType(ArtifactType.STATIC_BUNDLE).webRoot("/var/www/payments").active(true).build();
    private final Project project = Project.builder().id(7L).name("payments")
        .components(List.of(component)).serverGroups(List.of(ServerGroup.builder().servers(List.of(server)).build())).build();

    @BeforeEach
    void setUp() {
        when(projects.findByIdWithDetails(7L, user)).thenReturn(project);
    }

    @Test
    void staticStatusUsesOnlyTheStaticAgentAndIdentifiesTheCurrentRelease() {
        when(ssh.execute(any(), any())).thenReturn(result(0, """
            ARTIFACT_TYPE=STATIC_BUNDLE
            RELEASE_EXISTS=true
            CURRENT_RELEASE=/var/www/payments/releases/20260921
            NGINX_ACTIVE=true
            """));

        Map<String, Object> row = statusRow();

        assertThat(row).containsEntry("artifactType", "STATIC_BUNDLE")
            .containsEntry("serviceTarget", "/var/www/payments").containsEntry("controlSupported", false)
            .containsEntry("logsSupported", false).containsEntry("statusKnown", true)
            .containsEntry("exists", true).containsEntry("statusLabel", "릴리스 확인")
            .containsEntry("currentRelease", "/var/www/payments/releases/20260921");
        assertThat(row.get("detail").toString()).contains("실제 HTTP 응답은 별도 확인");
        verify(ssh).execute(server, List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-web-static.sh",
            "status", "--web-root", "/var/www/payments"));
    }

    @Test
    void staticControlsAreRejectedAtBothApiAndServiceBeforeSsh() {
        for (String action : List.of("start", "stop", "restart")) {
            var response = controller.controlService(7L, 21L, action, user, mock(HttpServletRequest.class));
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("SERVICE_CONTROL_UNSUPPORTED");
            assertThatThrownBy(() -> remote.controlService(project, server, action))
                .isInstanceOf(RemoteExecutionException.class).hasMessageContaining("기동·정지·재기동 대상");
        }
        verifyNoInteractions(ssh);
    }

    @Test
    void staticLogsReturnAnExplicitUnsupportedCapabilityWithoutDockerOrSharedNginxLogs() {
        Map<?, ?> body = (Map<?, ?>) controller.containerLog(21L, 7L, 300, user).getData();
        assertThat(body.get("supported")).isEqualTo(false);
        assertThat(body.get("text").toString()).contains("컨테이너 로그가 없습니다");
        assertThatThrownBy(() -> remote.containerLogs(project, server, 300)).isInstanceOf(RemoteExecutionException.class);
        verifyNoInteractions(ssh);
    }

    @Test
    void containerLogFailureDoesNotLetLoginShellLocaleWarningsHideTheAgentError() {
        component.setArtifactType(ArtifactType.CONTAINER);
        when(ssh.execute(any(), any())).thenReturn(new RemoteCommandResult(2, "", """
            bash: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)
            /bin/sh: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)
            /usr/bin/bash: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)
            -bash: warning: setlocale: LC_ALL: cannot change locale (en_US.UTF-8)
            컨테이너가 없습니다: uiqa-rocky-was-smoke
            """, "test", LocalDateTime.now(), LocalDateTime.now(), 1L));

        Map<?, ?> body = (Map<?, ?>) controller.containerLog(21L, 7L, 300, user).getData();

        assertThat(body.get("supported")).isEqualTo(true);
        assertThat(body.get("ok")).isEqualTo(false);
        assertThat((String) body.get("text")).contains("컨테이너가 없습니다")
            .doesNotContain("LC_ALL");
    }

    @Test
    void missingStaticReleaseIsSeparateFromNginxLiveness() {
        when(ssh.execute(any(), any())).thenReturn(result(0,
            "ARTIFACT_TYPE=STATIC_BUNDLE\nRELEASE_EXISTS=false\nCURRENT_RELEASE=\nNGINX_ACTIVE=true\n"));
        assertThat(statusRow()).containsEntry("exists", false).containsEntry("running", false)
            .containsEntry("statusKnown", true).containsEntry("statusLabel", "정적 릴리스 없음");
    }

    @Test
    void existingReleaseWithStoppedNginxDoesNotClaimTheServiceIsRunning() {
        when(ssh.execute(any(), any())).thenReturn(result(0,
            "ARTIFACT_TYPE=STATIC_BUNDLE\nRELEASE_EXISTS=true\nCURRENT_RELEASE=/var/www/payments/releases/r1\nNGINX_ACTIVE=false\n"));
        assertThat(statusRow()).containsEntry("exists", true).containsEntry("running", false)
            .containsEntry("statusLabel", "릴리스 있음 · Nginx 정지");
    }

    @Test
    void agentFailureAndOldResponseAreUnknownInsteadOfNotDeployed() {
        when(ssh.execute(any(), any())).thenReturn(result(1, "Unsupported action: status"));
        assertThat(statusRow()).containsEntry("statusKnown", false).containsEntry("exists", null)
            .containsEntry("statusLabel", "확인 불가");
        when(ssh.execute(any(), any())).thenReturn(result(0, "some legacy response"));
        assertThat(statusRow()).containsEntry("statusKnown", false).containsEntry("exists", null);
    }

    @Test
    void proxyReloadDoesNotRunContainerControlsOrLogs() {
        component.setArtifactType(ArtifactType.PROXY_RELOAD);
        assertThat(controller.controlService(7L, 21L, "restart", user, mock(HttpServletRequest.class)).getErrorCode())
            .isEqualTo("SERVICE_CONTROL_UNSUPPORTED");
        Map<?, ?> body = (Map<?, ?>) controller.containerLog(21L, 7L, 20, user).getData();
        assertThat(body.get("supported")).isEqualTo(false);
        verifyNoInteractions(ssh);
    }

    @Test
    void proxyStatusUsesConfiguredLoopbackHealthAndKeepsControlsUnsupported() {
        proxyComponent();
        when(ssh.execute(any(), any())).thenReturn(result(0,
            "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=true\nHTTP_HEALTHY=true\n"));
        assertThat(statusRow()).containsEntry("statusKnown", true).containsEntry("running", true)
            .containsEntry("httpHealthy", true).containsEntry("nginxRunning", true)
            .containsEntry("controlSupported", false).containsEntry("logsSupported", false)
            .containsEntry("statusLabel", "Nginx 실행 · HTTP 정상");
        verify(ssh).execute(server, List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-web-proxy.sh",
            "status", "--health-port", "8081", "--health-path", "/actuator/health", "--health-host", "localhost"));
    }

    @Test
    void proxyHttpFailureIsKnownAndDifferentFromStoppedNginx() {
        proxyComponent();
        when(ssh.execute(any(), any())).thenReturn(result(0,
            "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=true\nHTTP_HEALTHY=false\n"));
        assertThat(statusRow()).containsEntry("statusKnown", true).containsEntry("running", false)
            .containsEntry("statusLabel", "Nginx 실행 · HTTP 실패");
        when(ssh.execute(any(), any())).thenReturn(result(0,
            "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=false\nHTTP_HEALTHY=false\n"));
        assertThat(statusRow()).containsEntry("statusKnown", true).containsEntry("running", false)
            .containsEntry("statusLabel", "Nginx 정지");
    }

    @Test
    void malformedProxyStatusIsUnknownRatherThanHealthy() {
        proxyComponent();
        for (String output : List.of("old response", "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=true\n",
            "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=true\nHTTP_HEALTHY=yes\n",
            "ARTIFACT_TYPE=PROXY_RELOAD\nNGINX_ACTIVE=true\nHTTP_HEALTHY=true\nHTTP_HEALTHY=false\n")) {
            when(ssh.execute(any(), any())).thenReturn(result(0, output));
            assertThat(statusRow()).containsEntry("statusKnown", false).containsEntry("running", null);
        }
    }

    @Test
    void proxyStartStopRestartRemainRejectedBeforeSsh() {
        proxyComponent();
        for (String action : List.of("start", "stop", "restart")) {
            assertThat(controller.controlService(7L, 21L, action, user, mock(HttpServletRequest.class)).getErrorCode())
                .isEqualTo("SERVICE_CONTROL_UNSUPPORTED");
            assertThatThrownBy(() -> remote.controlService(project, server, action)).isInstanceOf(RemoteExecutionException.class);
        }
        verifyNoInteractions(ssh);
    }

    private void proxyComponent() {
        component.setArtifactType(ArtifactType.PROXY_RELOAD);
        component.setHealthCheckMode(HealthCheckMode.SSH_LOCAL_CURL);
        component.setHealthCheckPort(8081);
        component.setHealthCheckPath("/actuator/health");
        component.setProxyHealthHost("localhost");
    }

    @Test
    void legacyContainerStatusRetainsItsAgentAndControlCapability() {
        project.setComponents(List.of());
        when(ssh.execute(any(), any())).thenReturn(result(0, "NAMES STATUS\npayments Up 2 hours\n"));
        assertThat(statusRow()).containsEntry("artifactType", "CONTAINER").containsEntry("container", "payments")
            .containsEntry("running", true).containsEntry("controlSupported", true);
        verify(ssh).execute(server, List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-web-app.sh",
            "status", "--container", "payments", "--managed-project", "7"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statusRow() {
        return ((List<Map<String, Object>>) controller.serviceStatus(7L, user).getData()).getFirst();
    }

    private RemoteCommandResult result(int code, String output) {
        return new RemoteCommandResult(code, output, "", "test", LocalDateTime.now(), LocalDateTime.now(), 1L);
    }
}
