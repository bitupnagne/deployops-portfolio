package com.portfolio.deployops.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.*;
import com.portfolio.deployops.dto.GitlabMergeRequestActionForm;
import com.portfolio.deployops.repository.ProjectRepository;
import com.portfolio.deployops.service.*;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class GitlabMergeRequestAuditControllerTest {
    @Mock GitlabService gitlabService;
    @Mock ProjectRepository projectRepository;
    @Mock ProjectPermissionService projectPermissionService;
    @Mock MergeRequestGovernanceService governance;
    @Mock AuditLogService audit;
    @Mock OfficeNotificationService notifications;
    @InjectMocks GitlabApiController controller;

    private final SecurityUserDetails actor = new SecurityUserDetails(User.builder()
        .id(3L).loginId("ap-qa").name("QA approver").role(UserRole.APPROVER)
        .gitlabUsername("qa-approver").gitlabToken("private-pat-sentinel").build());
    private final Project project = Project.builder().id(1L).gitlabProjectId(30L).build();
    private final GitlabMergeRequestActionForm form = new GitlabMergeRequestActionForm(
        "a".repeat(40), "main", "b".repeat(40), "c".repeat(40));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private String action(boolean approval) { return approval ? "approval" : "merge"; }

    private void setup(boolean approval) {
        request.setRemoteAddr("192.0.2.3");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        if (approval) {
            when(gitlabService.resolveAccessTokenInfo(actor.getUser().getGitlabToken(),
                actor.getRole(), actor.getUsername()))
                .thenReturn(new GitlabService.TokenResolution("private-pat-sentinel", "USER_PAT"));
        } else {
            when(gitlabService.resolveAccessToken(actor.getUser().getGitlabToken(),
                actor.getRole(), actor.getUsername())).thenReturn("private-pat-sentinel");
        }
    }

    private com.portfolio.deployops.dto.ApiResponse<?> invoke(boolean approval) {
        return approval ? controller.approveMergeRequest(1L, 2L, form, actor, request)
            : controller.mergeMergeRequest(1L, 2L, form, actor, request);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void intentAuditFailurePreventsExternalEffect(boolean approval) {
        setup(approval);
        doThrow(new IllegalStateException("audit unavailable"))
            .when(audit).record(eq("QA approver"), eq("GitLab MR " + action(approval) + " requested"),
                eq("GitLabMergeRequest"), eq("30!2"), anyString(), eq(AuditResult.UNKNOWN), eq("192.0.2.3"));
        assertThatThrownBy(() -> invoke(approval)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(governance, notifications);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void confirmedActionRecordsSuccessAfterGovernance(boolean approval) throws Exception {
        setup(approval);
        if (approval) {
            when(governance.approve(project, 2L, form.reviewedSha(), "private-pat-sentinel", "USER_PAT",
                form.reviewedTargetBranch(), form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), actor))
                .thenReturn(Map.of("evidenceId", 9L));
        } else {
            when(governance.merge(project, 2L, form.reviewedSha(), "private-pat-sentinel",
                form.reviewedTargetBranch(), form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), actor))
                .thenReturn(Map.of("evidenceId", 9L));
        }
        assertThat(invoke(approval).isSuccess()).isTrue();
        var order = inOrder(audit, governance);
        order.verify(audit).record(eq("QA approver"), eq("GitLab MR " + action(approval) + " requested"),
            eq("GitLabMergeRequest"), eq("30!2"), anyString(), eq(AuditResult.UNKNOWN), eq("192.0.2.3"));
        if (approval) {
            order.verify(governance).approve(project, 2L, form.reviewedSha(), "private-pat-sentinel", "USER_PAT",
                form.reviewedTargetBranch(), form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), actor);
        } else {
            order.verify(governance).merge(project, 2L, form.reviewedSha(), "private-pat-sentinel",
                form.reviewedTargetBranch(), form.reviewedDiffBaseSha(), form.reviewedDiffStartSha(), actor);
        }
        order.verify(audit).record(eq("QA approver"), eq(approval ? "GitLab MR approved" : "GitLab MR merged"),
            eq("GitLabMergeRequest"), eq("30!2"), argThat(d -> d.contains(form.reviewedSha()) && !d.contains("private-pat")),
            eq(AuditResult.SUCCESS), eq("192.0.2.3"));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void uncertainExternalFailureIsUnknownAndDoesNotLeakException(boolean approval) throws Exception {
        setup(approval);
        if (approval) {
            when(governance.approve(any(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("private-pat-sentinel"));
        } else {
            when(governance.merge(any(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("private-pat-sentinel"));
        }
        var response = invoke(approval);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).doesNotContain("private-pat-sentinel");
        verify(audit).record(eq("QA approver"), eq("GitLab MR " + action(approval) + " not confirmed"),
            eq("GitLabMergeRequest"), eq("30!2"), argThat(d -> !d.contains("private-pat")),
            eq(AuditResult.UNKNOWN), eq("192.0.2.3"));
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString(),
            eq(AuditResult.SUCCESS), anyString());
        verifyNoInteractions(notifications);
    }

    @Test
    void knownPreflightRejectionKeeps409ReasonAndRecordsFailureForTrustedActor() throws Exception {
        setup(false);
        String reason = "검토 후 MR source SHA가 변경되었습니다. 다시 검토해 주세요.";
        var rejection = new MergeRequestGovernanceService.MergePreflightRejectedException(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, reason));
        when(governance.merge(any(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenThrow(rejection);

        var thrown = catchThrowableOfType(() -> invoke(false),
            MergeRequestGovernanceService.MergePreflightRejectedException.class);
        assertThat(thrown).isSameAs(rejection);
        var response = new GlobalExceptionHandler().handleResponseStatus(thrown);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo(reason);
        verify(audit).record(eq("QA approver"), eq("GitLab MR merge rejected"),
            eq("GitLabMergeRequest"), eq("30!2"),
            argThat(d -> d.contains(form.reviewedSha()) && d.contains(reason) && !d.contains("private-pat")),
            eq(AuditResult.FAIL), eq("192.0.2.3"));
        verify(audit, never()).record(anyString(), eq("GitLab MR merge not confirmed"),
            anyString(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(notifications);
    }

    @Test
    void knownPreflightRejectionUsesRealMvc409AndGlobalAdvice() throws Exception {
        setup(false);
        String reason = "검토 후 MR source SHA가 변경되었습니다. 다시 검토해 주세요.";
        when(governance.merge(any(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenThrow(
                new MergeRequestGovernanceService.MergePreflightRejectedException(
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, reason)));
        var principal = new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                return parameter.getParameterType() == SecurityUserDetails.class;
            }

            @Override
            public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                    org.springframework.web.method.support.ModelAndViewContainer container,
                    org.springframework.web.context.request.NativeWebRequest webRequest,
                    org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                return actor;
            }
        };
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(principal).build();
        String body = "{\"reviewedSha\":\"" + form.reviewedSha() + "\",\"reviewedTargetBranch\":\"main\","
            + "\"reviewedDiffBaseSha\":\"" + form.reviewedDiffBaseSha() + "\",\"reviewedDiffStartSha\":\""
            + form.reviewedDiffStartSha() + "\"}";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/gitlab/deploy-projects/1/merge-requests/2/merge")
                .with(r -> { r.setRemoteAddr("192.0.2.3"); return r; })
                .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("409 CONFLICT"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(reason));
        verify(audit).record(eq("QA approver"), eq("GitLab MR merge rejected"),
            eq("GitLabMergeRequest"), eq("30!2"), argThat(d -> d.contains(reason)),
            eq(AuditResult.FAIL), eq("192.0.2.3"));
        verify(audit, never()).record(anyString(), eq("GitLab MR merge not confirmed"),
            anyString(), anyString(), anyString(), any(), anyString());
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest @ValueSource(strings = {"waitForMergedResult", "markMerged"})
    void untypedPostWriteConflictStillRecordsUnknown(String phase) throws Exception {
        setup(false);
        when(governance.merge(any(), anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, phase + " private-pat-sentinel"));

        var response = invoke(false);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("GITLAB_ERROR");
        assertThat(response.getMessage()).doesNotContain("private-pat-sentinel");
        verify(audit).record(eq("QA approver"), eq("GitLab MR merge not confirmed"),
            eq("GitLabMergeRequest"), eq("30!2"), anyString(), eq(AuditResult.UNKNOWN), eq("192.0.2.3"));
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString(),
            eq(AuditResult.FAIL), anyString());
        verifyNoInteractions(notifications);
    }
}
