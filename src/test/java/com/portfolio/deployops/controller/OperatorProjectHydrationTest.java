package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.ArtifactType;
import com.portfolio.deployops.domain.DeployComponent;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerEnvironment;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.ServerType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.*;
import com.portfolio.deployops.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Exercises the real repository default method -> ProjectService -> operator/remote services.
 * Only database and SSH boundaries are mocked: projects start without hydrated components.
 */
class OperatorProjectHydrationTest {

    private final ProjectRepository projects = mock(ProjectRepository.class, CALLS_REAL_METHODS);
    private final DeployComponentRepository components = mock(DeployComponentRepository.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final ProjectPermissionService permissions = new ProjectPermissionService(mock(ProjectUserPermissionRepository.class));
    private final ProjectService projectService = new ProjectService(projects, mock(ServerGroupRepository.class), servers,
        mock(ApprovalPolicyRepository.class), mock(ApprovalStepRepository.class), mock(ProjectImageHistoryService.class),
        permissions, components);
    private final SshCommandService ssh = mock(SshCommandService.class);
    private final RemoteDeployService remote = new RemoteDeployService(ssh, mock(RemoteCommandLogService.class),
        mock(SystemSettingService.class), mock(ImageArtifactService.class));
    private final OperatorApiController controller = new OperatorApiController(
        mock(DeployRequestService.class), mock(AuditLogService.class), mock(DeployLogService.class),
        mock(OperatorExecutionService.class), remote, permissions, projectService,
        mock(RemoteCommandLogRepository.class), mock(DeployRequestRepository.class), mock(ArchitectureService.class),
        mock(DeploymentDutySeparationService.class), mock(ExecutionPolicyService.class), mock(ManualServerExecutionService.class));
    private final SecurityUserDetails admin = new SecurityUserDetails(User.builder().id(1L).role(UserRole.SUPER_ADMIN).active(true).build());
    private final List<Server> webServers = List.of(web(21L, "devweb", ServerEnvironment.TEST),
        web(22L, "prdweb1", ServerEnvironment.PROD), web(23L, "prdweb2", ServerEnvironment.PROD));

    @BeforeEach
    void databaseRowsAreLoadedSeparately() {
        // Fresh raw row on every read: no pre-populated component can hide missing hydration.
        when(projects.findById(7L)).thenAnswer(invocation -> Optional.of(Project.builder().id(7L).name("lab-static").build()));
        when(projects.findServerGroupsByProjectId(7L)).thenAnswer(invocation ->
            List.of(ServerGroup.builder().id(4L).name("web").build()));
        when(servers.findByServerGroupId(4L)).thenReturn(webServers);
        when(components.findByProjectId(7L)).thenReturn(List.of(
            DeployComponent.builder().id(30L).projectId(7L).role(ServerType.WEB).artifactType(ArtifactType.CONTAINER).active(false).build(),
            DeployComponent.builder().id(31L).projectId(7L).role(ServerType.WEB).artifactType(ArtifactType.STATIC_BUNDLE)
                .webRoot("/var/www/lab-static").active(true).build()));
    }

    @Test
    void statusHydratesComponentRowsBeforeClassifyingEveryWebServer() {
        when(ssh.execute(any(), any())).thenReturn(result("""
            ARTIFACT_TYPE=STATIC_BUNDLE
            RELEASE_EXISTS=true
            CURRENT_RELEASE=/var/www/lab-static/releases/r1
            NGINX_ACTIVE=true
            """));

        List<?> rows = (List<?>) controller.serviceStatus(7L, admin).getData();

        assertThat(rows).hasSize(3);
        for (Object value : rows) {
            Map<?, ?> row = (Map<?, ?>) value;
            assertThat(row.get("artifactType")).isEqualTo("STATIC_BUNDLE");
            assertThat(row.get("controlSupported")).isEqualTo(false);
            assertThat(row.get("logsSupported")).isEqualTo(false);
            assertThat(row.get("statusKnown")).isEqualTo(true);
            assertThat(row.get("serviceTarget")).isEqualTo("/var/www/lab-static");
        }
        verify(projects).findById(7L);
        verify(components).findByProjectId(7L);
        for (Server server : webServers) {
            verify(ssh).execute(server, List.of("sudo", "-n", "/usr/local/deploy/bin/deploy-web-static.sh",
                "status", "--web-root", "/var/www/lab-static"));
        }
    }

    @Test
    void directControlReloadsStaticComponentsAndNeverIssuesDockerOrNginxControl() {
        for (String action : List.of("start", "stop", "restart")) {
            assertThat(controller.controlService(7L, 22L, action, admin, mock(HttpServletRequest.class)).getErrorCode())
                .isEqualTo("SERVICE_CONTROL_UNSUPPORTED");
        }
        verifyNoInteractions(ssh);
    }

    @Test
    void containerLogReloadsStaticComponentsAndReturnsUnsupportedWithoutSsh() {
        Map<?, ?> body = (Map<?, ?>) controller.containerLog(21L, 7L, 300, admin).getData();
        assertThat(body.get("artifactType")).isEqualTo("STATIC_BUNDLE");
        assertThat(body.get("supported")).isEqualTo(false);
        verify(components).findByProjectId(7L);
        verifyNoInteractions(ssh);
    }

    @Test
    void topologyDoesNotAdvertiseAContainerForAnEntirelyStaticProject() {
        Map<?, ?> body = (Map<?, ?>) controller.topology(7L, admin).getData();
        assertThat(body.get("container")).isNull();
        verify(components).findByProjectId(7L);
        verifyNoInteractions(ssh);
    }

    @Test
    void projectsWithoutComponentRowsRetainLegacyContainerStatus() {
        when(components.findByProjectId(7L)).thenReturn(List.of());
        when(ssh.execute(any(), any())).thenReturn(result("NAMES STATUS\nlab-static Up 3 minutes\n"));
        List<?> rows = (List<?>) controller.serviceStatus(7L, admin).getData();
        assertThat(rows).hasSize(3);
        for (Object value : rows) {
            Map<?, ?> row = (Map<?, ?>) value;
            assertThat(row.get("artifactType")).isEqualTo("CONTAINER");
            assertThat(row.get("controlSupported")).isEqualTo(true);
        }
    }

    @Test
    void unauthorizedViewerCannotTriggerProjectOrComponentHydration() {
        SecurityUserDetails developer = new SecurityUserDetails(User.builder().id(9L).role(UserRole.DEVELOPER).active(true).build());
        assertThatThrownBy(() -> controller.serviceStatus(7L, developer))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(projects, components, servers, ssh);
    }

    private static Server web(Long id, String name, ServerEnvironment environment) {
        return Server.builder().id(id).name(name).serverType(ServerType.WEB).environment(environment).deployAccount("deploy").build();
    }

    private static RemoteCommandResult result(String stdout) {
        return new RemoteCommandResult(0, stdout, "", "fixture", LocalDateTime.now(), LocalDateTime.now(), 1L);
    }
}
