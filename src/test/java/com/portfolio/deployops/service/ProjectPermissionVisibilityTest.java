package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectPermissionVisibilityTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectUserPermissionRepository grants = mock(ProjectUserPermissionRepository.class);
    private final ProjectPermissionService permissions = new ProjectPermissionService(grants);
    private final ProjectService service = new ProjectService(projects, mock(ServerGroupRepository.class), mock(ServerRepository.class),
        mock(ApprovalPolicyRepository.class), mock(ApprovalStepRepository.class), mock(ProjectImageHistoryService.class), permissions,
        mock(DeployComponentRepository.class));
    private final Project project = Project.builder().id(10L).name("프로젝트").active(true).build();

    @Test
    void projectManagerSeesProjectsWithoutAnyDirectGrantButCannotRequestByRole() {
        var pm = principal(UserRole.PROJECT_MANAGER);
        when(projects.findByActiveTrue()).thenReturn(List.of(project)); when(projects.findAll()).thenReturn(List.of(project));
        assertThat(service.findVisibleActive(pm)).containsExactly(project);
        assertThat(service.findVisibleAll(pm)).containsExactly(project);
        assertThat(service.findDeployRequestable(pm)).isEmpty();
    }

    @Test
    void testOnlyRequestGrantAppearsAndDoesNotAllowProd() {
        var developer = principal(UserRole.DEVELOPER);
        when(grants.findProjectIdsByUserAndPermission(2L, ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.TEST)).thenReturn(List.of(10L));
        when(projects.findActiveByIds(List.of(10L))).thenReturn(List.of(project));
        when(grants.existsActivePermission(2L, 10L, ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.TEST)).thenReturn(true);
        assertThat(service.findDeployRequestable(developer)).containsExactly(project);
        assertThat(service.requestEnvironments(List.of(project), developer)).containsEntry(10L, List.of("TEST"));
        assertThat(permissions.hasPermission(developer, 10L, ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.ALL)).isFalse();
    }

    @Test
    void testAndProdProjectListsAreDeduplicated() {
        var developer = principal(UserRole.DEVELOPER);
        when(grants.findProjectIdsByUserAndPermission(2L, ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.TEST)).thenReturn(List.of(10L));
        when(grants.findProjectIdsByUserAndPermission(2L, ProjectPermissionType.DEPLOY_REQUEST, ProjectPermissionEnvironment.PROD)).thenReturn(List.of(10L, 20L));
        service.findDeployRequestable(developer);
        verify(projects).findActiveByIds(List.of(10L, 20L));
    }

    @Test
    void superAdminCanRequestBothEnvironmentsWithoutDirectRows() {
        assertThat(service.requestEnvironments(List.of(project), principal(UserRole.SUPER_ADMIN))).containsEntry(10L, List.of("TEST", "PROD"));
        verifyNoInteractions(grants);
    }

    private SecurityUserDetails principal(UserRole role) { return new SecurityUserDetails(User.builder().id(2L).role(role).active(true).build()); }
}
