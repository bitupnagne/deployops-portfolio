package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionEnvironment;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.RemoteCommandLog;
import com.portfolio.deployops.domain.Server;
import com.portfolio.deployops.domain.ServerGroup;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.ApprovalPolicyRepository;
import com.portfolio.deployops.repository.ApprovalStepRepository;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.DeployComponentRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.repository.ProjectUserPermissionRepository;
import com.portfolio.deployops.repository.RemoteCommandLogRepository;
import com.portfolio.deployops.repository.ServerGroupRepository;
import com.portfolio.deployops.repository.ServerRepository;
import com.portfolio.deployops.service.ArchitectureService;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.DeployLogService;
import com.portfolio.deployops.service.DeployRequestService;
import com.portfolio.deployops.service.DeploymentDutySeparationService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.ManualServerExecutionService;
import com.portfolio.deployops.service.OperatorExecutionService;
import com.portfolio.deployops.service.ProjectImageHistoryService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.RemoteDeployService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OperatorServerLogApiTest {

    private final ProjectUserPermissionRepository permissions = mock(ProjectUserPermissionRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ServerRepository servers = mock(ServerRepository.class);
    private final RemoteCommandLogRepository logs = mock(RemoteCommandLogRepository.class);
    private final ProjectPermissionService permissionService = new ProjectPermissionService(permissions);
    private final ProjectService projectService = new ProjectService(
        projects, mock(ServerGroupRepository.class), servers, mock(ApprovalPolicyRepository.class),
        mock(ApprovalStepRepository.class), mock(ProjectImageHistoryService.class), permissionService,
        mock(DeployComponentRepository.class));
    private final OperatorApiController controller = new OperatorApiController(
        mock(DeployRequestService.class), mock(AuditLogService.class), mock(DeployLogService.class),
        mock(OperatorExecutionService.class), mock(RemoteDeployService.class), permissionService, projectService,
        logs, mock(DeployRequestRepository.class), mock(ArchitectureService.class),
        mock(DeploymentDutySeparationService.class), mock(ExecutionPolicyService.class),
        mock(ManualServerExecutionService.class));

    @Test
    void sharedServerLogLookupAlwaysIncludesTheSelectedProject() {
        mapServer(10L, 100L);
        mapServer(20L, 100L);
        grantView(10L);
        grantView(20L);
        when(logs.findRecentByProjectIdAndServerId(10L, 100L, 20)).thenReturn(List.of(log(101L, "project-a")));
        when(logs.findRecentByProjectIdAndServerId(20L, 100L, 20)).thenReturn(List.of(log(201L, "project-b")));

        List<?> first = (List<?>) controller.serverLogs(100L, 10L, 20, user(UserRole.DEVELOPER)).getData();
        List<?> second = (List<?>) controller.serverLogs(100L, 20L, 20, user(UserRole.DEVELOPER)).getData();

        assertThat(first).singleElement().satisfies(row -> assertThat(((Map<?, ?>) row).get("stdout"))
            .isEqualTo("project-a"));
        assertThat(second).singleElement().satisfies(row -> assertThat(((Map<?, ?>) row).get("stdout"))
            .isEqualTo("project-b"));
        verify(logs).findRecentByProjectIdAndServerId(10L, 100L, 20);
        verify(logs).findRecentByProjectIdAndServerId(20L, 100L, 20);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"DEVELOPER", "OPERATOR", "APPROVER"})
    void globalRoleDoesNotReplaceProjectViewPermission(UserRole role) {
        assertThatThrownBy(() -> controller.serverLogs(100L, 20L, 20, user(role)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(projects, servers, logs);
    }

    @Test
    void permissionOnAnotherProjectCannotBeUsedToReadTheSharedServer() {
        grantView(10L);
        assertThatThrownBy(() -> controller.serverLogs(100L, 20L, 20, user(UserRole.DEVELOPER)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(projects, servers, logs);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"DEVELOPER", "SUPER_ADMIN"})
    void unmappedServerIsRejectedEvenForSuperAdmin(UserRole role) {
        mapServer(10L, 100L);
        grantView(10L);
        assertThatThrownBy(() -> controller.serverLogs(999L, 10L, 20, user(role)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(logs);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"SUPER_ADMIN", "PROJECT_MANAGER"})
    void implicitProjectViewStillUsesProjectScopedLogs(UserRole role) {
        mapServer(10L, 100L);
        controller.serverLogs(100L, 10L, 20, user(role));
        verifyNoInteractions(permissions);
        verify(logs).findRecentByProjectIdAndServerId(10L, 100L, 20);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 20, 50000})
    void scopedLogLookupKeepsTheExistingLimitBounds(int requested) {
        mapServer(10L, 100L);
        controller.serverLogs(100L, 10L, requested, user(UserRole.SUPER_ADMIN));
        verify(logs).findRecentByProjectIdAndServerId(10L, 100L, Math.min(Math.max(requested, 1), 50));
    }

    @Test
    void myBatisQueryBindsProjectAndServerBeforeApplyingTheLimit() {
        Configuration configuration = new Configuration();
        configuration.addMapper(RemoteCommandLogRepository.class);
        BoundSql query = configuration.getMappedStatement(RemoteCommandLogRepository.class.getName()
            + ".findRecentByProjectIdAndServerId").getBoundSql(Map.of("projectId", 10L, "serverId", 100L, "limit", 20));

        // The actual mapped query must constrain both dimensions; filtering after LIMIT would lose valid project logs.
        assertThat(query.getSql().replaceAll("\\s+", " ").trim())
            .contains("JOIN deploy_request r ON r.id = l.deploy_request_id")
            .contains("WHERE r.project_id = ? AND l.server_id = ? ORDER BY l.id DESC LIMIT ?");
        assertThat(query.getParameterMappings()).extracting(mapping -> mapping.getProperty())
            .containsExactly("projectId", "serverId", "limit");
    }

    private void mapServer(Long projectId, Long serverId) {
        when(projects.findByIdWithDetails(projectId)).thenReturn(Optional.of(Project.builder().id(projectId).build()));
        when(projects.findServerGroupsByProjectId(projectId))
            .thenReturn(List.of(ServerGroup.builder().id(projectId).build()));
        when(servers.findByServerGroupId(projectId)).thenReturn(List.of(Server.builder().id(serverId).build()));
    }

    private void grantView(Long projectId) {
        when(permissions.existsActivePermission(7L, projectId, ProjectPermissionType.PROJECT_VIEW,
            ProjectPermissionEnvironment.ALL)).thenReturn(true);
    }

    private SecurityUserDetails user(UserRole role) {
        return new SecurityUserDetails(User.builder().id(7L).loginId("review-user").name("검증 사용자")
            .password("{noop}not-used").role(role).active(true).build());
    }

    private RemoteCommandLog log(Long requestId, String output) {
        return RemoteCommandLog.builder().id(requestId).deployRequestId(requestId).serverId(100L)
            .stdoutText(output).success(true).build();
    }
}
