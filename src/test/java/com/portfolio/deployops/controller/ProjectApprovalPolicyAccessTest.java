package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.dto.ProjectForm;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectGitlabConnectionService;
import com.portfolio.deployops.service.ProjectReadinessService;
import com.portfolio.deployops.service.ProjectService;
import com.portfolio.deployops.service.RemoteCommandLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectApprovalPolicyAccessTest {

    @Test
    void projectManagerCannotChooseApprovalPolicyWhileCreatingProject() {
        ProjectService projectService = mock(ProjectService.class);
        ProjectApiController controller = new ProjectApiController(
            mock(RemoteCommandLogService.class),
            mock(ProjectPermissionService.class),
            projectService,
            mock(AuditLogService.class),
            mock(ProjectReadinessService.class),
            mock(GitlabService.class),
            mock(ProjectGitlabConnectionService.class));
        ProjectForm form = new ProjectForm();
        form.setName("new-project");
        form.setApprovalPolicyId(7L);

        assertThatThrownBy(() -> controller.create(form, mock(SecurityUserDetails.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(projectService, never()).create(form);
    }

    @Test
    void projectManagerCannotReplaceAnExistingProjectsApprovalPolicy() {
        ProjectService projectService = mock(ProjectService.class);
        ProjectApiController controller = new ProjectApiController(
            mock(RemoteCommandLogService.class),
            mock(ProjectPermissionService.class),
            projectService,
            mock(AuditLogService.class),
            mock(ProjectReadinessService.class),
            mock(GitlabService.class),
            mock(ProjectGitlabConnectionService.class));
        ProjectForm form = new ProjectForm();
        form.setName("demo-app-relay");
        form.setApprovalPolicyId(7L);

        assertThatThrownBy(() -> controller.update(4L, form, mock(SecurityUserDetails.class)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(projectService, never()).update(4L, form);
    }
}
