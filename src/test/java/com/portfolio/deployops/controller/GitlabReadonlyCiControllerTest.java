package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitlabReadonlyCiControllerTest {
    @Mock GitlabService gitlabService;
    @Mock ProjectRepository projectRepository;
    @Mock ProjectPermissionService projectPermissionService;
    @Mock GitlabCiReadinessService gitlabCiReadinessService;
    @Mock ExecutionPolicyService executionPolicyService;
    @Mock SecretMaskingService secretMaskingService;
    @Mock GitlabCiInspectService gitlabCiInspectService;
    @InjectMocks GitlabApiController controller;
    private final SecurityUserDetails operator = new SecurityUserDetails(User.builder()
        .id(4L).loginId("operator").role(UserRole.OPERATOR).build());
    private final Project project = Project.builder().id(4L).gitlabProjectId(40L).defaultBranch("main").build();
    @Test void allSixReadonlyRoutesRequireViewBeforeAnyBotLookup() {
        doThrow(new IllegalArgumentException("denied")).when(projectPermissionService)
            .assertPermission(operator, 4L, ProjectPermissionType.PROJECT_VIEW);
        List<Runnable> routes = List.of(
            () -> controller.listPipelinesByDeployProject(4L,"main",operator),
            () -> controller.getPipelineByDeployProject(4L,8L,operator),
            () -> controller.ciReadiness(4L,"main",operator),
            () -> controller.pipelineJobs(4L,8L,operator),
            () -> controller.jobTrace(4L,8L,21L,operator),
            () -> controller.readCiFile(4L,"main",operator));
        for (Runnable route : routes) assertThatThrownBy(route::run).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gitlabService, projectRepository, gitlabCiReadinessService);
    }
    @Test void operatorWithoutPatCanReadExactProjectPipelineAndJobs() throws Exception {
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        when(projectRepository.findById(4L)).thenReturn(Optional.of(project));
        when(gitlabService.getPipelines("validated-bot",null,40L,"main")).thenReturn(List.of(Map.of("id",8L)));
        when(gitlabService.getPipeline("validated-bot",null,40L,8L)).thenReturn(Map.of("id",8L));
        when(gitlabService.getPipelineJobs("validated-bot",null,40L,8L)).thenReturn(List.of(Map.of("id",21L)));
        assertThat(controller.listPipelinesByDeployProject(4L,"main",operator).isSuccess()).isTrue();
        assertThat(controller.getPipelineByDeployProject(4L,8L,operator).isSuccess()).isTrue();
        assertThat(controller.pipelineJobs(4L,8L,operator).isSuccess()).isTrue();
        verify(gitlabService,never()).resolveAccessToken(any(),any(),any());
    }
    @Test void readonlyReadinessUsesBotAndCurrentMrPolicy() throws Exception {
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        when(projectRepository.findById(4L)).thenReturn(Optional.of(project));
        when(executionPolicyService.requireMrEvidenceForBuild()).thenReturn(true);
        var readiness=GitlabCiReadiness.builder().ready(true).build();
        when(gitlabCiReadinessService.evaluateForMergeRequestBuild("validated-bot",null,project,"main")).thenReturn(readiness);
        assertThat(controller.ciReadiness(4L,null,operator).getData()).isSameAs(readiness);
    }
    @Test void traceStillRequiresJobMembershipAndMasksSecrets() throws Exception {
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        when(projectRepository.findById(4L)).thenReturn(Optional.of(project));
        when(gitlabService.getPipelineJobs("validated-bot",null,40L,8L)).thenReturn(List.of(Map.of("id",21L)));
        assertThat(controller.jobTrace(4L,8L,99L,operator).getErrorCode()).isEqualTo("JOB_NOT_FOUND");
        verify(gitlabService,never()).getJobTrace(anyString(),any(),anyLong(),anyLong());
        when(gitlabService.getJobTrace("validated-bot",null,40L,21L)).thenReturn("credential=secret");
        when(secretMaskingService.mask("credential=secret")).thenReturn("credential=***");
        assertThat(controller.jobTrace(4L,8L,21L,operator).getData()).isEqualTo(Map.of("trace","credential=***","truncated",false));
    }
    @Test void readonlyCiFileUsesExactLinkedProjectAndImmutableBranchCommit() throws Exception {
        String sha="a".repeat(40);
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        when(projectRepository.findById(4L)).thenReturn(Optional.of(project));
        when(gitlabService.getBranchCommitSha("validated-bot",null,40L,"main")).thenReturn(sha);
        when(gitlabService.readRepositoryFileBounded("validated-bot",null,40L,".gitlab-ci.yml",sha,262144)).thenReturn("stages: [build]");
        when(gitlabCiInspectService.suggest("stages: [build]",project)).thenReturn(Map.of());
        Map<?,?> data=(Map<?,?>)controller.readCiFile(4L,null,operator).getData();
        assertThat(data.get("content")).isEqualTo("stages: [build]");
        assertThat(data.get("baseCommitSha")).isEqualTo(sha);
        verify(gitlabService,never()).resolveAccessToken(any(),any(),any());
    }
    @Test void ciFileWithoutValidatedTokenNeverReadsProjectOrRepository() {
        assertThat(controller.readCiFile(4L,null,operator).getErrorCode()).isEqualTo("NO_TOKEN");
        verifyNoInteractions(projectRepository);
    }
    @Test void unvalidatedBotReturnsNoTokenWithoutProjectRemoteLookup() {
        assertThat(controller.pipelineJobs(4L,8L,operator).getErrorCode()).isEqualTo("NO_TOKEN");
        verifyNoInteractions(projectRepository);
    }
    @Test void readinessWithPersonalDeveloperPatStillUsesValidatedBot() throws Exception {
        operator.getUser().setGitlabToken("developer-personal-pat");
        when(gitlabService.resolveReadonlyCiAccessToken(null)).thenReturn("validated-bot");
        when(projectRepository.findById(4L)).thenReturn(Optional.of(project));
        var readiness = GitlabCiReadiness.builder().ready(true).build();
        when(gitlabCiReadinessService.evaluate("validated-bot", null, project, "main"))
            .thenReturn(readiness);
        assertThat(controller.ciReadiness(4L, null, operator).getData()).isSameAs(readiness);
        verify(gitlabService, never()).resolveReadonlyCiAccessToken("developer-personal-pat");
        verify(gitlabService, never()).resolveAccessToken(any(), any(), any());
    }

    @Test void readinessWithoutValidatedBotFailsClosedDespitePersonalPat() {
        operator.getUser().setGitlabToken("developer-personal-pat");
        assertThat(controller.ciReadiness(4L, null, operator).getErrorCode()).isEqualTo("NO_TOKEN");
        verifyNoInteractions(projectRepository, gitlabCiReadinessService);
        verify(gitlabService, never()).resolveReadonlyCiAccessToken("developer-personal-pat");
    }

    @Test void readinessPermissionDeniedBeforeBotResolution() {
        doThrow(new org.springframework.security.access.AccessDeniedException("denied"))
            .when(projectPermissionService).assertPermission(operator, 4L, ProjectPermissionType.PROJECT_VIEW);
        assertThatThrownBy(() -> controller.ciReadiness(4L, null, operator))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(gitlabService, projectRepository, gitlabCiReadinessService);
    }
}
