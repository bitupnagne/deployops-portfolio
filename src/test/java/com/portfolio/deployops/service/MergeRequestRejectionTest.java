package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MergeRequestRejectionTest {
    @Test void unresolvedIndividualDiffStopsBeforeNoteAndCloseEvenWithoutOverflow() throws Exception {
        prepare();
        for (String flag : List.of("collapsed", "tooLarge")) {
            Map<String, Object> incomplete = mr("opened");
            incomplete.put("changes", List.of(Map.of(flag, true)));
            when(gitlab.getMergeRequestChanges("pat", null, 101L, 4L)).thenReturn(incomplete);
            assertThatThrownBy(() -> reject("USER_PAT", "반려"))
                .isInstanceOf(ResponseStatusException.class);
        }
        verify(gitlab, never()).postMergeRequestNote(any(), any(), anyLong(), anyLong(), any());
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
        verifyNoInteractions(evidence, invalidation);
    }
    private static final String SHA = "a".repeat(40), BASE = "b".repeat(40), START = "c".repeat(40);
    private final GitlabService gitlab = mock(GitlabService.class);
    private final MergeRequestEvidenceRepository evidence = mock(MergeRequestEvidenceRepository.class);
    private final MergeRequestOriginRepository origin = mock(MergeRequestOriginRepository.class);
    private final MergeRequestApprovalInvalidationService invalidation = mock(MergeRequestApprovalInvalidationService.class);
    private final MergeRequestGovernanceService service = new MergeRequestGovernanceService(gitlab, evidence, origin, invalidation);
    private final Project project = Project.builder().id(3L).gitlabProjectId(101L).build();
    private final SecurityUserDetails reviewer = new SecurityUserDetails(User.builder().id(7L)
        .loginId("reviewer").gitlabUsername("reviewer").role(UserRole.APPROVER).build());

    private Map<String, Object> mr(String state) {
        return new LinkedHashMap<>(Map.of("state", state, "sha", SHA, "targetProjectId", 101L,
            "targetBranch", "main", "authorId", 21L, "overflow", false,
            "diffRefs", Map.of("base_sha", BASE, "start_sha", START)));
    }
    private void prepare() throws Exception {
        when(gitlab.getAuthenticatedUserIdentity("pat", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(77L, "reviewer"));
        when(gitlab.getMergeRequest("pat", null, 101L, 4L))
            .thenReturn(mr("opened"), mr("opened"), mr("closed"));
        when(gitlab.getMergeRequestChanges("pat", null, 101L, 4L)).thenReturn(mr("opened"));
    }
    private Map<String, Object> reject(String tokenSource, String reason) throws Exception {
        return service.reject(project, 4L, SHA, "pat", tokenSource, "main", BASE, START, reason, reviewer);
    }
    @Test void closesWithPersonalIdentityAndReasonThenInvalidatesApprovals() throws Exception {
        prepare();
        assertThat(reject("USER_PAT", "검증 실패")).containsEntry("state", "closed");
        var ordered = inOrder(gitlab, invalidation);
        ordered.verify(gitlab).postMergeRequestNote("pat", null, 101L, 4L,
            "DeployOps MR 반려 요청 (검토 SHA: " + SHA + ")\n\n사유:\n> 검증 실패");
        ordered.verify(invalidation).invalidate(3L, 101L, 4L);
        ordered.verify(gitlab).closeMergeRequest("pat", null, 101L, 4L);
        ordered.verify(gitlab).getMergeRequest("pat", null, 101L, 4L);
        ordered.verify(invalidation).invalidate(3L, 101L, 4L);
        verify(gitlab, never()).mergeMergeRequest(any(), any(), anyLong(), anyLong(), any(), anyBoolean());
    }
    @Test void systemFallbackAndEmptyOrOversizedReasonsHaveNoExternalEffect() {
        assertThatThrownBy(() -> reject("SYSTEM_BOT", "반려")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reject("USER_PAT", "  ")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reject("USER_PAT", "a".repeat(1001))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(gitlab, evidence, origin);
    }
    @Test void reasonCannotExecuteGitlabQuickActions() throws Exception {
        prepare();
        reject("USER_PAT", "반려\n/merge\n/reopen");
        verify(gitlab).postMergeRequestNote("pat", null, 101L, 4L,
            "DeployOps MR 반려 요청 (검토 SHA: " + SHA + ")\n\n사유:\n> 반려\n> &#47;merge\n> &#47;reopen");
    }
    @Test void tokenIdentityMismatchHasNoExternalMutation() throws Exception {
        prepare();
        when(gitlab.getAuthenticatedUserIdentity("pat", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(78L, "other"));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("계정");
        verify(gitlab, never()).postMergeRequestNote(any(), any(), anyLong(), anyLong(), any());
        verifyNoInteractions(evidence);
    }
    @Test void authorAndOtherOriginProjectCannotReject() throws Exception {
        prepare();
        Map<String, Object> own = mr("opened"); own.put("authorId", 77L);
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(own);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("작성자");
        when(origin.findProjectIdsByGitlabProjectIdAndMergeRequestIid(101L, 4L)).thenReturn(List.of(99L));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("다른 DeployOps");
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
    }
    @Test void staleDiffOrShaStopsBeforeNoteAndClose() throws Exception {
        prepare();
        Map<String, Object> stale = mr("opened"); stale.put("targetBranch", "release");
        when(gitlab.getMergeRequestChanges("pat", null, 101L, 4L)).thenReturn(stale);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("diff 기준");
        stale.put("sha", "d".repeat(40));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("SHA");
        verify(gitlab, never()).postMergeRequestNote(any(), any(), anyLong(), anyLong(), any());
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
    }
    @Test void changeAfterNoteStopsCloseAndRetainsEvidence() throws Exception {
        prepare();
        Map<String, Object> stale = mr("opened"); stale.put("sha", "d".repeat(40));
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(mr("opened"), stale);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("source SHA");
        verify(gitlab).postMergeRequestNote(any(), any(), anyLong(), anyLong(), any());
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
        verifyNoInteractions(evidence);
    }
    @Test void closeFailureLeavesDurableApprovalWithdrawalAndDoesNotReportSuccess() throws Exception {
        prepare();
        doThrow(new IllegalStateException("GitLab unavailable")).when(gitlab).closeMergeRequest("pat", null, 101L, 4L);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("unavailable");
        verify(invalidation).invalidate(3L, 101L, 4L);
        verifyNoInteractions(evidence);
    }
    @Test void observedOpenOrChangedHeadAfterCloseDoesNotConfirmRejection() throws Exception {
        prepare();
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(mr("opened"));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("닫힘 상태");
        Map<String, Object> raced = mr("closed"); raced.put("sha", "d".repeat(40));
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(mr("opened"), mr("opened"), raced);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("source SHA");
        verify(invalidation, times(2)).invalidate(3L, 101L, 4L);
        verifyNoInteractions(evidence);
    }
    @Test void confirmationLookupFailureLeavesApprovalWithdrawnBeforeExternalClose() throws Exception {
        prepare();
        when(gitlab.getMergeRequest("pat", null, 101L, 4L))
            .thenReturn(mr("opened"), mr("opened")).thenThrow(new IllegalStateException("GET unavailable"));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("GET unavailable");
        var ordered = inOrder(invalidation, gitlab);
        ordered.verify(invalidation).invalidate(3L, 101L, 4L);
        ordered.verify(gitlab).closeMergeRequest("pat", null, 101L, 4L);
    }
    @Test void approvalWithdrawalFailurePreventsExternalClose() throws Exception {
        prepare();
        doThrow(new IllegalStateException("DB unavailable")).when(invalidation).invalidate(3L, 101L, 4L);
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("DB unavailable");
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
    }
    @Test void mergedMrCannotBeRejected() throws Exception {
        prepare();
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(mr("merged"));
        assertThatThrownBy(() -> reject("USER_PAT", "반려")).hasMessageContaining("열린 MR");
        verify(gitlab, never()).closeMergeRequest(any(), any(), anyLong(), anyLong());
    }
}
