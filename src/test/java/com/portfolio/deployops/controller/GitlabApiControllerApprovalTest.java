package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.ProjectPermissionType;
import com.portfolio.deployops.dto.GitlabCiReadiness;
import com.portfolio.deployops.dto.GitlabMergeRequestActionForm;
import com.portfolio.deployops.dto.GitlabMergeRequestRejectForm;
import com.portfolio.deployops.dto.GitlabMergeRequestCreateForm;
import com.portfolio.deployops.repository.DeployRequestRepository;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.AuditLogService;
import com.portfolio.deployops.service.ExecutionPolicyService;
import com.portfolio.deployops.service.GitlabCiReadinessService;
import com.portfolio.deployops.service.GitlabService;
import com.portfolio.deployops.service.MergeRequestGovernanceService;
import com.portfolio.deployops.service.ProjectPermissionService;
import com.portfolio.deployops.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitlabApiControllerApprovalTest {

    @Mock private GitlabService gitlabService;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectService projectService;
    @Mock private MergeRequestEvidenceRepository mergeRequestEvidenceRepository;
    @Mock private MergeRequestOriginRepository mergeRequestOriginRepository;
    @Mock private ProjectPermissionService projectPermissionService;
    @Mock private MergeRequestGovernanceService mergeRequestGovernanceService;
    @Mock private DeployRequestRepository deployRequestRepository;
    @Mock private ExecutionPolicyService executionPolicyService;
    @Mock private GitlabCiReadinessService gitlabCiReadinessService;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private GitlabApiController controller;

    @Test
    void revokedApprovalPermissionStopsBeforeTokenLookupOrExternalEffect() {
        var reviewer = reviewerWithPat();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(projectPermissionService)
            .assertPermission(reviewer, 3L, ProjectPermissionType.MR_APPROVE);
        var form = new GitlabMergeRequestActionForm(SHA, "main", "b".repeat(40), "c".repeat(40));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            controller.approveMergeRequest(3L, 5L, form, reviewer, new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gitlabService, projectRepository, mergeRequestGovernanceService,
            mergeRequestEvidenceRepository, auditLogService);
    }
    @Test
    void missingApprovalIdentityExplainsRecoveryBeforeAnyExternalEffect() {
        for (String username : new String[] {null, "", "   "}) {
            var reviewer = reviewerWithPat();
            reviewer.getUser().setGitlabUsername(username);
            var form = new GitlabMergeRequestActionForm(SHA, "main", "b".repeat(40), "c".repeat(40));

            var response = controller.approveMergeRequest(3L, 5L, form, reviewer, new MockHttpServletRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("GITLAB_IDENTITY_REQUIRED");
            assertThat(response.getMessage()).contains("사용자명 연결", "PAT 변경", "다시 로그인")
                .doesNotContain("변경이 남았을 수");
        }
        verifyNoInteractions(gitlabService, projectRepository, mergeRequestGovernanceService,
            mergeRequestEvidenceRepository, auditLogService);
    }

    @Test
    void sharedIntegrationActorReportsKnownDenialForBothReviewActions() throws Exception {
        var reviewer = reviewerWithPat();
        var project = Project.builder().id(3L).gitlabProjectId(30L).build();
        var form = new GitlabMergeRequestActionForm(SHA, "main", "b".repeat(40), "c".repeat(40));
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(mergeRequestGovernanceService.approve(project, 4L, SHA, "reviewer-pat", "USER_PAT",
            "main", "b".repeat(40), "c".repeat(40), reviewer))
            .thenThrow(new GitlabService.SharedApprovalActorException());
        when(mergeRequestGovernanceService.reject(project, 4L, rejection().reviewedSha(), "reviewer-pat", "USER_PAT",
            "main", "b".repeat(40), "c".repeat(40), "검증 실패", reviewer))
            .thenThrow(new GitlabService.SharedApprovalActorException());

        var request = new MockHttpServletRequest();
        var approve = controller.approveMergeRequest(3L, 4L, form, reviewer, request);
        var reject = controller.rejectMergeRequest(3L, 4L, rejection(), reviewer, request);
        for (var result : java.util.List.of(approve, reject)) {
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("MR_SHARED_APPROVAL_ACTOR");
            assertThat(result.getMessage()).contains("연동 계정과 다른 개인 GitLab 계정");
        }
    }

    @Test
    void failureAfterGovernanceStartsStillReportsUnknownRemoteEffectWithoutLeakingDetails() throws Exception {
        var reviewer = reviewerWithPat();
        var project = Project.builder().id(3L).gitlabProjectId(30L).build();
        var form = new GitlabMergeRequestActionForm(SHA, "main", "b".repeat(40), "c".repeat(40));
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(mergeRequestGovernanceService.approve(project, 5L, SHA, "reviewer-pat", "USER_PAT",
            "main", "b".repeat(40), "c".repeat(40), reviewer))
            .thenThrow(new IllegalStateException("sensitive remote response"));

        var response = controller.approveMergeRequest(3L, 5L, form, reviewer, new MockHttpServletRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("GITLAB_ERROR");
        assertThat(response.getMessage()).contains("GitLab 변경이 남았을 수")
            .doesNotContain("sensitive remote response");
    }

    @Test
    void readonlyReadinessUsesValidatedBotAndMrPolicyOnlyWhenRequired() throws Exception {
        var viewer = reviewerWithPat();
        var project = Project.builder().id(3L).gitlabProjectId(30L).defaultBranch("main").build();
        when(gitlabService.resolveReadonlyCiAccessToken(null))
            .thenReturn("validated-bot");
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(executionPolicyService.requireMrEvidenceForBuild()).thenReturn(true);
        var blocked = GitlabCiReadiness.builder().ready(false)
            .blockers(java.util.List.of("deploy/mr-* 보호 필요")).build();
        when(gitlabCiReadinessService.evaluateForMergeRequestBuild("validated-bot", null, project, "main"))
            .thenReturn(blocked);
        assertThat(controller.ciReadiness(3L, null, viewer).getData()).isSameAs(blocked);
        verify(projectPermissionService).assertPermission(viewer, 3L, ProjectPermissionType.PROJECT_VIEW);
        when(executionPolicyService.requireMrEvidenceForBuild()).thenReturn(false);
        var generic = GitlabCiReadiness.builder().ready(true).build();
        when(gitlabCiReadinessService.evaluate("validated-bot", null, project, "main")).thenReturn(generic);
        assertThat(controller.ciReadiness(3L, null, viewer).getData()).isSameAs(generic);
        verifyNoInteractions(mergeRequestGovernanceService, auditLogService);
    }

    private GitlabMergeRequestRejectForm rejection() {
        return new GitlabMergeRequestRejectForm("a".repeat(40), "main", "b".repeat(40), "c".repeat(40), "검증 실패");
    }

    @Test
    void rejectionChecksApprovalPermissionBeforeTokenOrProjectLookup() {
        var reviewer = reviewerWithPat();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(projectPermissionService)
            .assertPermission(reviewer, 3L, ProjectPermissionType.MR_APPROVE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.rejectMergeRequest(
            3L, 4L, rejection(), reviewer, new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gitlabService, projectRepository, mergeRequestGovernanceService, auditLogService);
    }

    @Test
    void rejectionRejectsSystemFallbackWithoutExternalEffect() {
        var reviewer = reviewerWithPat();
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("bot", "SYSTEM_BOT"));
        var response = controller.rejectMergeRequest(3L, 4L, rejection(), reviewer, new MockHttpServletRequest());
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PERSONAL_PAT_REQUIRED");
        verifyNoInteractions(projectRepository, mergeRequestGovernanceService, auditLogService);
    }

    @Test
    void rejectionFailureRetainsReasonInUnknownAuditAndReportsPartialEffect() throws Exception {
        var reviewer = reviewerWithPat();
        var project = Project.builder().id(3L).gitlabProjectId(30L).build();
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(mergeRequestGovernanceService.reject(project, 4L, "a".repeat(40), "reviewer-pat", "USER_PAT",
            "main", "b".repeat(40), "c".repeat(40), "검증 실패", reviewer))
            .thenThrow(new IllegalStateException("close unavailable"));
        var response = controller.rejectMergeRequest(3L, 4L, rejection(), reviewer, new MockHttpServletRequest());
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("MR_REJECTION_NOT_CONFIRMED");
        assertThat(response.getMessage()).contains("사유가 남거나 MR이 닫혔을 수").doesNotContain("close unavailable");
        verify(auditLogService).record(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("GitLab MR rejection not confirmed"),
            org.mockito.ArgumentMatchers.eq("GitLabMergeRequest"), org.mockito.ArgumentMatchers.eq("30!4"),
            org.mockito.ArgumentMatchers.contains("reason=검증 실패"),
            org.mockito.ArgumentMatchers.eq(com.portfolio.deployops.domain.AuditResult.UNKNOWN), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectionFormRejectsMissingAndOversizedReasons() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(rejection())).isEmpty();
            for (String reason : new String[]{"", "  ", "x".repeat(1001)}) {
                assertThat(validator.validate(new GitlabMergeRequestRejectForm("a".repeat(40), "main",
                    "b".repeat(40), "c".repeat(40), reason))).isNotEmpty();
            }
        }
    }

    @Test
    void mergeRequestListChecksProjectPermissionBeforeGitlabToken() {
        SecurityUserDetails user = new SecurityUserDetails(User.builder()
            .id(8L).loginId("developer").role(UserRole.DEVELOPER).build());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(projectPermissionService).assertPermission(user, 3L, ProjectPermissionType.MR_REVIEW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.listMergeRequestsByDeployProject(3L, "opened", user))
            .isInstanceOf(ResponseStatusException.class);

        verify(projectPermissionService).assertPermission(user, 3L, ProjectPermissionType.MR_REVIEW);
        verifyNoInteractions(gitlabService, projectRepository);
    }

    @Test
    void mergeRequestCreationChecksProjectPermissionBeforeGitlabToken() {
        SecurityUserDetails user = new SecurityUserDetails(User.builder()
            .id(8L).loginId("developer").role(UserRole.DEVELOPER).build());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(projectPermissionService).assertPermission(user, 3L, ProjectPermissionType.MR_REVIEW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.createMergeRequest(
                3L, new GitlabMergeRequestCreateForm(), user, new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class);

        verify(projectPermissionService).assertPermission(user, 3L, ProjectPermissionType.MR_REVIEW);
        verifyNoInteractions(gitlabService, projectRepository);
    }

    @Test
    void approvalRejectsSystemTokenFallbackBeforeLoadingProjectOrCallingGitlab() {
        SecurityUserDetails reviewer = new SecurityUserDetails(User.builder()
            .id(7L).loginId("reviewer").gitlabUsername("reviewer")
            .role(UserRole.APPROVER).build());
        when(gitlabService.resolveAccessTokenInfo(null, reviewer.getRole(), reviewer.getUsername()))
            .thenReturn(new GitlabService.TokenResolution("shared-system-token", "SYSTEM_BOT"));
        GitlabMergeRequestActionForm form = new GitlabMergeRequestActionForm(
            "0123456789abcdef0123456789abcdef01234567", "main",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        var response = controller.approveMergeRequest(3L, 4L, form, reviewer, new MockHttpServletRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PERSONAL_PAT_REQUIRED");
        verifyNoInteractions(projectRepository, mergeRequestGovernanceService);
    }

    @Test
    void mergedReviewOffersReconciliationOnlyForPendingApprovalAtTheCurrentSha() throws Exception {
        var merger = reviewerWithPat();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer")).thenReturn("reviewer-pat");
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(Project.builder().id(3L).gitlabProjectId(30L).build()));
        when(projectPermissionService.hasPermission(merger, 3L, ProjectPermissionType.MR_MERGE)).thenReturn(true);
        when(gitlabService.getMergeRequestChanges("reviewer-pat", null, 30L, 5L))
            .thenReturn(new java.util.LinkedHashMap<>(Map.of("state", "merged", "sha", SHA)));
        when(gitlabService.getMergeRequest("reviewer-pat", null, 30L, 5L))
            .thenReturn(Map.of("state", "merged", "sha", SHA, "canMerge", false));
        when(mergeRequestEvidenceRepository.findApproved(3L, 30L, 5L, SHA))
            .thenReturn(Optional.of(MergeRequestEvidence.builder().reviewerId(8L).build()))
            .thenReturn(Optional.empty());
        for (boolean expected : new boolean[]{true, false}) {
            var response = controller.getMergeRequestChanges(3L, 5L, merger);
            assertThat(response.isSuccess()).isTrue();
            assertThat(((Map<?, ?>) response.getData()).get("deployOpsCanReconcileMerge")).isEqualTo(expected);
        }
    }

    @Test
    void changesExposeApprovalOnlyWhenDeployOpsEvidenceAndGitLabStateBothMatchCurrentUser() throws Exception {
        var reviewer = reviewerWithPat();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(Project.builder()
            .id(3L).gitlabProjectId(30L).build()));
        when(gitlabService.getMergeRequestChanges("reviewer-pat", null, 30L, 5L))
            .thenReturn(new java.util.LinkedHashMap<>(Map.of("iid", 5L, "sha", SHA)));
        when(gitlabService.getMergeRequest("reviewer-pat", null, 30L, 5L))
            .thenReturn(Map.of("canMerge", false));
        when(mergeRequestEvidenceRepository.findApproved(3L, 30L, 5L, SHA))
            .thenReturn(Optional.of(MergeRequestEvidence.builder()
                .reviewerId(7L).reviewerGitlabUserId(77L).build()));
        when(gitlabService.getAuthenticatedUserIdentity("reviewer-pat", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(77L, "reviewer"));
        when(gitlabService.getMergeRequestApproverIds("reviewer-pat", null, 30L, 5L))
            .thenReturn(Set.of(77L));

        when(projectPermissionService.hasPermission(reviewer, 3L, ProjectPermissionType.MR_MERGE))
            .thenReturn(false);
        when(projectPermissionService.hasPermission(reviewer, 3L, ProjectPermissionType.MR_APPROVE))
            .thenReturn(true, false);
        var response = controller.getMergeRequestChanges(3L, 5L, reviewer);

        assertThat(response.isSuccess()).as("response error: %s", response.getMessage()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getData();
        assertThat(data.get("approvalStateKnown")).isEqualTo(true);
        assertThat(data.get("approvedByCurrentUser")).isEqualTo(true);
        assertThat(data.get("deployOpsCanApprove")).isEqualTo(true);
        assertThat(data.get("deployOpsCanReject")).isEqualTo(true);
        assertThat(data.get("deployOpsCanMerge")).isEqualTo(false);
        verify(mergeRequestEvidenceRepository).findApproved(3L, 30L, 5L, SHA);
        verify(projectPermissionService).hasPermission(reviewer, 3L,
            com.portfolio.deployops.domain.ProjectPermissionType.MR_MERGE);

        when(gitlabService.getMergeRequestApproverIds("reviewer-pat", null, 30L, 5L))
            .thenReturn(Set.of());
        var revokedResponse = controller.getMergeRequestChanges(3L, 5L, reviewer);
        Map<?, ?> revokedData = (Map<?, ?>) revokedResponse.getData();
        assertThat(revokedData.get("approvalStateKnown")).isEqualTo(true);
        assertThat(revokedData.get("approvedByCurrentUser")).isEqualTo(false);
        assertThat(revokedData.get("deployOpsCanApprove")).isEqualTo(false);
        assertThat(revokedData.get("deployOpsCanReject")).isEqualTo(false);
        assertThat(revokedData.get("deployOpsCanMerge")).isEqualTo(false);
    }

    @Test
    void pipelineRetryStopsWhenGitlabCiReadinessHasBlockers() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).gitlabProjectId(30L).defaultBranch("main").build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(gitlabService.getPipeline("reviewer-pat", null, 30L, 77L))
            .thenReturn(Map.of("status", "failed", "ref", "main"));
        when(gitlabCiReadinessService.evaluate("reviewer-pat", null, project, "main"))
            .thenReturn(GitlabCiReadiness.builder().ready(false).blockers(java.util.List.of("필수 변수 누락")).build());
        HttpServletRequest request = new MockHttpServletRequest();

        var response = controller.operatePipeline(3L, 77L, "retry", reviewer, request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("CI_NOT_READY");
        assertThat(response.getMessage()).contains("필수 변수 누락");
        verify(gitlabService, org.mockito.Mockito.never()).retryPipeline("reviewer-pat", null, 30L, 77L);
    }

    @Test
    void failedPipelineCannotBeCancelled() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).gitlabProjectId(30L).defaultBranch("main").build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(gitlabService.getPipeline("reviewer-pat", null, 30L, 77L))
            .thenReturn(Map.of("status", "failed", "ref", "main"));

        var response = controller.operatePipeline(3L, 77L, "cancel", reviewer, new MockHttpServletRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("PIPELINE_STATE_INVALID");
        verify(gitlabService, org.mockito.Mockito.never()).cancelPipeline("reviewer-pat", null, 30L, 77L);
    }

    @Test
    void failedPipelineCanBeRetriedAfterCiReadinessPasses() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).gitlabProjectId(30L).defaultBranch("main").build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(gitlabService.resolveAccessTokenInfo("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn(new GitlabService.TokenResolution("reviewer-pat", "USER_PAT"));
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(gitlabService.getPipeline("reviewer-pat", null, 30L, 77L))
            .thenReturn(Map.of("status", "failed", "ref", "main"));
        when(gitlabCiReadinessService.evaluate("reviewer-pat", null, project, "main"))
            .thenReturn(GitlabCiReadiness.builder().ready(true).blockers(java.util.List.of()).build());
        when(gitlabService.retryPipeline("reviewer-pat", null, 30L, 77L))
            .thenReturn(Map.of("status", "pending"));

        var response = controller.operatePipeline(3L, 77L, "retry", reviewer, new MockHttpServletRequest());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).contains("요청이 완료되었습니다");
        verify(gitlabService).retryPipeline("reviewer-pat", null, 30L, 77L);
    }

    @Test
    void mergeRequestQueueReportsGitlabLookupFailureInsteadOfShowingAnEmptyQueue() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).name("payment-api").gitlabProjectId(30L).build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(projectService.findMrApprovable(reviewer)).thenReturn(java.util.List.of(project));
        when(projectPermissionService.hasPermission(reviewer, 3L, ProjectPermissionType.MR_MERGE))
            .thenReturn(false);
        when(gitlabService.getMergeRequests("reviewer-pat", null, 30L, "opened"))
            .thenThrow(new java.io.IOException("GitLab unavailable"));

        var response = controller.myMergeRequests(reviewer);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(java.util.List.of());
        assertThat(response.getMessage())
            .contains("payment-api")
            .contains("PAT")
            .contains("연결 상태");
    }

    @Test
    void mergeRequestQueueKeepsSuccessfulProjectsWhenAnotherGitlabLookupFails() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project available = Project.builder().id(3L).name("payment-api").gitlabProjectId(30L).build();
        Project unavailable = Project.builder().id(4L).name("payment-web").gitlabProjectId(40L).build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(projectService.findMrApprovable(reviewer)).thenReturn(java.util.List.of(available, unavailable));
        when(projectPermissionService.hasPermission(reviewer, 3L, ProjectPermissionType.MR_MERGE))
            .thenReturn(false);
        when(projectPermissionService.hasPermission(reviewer, 4L, ProjectPermissionType.MR_MERGE))
            .thenReturn(false);
        when(gitlabService.getMergeRequests("reviewer-pat", null, 30L, "opened"))
            .thenReturn(java.util.List.of(Map.of("iid", 6L, "title", "available MR")));
        when(gitlabService.getMergeRequests("reviewer-pat", null, 40L, "opened"))
            .thenThrow(new java.io.IOException("GitLab unavailable"));

        var response = controller.myMergeRequests(reviewer);

        assertThat(response.isSuccess()).isTrue();
        assertThat((java.util.List<?>) response.getData()).hasSize(1);
        Map<?, ?> mr = (Map<?, ?>) ((java.util.List<?>) response.getData()).get(0);
        assertThat(mr.get("projectName")).isEqualTo("payment-api");
        assertThat(response.getMessage()).contains("payment-web").doesNotContain("payment-api");
    }

    @Test
    void mergeRequestQueueStillListsMrButFailsClosedWhenMergePermissionLookupFails() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).name("payment-api").gitlabProjectId(30L).build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(projectService.findMrApprovable(reviewer)).thenReturn(java.util.List.of(project));
        when(projectPermissionService.hasPermission(reviewer, 3L, ProjectPermissionType.MR_MERGE))
            .thenThrow(new IllegalStateException("permission store unavailable"));
        when(gitlabService.getMergeRequests("reviewer-pat", null, 30L, "opened"))
            .thenReturn(java.util.List.of(Map.of("iid", 6L, "title", "available MR")));

        var response = controller.myMergeRequests(reviewer);

        assertThat(response.isSuccess()).isTrue();
        Map<?, ?> mr = (Map<?, ?>) ((java.util.List<?>) response.getData()).get(0);
        assertThat(mr.get("projectName")).isEqualTo("payment-api");
        assertThat(mr.get("canMerge")).isEqualTo(false);
        assertThat(response.getMessage()).contains("병합 권한 확인 실패").contains("병합은 허용되지 않습니다");
    }

    @Test
    void mergeRequestQueueExcludesRequestsOwnedByDifferentDeployProjectAfterRepositoryRelink() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(4L).name("payment-web").gitlabProjectId(30L).build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(projectService.findMrApprovable(reviewer)).thenReturn(java.util.List.of(project));
        when(projectPermissionService.hasPermission(reviewer, 4L, ProjectPermissionType.MR_MERGE))
            .thenReturn(false);
        when(gitlabService.getMergeRequests("reviewer-pat", null, 30L, "opened"))
            .thenReturn(java.util.List.of(Map.of("iid", 5L, "title", "stale"),
                Map.of("iid", 6L, "title", "current")));
        when(mergeRequestOriginRepository.findProjectIdsByGitlabProjectIdAndMergeRequestIid(30L, 5L))
            .thenReturn(java.util.List.of(3L));
        when(mergeRequestOriginRepository.findProjectIdsByGitlabProjectIdAndMergeRequestIid(30L, 6L))
            .thenReturn(java.util.List.of(4L));

        var response = controller.myMergeRequests(reviewer);

        assertThat(response.isSuccess()).isTrue();
        assertThat((java.util.List<?>) response.getData()).hasSize(1);
        Map<?, ?> remaining = (Map<?, ?>) ((java.util.List<?>) response.getData()).get(0);
        assertThat(remaining.get("iid")).isEqualTo(6L);
        assertThat(response.getMessage()).contains("다른 DeployOps 프로젝트에서 생성된 과거 MR");
    }

    @Test
    void mergeRequestQueueExplainsWhenApprovableProjectHasNoGitlabRepository() throws Exception {
        SecurityUserDetails reviewer = reviewerWithPat();
        Project project = Project.builder().id(3L).name("payment-api").build();
        when(gitlabService.resolveAccessToken("reviewer-pat", "APPROVER", "reviewer"))
            .thenReturn("reviewer-pat");
        when(projectService.findMrApprovable(reviewer)).thenReturn(java.util.List.of(project));

        var response = controller.myMergeRequests(reviewer);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(java.util.List.of());
        assertThat(response.getMessage()).contains("GitLab 저장소가 연결되지 않은 프로젝트")
            .contains("payment-api");
        verify(gitlabService, org.mockito.Mockito.never())
            .getMergeRequests(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private SecurityUserDetails reviewerWithPat() {
        return new SecurityUserDetails(User.builder()
            .id(7L).loginId("reviewer").gitlabUsername("reviewer")
            .gitlabToken("reviewer-pat").role(UserRole.APPROVER).build());
    }
}
