package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.dto.ProjectGitlabConnectionForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.ProjectGitlabConnectionService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectReadinessService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.RemoteCommandLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectGitlabConnectionApiTest {

    private final ProjectPermissionService permissions = mock(ProjectPermissionService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectGitlabConnectionService gitlabConnection = mock(ProjectGitlabConnectionService.class);
    private final ProjectApiController controller = new ProjectApiController(
        mock(RemoteCommandLogService.class), permissions, projects, mock(AuditLogService.class),
        mock(ProjectReadinessService.class), mock(GitlabService.class), gitlabConnection);
    private final SecurityUserDetails admin = new SecurityUserDetails(User.builder()
        .id(1L).role(UserRole.SUPER_ADMIN).active(true).build());

    @Test
    void adminTestsEnteredIdWithServerSideCredentialService() {
        when(projects.findById(3L)).thenReturn(Project.builder()
            .id(3L).gitlabProjectId(5L).gitlabRepoUrl("https://gitlab/root/old.git").build());
        when(gitlabConnection.test(7L, "https://gitlab/root/current.git"))
            .thenReturn(new ProjectGitlabConnectionService.Result(7L, "root/current", "main", true));

        var response = controller.testGitlabProject(3L,
            new ProjectGitlabConnectionForm(7L, "https://gitlab/root/current.git"), admin);

        assertThat(response.isSuccess()).isTrue();
        verify(permissions).assertPermission(admin, 3L, ProjectPermissionType.PROJECT_VIEW);
        verify(gitlabConnection).test(7L, "https://gitlab/root/current.git");
    }

    @Test
    void usesSavedProjectWhenRequestHasNoOverride() {
        when(projects.findById(3L)).thenReturn(Project.builder()
            .id(3L).gitlabProjectId(5L).gitlabRepoUrl("https://gitlab/root/saved.git").build());
        when(gitlabConnection.test(5L, "https://gitlab/root/saved.git"))
            .thenReturn(new ProjectGitlabConnectionService.Result(5L, "root/saved", "main", true));

        controller.testGitlabProject(3L, null, admin);

        verify(gitlabConnection).test(5L, "https://gitlab/root/saved.git");
    }

    @Test
    void responseDescribesTheVerifiedScopeWithoutClaimingCloneConnectivity() {
        when(projects.findById(3L)).thenReturn(Project.builder()
            .id(3L).gitlabProjectId(5L).gitlabRepoUrl("https://gitlab/root/saved.git").build());
        when(gitlabConnection.test(5L, "https://gitlab/root/saved.git"))
            .thenReturn(new ProjectGitlabConnectionService.Result(5L, "root/saved", "main", true));

        var response = controller.testGitlabProject(3L,
            new ProjectGitlabConnectionForm(5L, "https://gitlab/root/saved.git"), admin);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).contains("clone 통신은 검사하지 않았습니다");
        verify(gitlabConnection).test(5L, "https://gitlab/root/saved.git");
    }

    @Test
    void rejectsCallerWithoutProjectViewBeforeLoadingProjectOrContactingGitlab() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(permissions).assertPermission(admin, 3L, ProjectPermissionType.PROJECT_VIEW);

        assertThatThrownBy(() -> controller.testGitlabProject(3L,
            new ProjectGitlabConnectionForm(7L, null), admin))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(projects, gitlabConnection);
    }

    @Test
    void blankEnteredIdDoesNotSilentlyTestPreviouslySavedId() {
        when(projects.findById(3L)).thenReturn(Project.builder().id(3L).gitlabProjectId(5L).build());

        var response = controller.testGitlabProject(3L,
            new ProjectGitlabConnectionForm(null, ""), admin);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NO_GITLAB_ID");
        verifyNoInteractions(gitlabConnection);
    }
}
