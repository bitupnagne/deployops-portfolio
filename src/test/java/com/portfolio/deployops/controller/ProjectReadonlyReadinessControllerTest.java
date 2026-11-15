package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.ProjectReadiness;
import com.portfolio.deployops.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectReadonlyReadinessControllerTest {
    @Mock RemoteCommandLogService remoteCommandLogService;
    @Mock ProjectPermissionService projectPermissionService;
    @Mock ProjectService projectService;
    @Mock AuditLogService auditLogService;
    @Mock ProjectReadinessService projectReadinessService;
    @Mock GitlabService gitlabService;
    @Mock ProjectGitlabConnectionService projectGitlabConnectionService;
    @InjectMocks ProjectApiController controller;
    private final SecurityUserDetails actor=new SecurityUserDetails(User.builder()
        .id(4L).loginId("operator").role(UserRole.OPERATOR).build());
    private final Project project=Project.builder().id(4L).gitlabProjectId(40L).build();
    @Test void viewDenialPreventsAnyBotOrProjectLookup() {
        doThrow(new IllegalArgumentException("denied")).when(projectPermissionService)
            .assertPermission(actor,4L,ProjectPermissionType.PROJECT_VIEW);
        assertThatThrownBy(() -> controller.readiness(4L,actor)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gitlabService,projectService,projectReadinessService);
    }
    @Test void authorizedReadinessUsesValidatedReadonlyTokenForExactProject() {
        when(projectService.findByIdWithDetails(4L,actor)).thenReturn(project);
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        var result=ProjectReadiness.builder().deployable(true).build();
        when(projectReadinessService.evaluate(project,"validated-bot")).thenReturn(result);
        assertThat(controller.readiness(4L,actor).getData()).isSameAs(result);
        verify(gitlabService,never()).resolveAccessToken(any(),any());
    }
    @Test void missingValidatedBotRemainsUnavailableToReadinessEvaluator() {
        when(projectService.findByIdWithDetails(4L,actor)).thenReturn(project);
        controller.readiness(4L,actor);
        verify(projectReadinessService).evaluate(project,null);
    }
}
