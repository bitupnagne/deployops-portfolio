package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.MergeRequestEvidence;
import com.portfolio.deployops.domain.MergeRequestEvidenceStatus;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class MergeRequestGovernanceServiceTest {

    @Test void unresolvedIndividualDiffCannotApproveEvenWithoutOverflow() throws Exception {
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        for (String flag : java.util.List.of("collapsed", "tooLarge")) {
            when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
                "sha", SOURCE_SHA, "overflow", false, "targetBranch", "main",
                "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA),
                "changes", java.util.List.of(Map.of(flag, true))));
            assertThatThrownBy(() -> service.approve(project, 4L, SOURCE_SHA, "token", "USER_PAT",
                "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
                .isInstanceOf(ResponseStatusException.class);
        }
        verify(gitlab, never()).approveMergeRequest(any(), any(), anyLong(), anyLong(), any());
        verify(repository, never()).saveApproved(any());
    }

    private static final String SOURCE_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String MERGED_SHA = "89abcdef0123456789abcdef0123456789abcdef";
    private static final String BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String START_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private final GitlabService gitlab = mock(GitlabService.class);
    private final MergeRequestEvidenceRepository repository = mock(MergeRequestEvidenceRepository.class);
    private final MergeRequestOriginRepository originRepository = mock(MergeRequestOriginRepository.class);
    private final MergeRequestGovernanceService service = new MergeRequestGovernanceService(
        gitlab, repository, originRepository, mock(MergeRequestApprovalInvalidationService.class));
    private final Project project = Project.builder().id(3L).gitlabProjectId(101L).build();

    @org.junit.jupiter.api.BeforeEach
    void authenticatedReviewer() throws Exception {
        when(gitlab.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(7L, "reviewer"));
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(openMr("author"));
    }

    @Test void oldApprovalCannotMergeAnIncompleteOrChangedCurrentDiff() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L).status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        for (String change : java.util.List.of("collapsed", "tooLarge", "sha", "targetBranch", "diffRefs")) {
            Map<String, Object> diff = new java.util.LinkedHashMap<>(openMr("author"));
            switch (change) {
                case "collapsed", "tooLarge" -> diff.put("changes", java.util.List.of(Map.of(change, true)));
                case "sha" -> diff.put("sha", MERGED_SHA);
                case "targetBranch" -> diff.put("targetBranch", "release");
                case "diffRefs" -> diff.put("diffRefs", Map.of("base_sha", MERGED_SHA, "start_sha", START_SHA));
            }
            when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(diff);
            assertThatThrownBy(() -> service.merge(project, 4L, SOURCE_SHA, "token",
                "main", BASE_SHA, START_SHA, user(8L, "merger")))
                .isInstanceOf(ResponseStatusException.class);
        }
        verify(gitlab, never()).mergeMergeRequest(any(), any(), anyLong(), anyLong(), any(), anyBoolean());
        verify(repository, never()).markMerged(any());
    }

    @Test
    void approvalStoresServerReadDiffIdentityAndHumanReviewer() throws Exception {
        SecurityUserDetails reviewer = user(7L, "reviewer");
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
            "sha", SOURCE_SHA,
            "overflow", false,
            "targetBranch", "main",
            "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)
        ));
        when(gitlab.approveMergeRequest("token", null, 101L, 4L, SOURCE_SHA)).thenReturn(Map.of("ok", true));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        when(repository.saveApproved(any())).thenAnswer(invocation -> {
            MergeRequestEvidence value = invocation.getArgument(0);
            value.setId(91L);
            return 1;
        });

        Map<String, Object> result = service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, reviewer);

        ArgumentCaptor<MergeRequestEvidence> saved = ArgumentCaptor.forClass(MergeRequestEvidence.class);
        verify(repository).saveApproved(saved.capture());
        verify(gitlab).assertDistinctApprovalActor("token", new GitlabService.GitlabUserIdentity(7L, "reviewer"));
        assertThat(saved.getValue().getReviewerId()).isEqualTo(7L);
        assertThat(saved.getValue().getReviewerGitlabUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getReviewerGitlabUsername()).isEqualTo("reviewer");
        assertThat(saved.getValue().getSourceSha()).isEqualTo(SOURCE_SHA);
        assertThat(saved.getValue().getDiffBaseSha()).isEqualTo(BASE_SHA);
        assertThat(saved.getValue().getTokenSource()).isEqualTo("USER_PAT");
        assertThat(result).containsEntry("evidenceId", 91L).containsEntry("reviewedSha", SOURCE_SHA);
    }

    @Test
    void alreadyApprovedMergeRequestRecordsDeployOpsEvidenceWithoutDuplicateGitlabRequest() throws Exception {
        SecurityUserDetails reviewer = user(7L, "reviewer");
        when(gitlab.getMergeRequest("token", null, 101L, 4L))
            .thenReturn(openMr("author"), openMr("author"));
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
            "sha", SOURCE_SHA,
            "overflow", false,
            "targetBranch", "main",
            "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)
        ));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L))
            .thenReturn(Set.of(7L));
        when(repository.saveApproved(any())).thenAnswer(invocation -> {
            MergeRequestEvidence value = invocation.getArgument(0);
            value.setId(92L);
            return 1;
        });

        Map<String, Object> result = service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, reviewer);

        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(gitlab, times(2)).getMergeRequestApproverIds("token", null, 101L, 4L);
        verify(repository).saveApproved(any());
        assertThat(result).containsEntry("alreadyApproved", true)
            .containsEntry("evidenceId", 92L)
            .containsEntry("reviewedSha", SOURCE_SHA);
    }

    @Test
    void authorCannotApproveOwnMergeRequest() throws Exception {
        SecurityUserDetails author = user(7L, "author");
        when(gitlab.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(7L, "author"));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("AUTHOR", 7L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, author))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("자신의 변경");
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void approvalRejectsWhenTargetOrDiffChangedAfterScreenReview() throws Exception {
        SecurityUserDetails reviewer = user(7L, "reviewer");
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
            "sha", SOURCE_SHA,
            "overflow", false,
            "targetBranch", "release",
            "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)
        ));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, reviewer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("화면 검토 후");
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void approvalDoesNotCreateEvidenceWhenContextChangesDuringGitlabApproval() throws Exception {
        SecurityUserDetails reviewer = user(7L, "reviewer");
        Map<String, Object> changed = new java.util.LinkedHashMap<>(openMr("author"));
        changed.put("targetBranch", "release");
        when(gitlab.getMergeRequest("token", null, 101L, 4L))
            .thenReturn(openMr("author"), changed);
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
            "sha", SOURCE_SHA,
            "overflow", false,
            "targetBranch", "main",
            "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)
        ));
        when(gitlab.approveMergeRequest("token", null, 101L, 4L, SOURCE_SHA))
            .thenReturn(Map.of("ok", true));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, reviewer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("화면 검토 후");
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void deployCreatorCannotApproveSystemBotAuthoredMergeRequest() throws Exception {
        SecurityUserDetails creator = user(7L, "reviewer");
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("system-bot"));
        when(originRepository.findCreatedById(3L, 101L, 4L)).thenReturn(Optional.of(7L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, creator))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("생성한 사용자");
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
    }

    @Test
    void approvalRejectsMrCreatedUnderAnotherDeployProjectAfterRepositoryRelink() throws Exception {
        SecurityUserDetails reviewer = user(7L, "reviewer");
        when(gitlab.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(7L, "reviewer"));
        when(originRepository.findProjectIdsByGitlabProjectIdAndMergeRequestIid(101L, 4L))
            .thenReturn(java.util.List.of(99L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, reviewer))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("다른 DeployOps 프로젝트");
        verify(gitlab, never()).getMergeRequest("token", null, 101L, 4L);
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void mergeRejectsMrCreatedUnderAnotherDeployProjectAfterRepositoryRelink() throws Exception {
        when(originRepository.findProjectIdsByGitlabProjectIdAndMergeRequestIid(101L, 4L))
            .thenReturn(java.util.List.of(99L));

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("다른 DeployOps 프로젝트");
        verify(gitlab, never()).getMergeRequest("token", null, 101L, 4L);
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository, never()).markMerged(any());
    }

    @Test
    void mergeRequiresExactLocalApprovalAndStoresGitlabMergedResultSha() throws Exception {
        SecurityUserDetails merger = user(8L, "merger");
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L)
            .status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        when(gitlab.mergeMergeRequest("token", null, 101L, 4L, SOURCE_SHA, true)).thenReturn(Map.of(
            "state", "merged", "sha", SOURCE_SHA, "targetProjectId", 101L,
            "targetBranch", "main", "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA),
            "mergeCommitSha", MERGED_SHA, "squashCommitSha", ""
        ));
        when(repository.markMerged(evidence)).thenReturn(1);

        Map<String, Object> result = service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, merger);

        assertThat(evidence.getMergedResultSha()).isEqualTo(MERGED_SHA);
        assertThat(evidence.getMergerId()).isEqualTo(8L);
        assertThat(result).containsEntry("mergedResultSha", MERGED_SHA).containsEntry("evidenceId", 91L);
        verify(repository).markMerged(evidence);
    }

    @Test
    void alreadyMergedRequestCanReconcileLocalEvidenceEvenWhenGitlabDisallowsFurtherMerge() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L).status(MergeRequestEvidenceStatus.APPROVED).build();
        Map<String, Object> merged = new java.util.LinkedHashMap<>(openMr("author"));
        merged.put("state", "merged");
        merged.put("canMerge", false);
        merged.put("mergeCommitSha", MERGED_SHA);
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(merged);
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        when(repository.markMerged(evidence)).thenReturn(1);

        Map<String, Object> result = service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger"));

        assertThat(result).containsEntry("mergedResultSha", MERGED_SHA).containsEntry("evidenceId", 91L);
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository).markMerged(evidence);
        verify(gitlab, never()).getMergeRequestChanges(any(), any(), anyLong(), anyLong());
    }

    @Test
    void mergeRejectsChangedHeadBeforeCallingGitlabMerge() throws Exception {
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(
            MergeRequestEvidence.builder().id(91L).build()));
        Map<String, Object> changed = new java.util.LinkedHashMap<>(openMr("author"));
        changed.put("sha", MERGED_SHA);
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(changed);

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(MergeRequestGovernanceService.MergePreflightRejectedException.class)
            .hasMessageContaining("source SHA");
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository, never()).markMerged(any());
    }

    @Test
    void gitlabReadRejectionIsNotMisclassifiedAsKnownLocalPreflight() throws Exception {
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(
            MergeRequestEvidence.builder().id(91L).build()));
        var failure = new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
            "GitLab read transport rejected");
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenThrow(failure);

        assertThatThrownBy(() -> service.merge(project, 4L, SOURCE_SHA, "token",
            "main", BASE_SHA, START_SHA, user(8L, "merger"))).isSameAs(failure);
        verify(gitlab, never()).mergeMergeRequest(any(), any(), anyLong(), anyLong(), any(), anyBoolean());
        verify(repository, never()).markMerged(any());
    }

    @Test
    void postMergeLocalEvidenceFailureIsNotAFirstWriteRejection() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L).status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        when(gitlab.mergeMergeRequest("token", null, 101L, 4L, SOURCE_SHA, true)).thenReturn(Map.of(
            "state", "merged", "sha", SOURCE_SHA, "targetProjectId", 101L,
            "targetBranch", "main", "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA),
            "mergeCommitSha", MERGED_SHA));
        when(repository.markMerged(evidence)).thenReturn(0);

        assertThatThrownBy(() -> service.merge(project, 4L, SOURCE_SHA, "token",
            "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .isNotInstanceOf(MergeRequestGovernanceService.MergePreflightRejectedException.class)
            .hasMessageContaining("병합 증거를 확정하지 못했습니다");
        verify(gitlab).mergeMergeRequest("token", null, 101L, 4L, SOURCE_SHA, true);
        verify(repository).markMerged(evidence);
    }

    @Test
    void mergeRejectsTargetBranchChangedAfterApproval() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("release").status(MergeRequestEvidenceStatus.APPROVED)
            .build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("대상 브랜치");
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
    }

    @Test
    void mergeRejectsWhenGitlabReturnsDifferentDiffAfterMergeCall() throws Exception {
        SecurityUserDetails merger = user(8L, "merger");
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L)
            .status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(7L));
        when(gitlab.mergeMergeRequest("token", null, 101L, 4L, SOURCE_SHA, true)).thenReturn(Map.of(
            "state", "merged", "sha", SOURCE_SHA, "targetProjectId", 101L,
            "targetBranch", "main",
            "diffRefs", Map.of("base_sha", MERGED_SHA, "start_sha", START_SHA),
            "mergeCommitSha", MERGED_SHA, "squashCommitSha", ""
        ));

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, merger))
            .isInstanceOf(ResponseStatusException.class)
            .isNotInstanceOf(MergeRequestGovernanceService.MergePreflightRejectedException.class)
            .hasMessageContaining("승인 후 MR 대상 브랜치 또는 diff 기준");
        verify(repository, never()).markMerged(any());
    }

    @Test
    void sharedBotCannotApproveEvenWithAnEligibleReviewer() throws Exception {
        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "SYSTEM_BOT",
            "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("개인 토큰");
        verify(gitlab, never()).getMergeRequest(any(), any(), any(Long.class), any(Long.class));
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void personalPatUsernameMustMatchReviewerMapping() throws Exception {
        when(gitlab.getAuthenticatedUserIdentity("token", null))
            .thenReturn(new GitlabService.GitlabUserIdentity(9L, "someone-else"));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("계정이 로그인 사용자");
        verify(gitlab, never()).getMergeRequest(any(), any(), any(Long.class), any(Long.class));
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void matchingUsernameCannotApproveWhenTokenUserIdIsMrAuthor() throws Exception {
        when(gitlab.getMergeRequest("token", null, 101L, 4L))
            .thenReturn(openMr("old-author-name", 7L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("자신의 변경");
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void missingMrAuthorIdFailsClosed() throws Exception {
        when(gitlab.getMergeRequest("token", null, 101L, 4L))
            .thenReturn(openMr("author", 0L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("GitLab ID");
        verify(gitlab, never()).approveMergeRequest(any(), any(), any(Long.class), any(Long.class), any());
    }

    @Test
    void approvalResponseWithoutReviewerInGitlabDoesNotCreateLocalEvidence() throws Exception {
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestChanges("token", null, 101L, 4L)).thenReturn(Map.of(
            "sha", SOURCE_SHA, "overflow", false, "targetBranch", "main",
            "diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)));
        when(gitlab.approveMergeRequest("token", null, 101L, 4L, SOURCE_SHA)).thenReturn(Map.of("ok", true));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(8L));

        assertThatThrownBy(() -> service.approve(
            project, 4L, SOURCE_SHA, "token", "USER_PAT",
            "main", BASE_SHA, START_SHA, user(7L, "reviewer")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("현재 MR 승인");
        verify(repository, never()).saveApproved(any());
    }

    @Test
    void mergeRejectsApprovalRevokedInGitlab() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerGitlabUserId(7L).status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));
        when(gitlab.getMergeRequestApproverIds("token", null, 101L, 4L)).thenReturn(Set.of(8L));

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("현재 MR 승인");
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository, never()).markMerged(any());
    }

    @Test
    void legacyEvidenceWithoutNumericReviewerIdCannotMerge() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .status(MergeRequestEvidenceStatus.APPROVED).build();
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(openMr("author"));

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("GitLab ID가 없습니다");
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
    }

    @Test
    void mergeStopsBeforeGitlabMutationWhenCurrentAccountCannotMergeProtectedTarget() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerId(7L).reviewerGitlabUserId(7L).reviewerGitlabUsername("reviewer")
            .status(MergeRequestEvidenceStatus.APPROVED).build();
        Map<String, Object> current = new java.util.LinkedHashMap<>(openMr("author"));
        current.put("canMerge", false);
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(current);

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("대상 브랜치 병합 권한이 없습니다");
        verify(gitlab, never()).getMergeRequestApproverIds(any(), any(), any(Long.class), any(Long.class));
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository, never()).markMerged(any());
    }

    @Test
    void mergeStopsWhenGitlabDoesNotReturnMergeCapability() throws Exception {
        MergeRequestEvidence evidence = MergeRequestEvidence.builder()
            .id(91L).projectId(3L).gitlabProjectId(101L).mergeRequestIid(4L)
            .sourceSha(SOURCE_SHA).targetBranch("main").diffBaseSha(BASE_SHA).diffStartSha(START_SHA)
            .reviewerId(7L).reviewerGitlabUserId(7L).reviewerGitlabUsername("reviewer")
            .status(MergeRequestEvidenceStatus.APPROVED).build();
        Map<String, Object> current = new java.util.LinkedHashMap<>(openMr("author"));
        current.remove("canMerge");
        when(repository.findApproved(3L, 101L, 4L, SOURCE_SHA)).thenReturn(Optional.of(evidence));
        when(gitlab.getMergeRequest("token", null, 101L, 4L)).thenReturn(current);

        assertThatThrownBy(() -> service.merge(
            project, 4L, SOURCE_SHA, "token", "main", BASE_SHA, START_SHA, user(8L, "merger")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("병합 권한을 확인하지 못해 병합을 차단했습니다");
        verify(gitlab, never()).mergeMergeRequest(any(), any(), any(Long.class), any(Long.class), any(), any(Boolean.class));
        verify(repository, never()).markMerged(any());
    }

    private Map<String, Object> openMr(String author) {
        return openMr(author, 21L);
    }

    private Map<String, Object> openMr(String author, long authorId) {
        return Map.ofEntries(
            Map.entry("state", "opened"),
            Map.entry("sha", SOURCE_SHA),
            Map.entry("targetProjectId", 101L),
            Map.entry("targetBranch", "main"),
            Map.entry("authorUsername", author),
            Map.entry("authorId", authorId),
            Map.entry("draft", false),
            Map.entry("canMerge", true),
            Map.entry("diffRefs", Map.of("base_sha", BASE_SHA, "start_sha", START_SHA)),
            Map.entry("mergeCommitSha", ""),
            Map.entry("squashCommitSha", "")
        );
    }

    private SecurityUserDetails user(long id, String gitlabUsername) {
        return new SecurityUserDetails(User.builder().id(id).loginId("u" + id).name("User " + id)
            .gitlabUsername(gitlabUsername).role(UserRole.APPROVER).build());
    }
}
