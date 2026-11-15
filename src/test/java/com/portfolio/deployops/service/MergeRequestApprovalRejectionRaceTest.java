package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.config.SecurityUserDetails;
import com.portfolio.deployops.domain.Project;
import com.portfolio.deployops.domain.User;
import com.portfolio.deployops.domain.UserRole;
import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestOriginRepository;
import com.portfolio.deployops.repository.MergeRequestReviewGenerationRepository;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MergeRequestApprovalRejectionRaceTest {
    @Test void rejectFinishesAfterApprovalPostcheckButBeforeSaveSoOldReviewCannotRecreateEvidence() throws Exception {
        var gitlab = mock(GitlabService.class);
        var evidence = mock(MergeRequestEvidenceRepository.class);
        var origin = mock(MergeRequestOriginRepository.class);
        var generation = mock(MergeRequestReviewGenerationRepository.class);
        var current = new AtomicLong(0);
        when(generation.lockGeneration(3L, 101L, 4L)).thenAnswer(invocation -> current.get());
        when(generation.advanceGeneration(3L, 101L, 4L)).thenAnswer(invocation -> { current.incrementAndGet(); return 1; });
        var invalidation = new MergeRequestApprovalInvalidationService(evidence, generation);
        var service = new MergeRequestGovernanceService(gitlab, evidence, origin, invalidation);
        String sha = "a".repeat(40), base = "b".repeat(40), start = "c".repeat(40);
        var open = Map.<String, Object>of("state", "opened", "sha", sha, "targetProjectId", 101L,
            "targetBranch", "main", "authorId", 21L, "draft", false, "overflow", false,
            "diffRefs", Map.of("base_sha", base, "start_sha", start));
        var closed = new java.util.LinkedHashMap<>(open); closed.put("state", "closed");
        var project = Project.builder().id(3L).gitlabProjectId(101L).build();
        var reviewer = new SecurityUserDetails(User.builder().id(7L).loginId("reviewer")
            .gitlabUsername("reviewer").role(UserRole.APPROVER).build());
        when(gitlab.getAuthenticatedUserIdentity("pat", null)).thenReturn(new GitlabService.GitlabUserIdentity(77L, "reviewer"));
        when(gitlab.getMergeRequestChanges("pat", null, 101L, 4L)).thenReturn(open);
        when(gitlab.getMergeRequest("pat", null, 101L, 4L)).thenReturn(open, open, open, open, closed);
        var postcheckReached = new CountDownLatch(1);
        var rejectionFinished = new CountDownLatch(1);
        var approvalReads = new AtomicInteger();
        when(gitlab.getMergeRequestApproverIds("pat", null, 101L, 4L)).thenAnswer(invocation -> {
            if (approvalReads.incrementAndGet() == 2) {
                postcheckReached.countDown();
                if (!rejectionFinished.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test rejection timeout");
            }
            return Set.of(77L);
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var approval = executor.submit(() -> service.approve(project, 4L, sha, "pat", "USER_PAT",
                "main", base, start, reviewer));
            if (!postcheckReached.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test approval timeout");
            try {
                service.reject(project, 4L, sha, "pat", "USER_PAT", "main", base, start, "검증 실패", reviewer);
            } finally { rejectionFinished.countDown(); }
            assertThatThrownBy(() -> approval.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasStackTraceContaining("검토 중 MR이 반려");
        }
        verify(evidence, never()).saveApproved(any());
        verify(evidence, times(2)).invalidateApprovals(3L, 101L, 4L);
    }
}
