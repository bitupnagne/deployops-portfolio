package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestReviewGenerationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class MergeRequestApprovalInvalidationServiceTest {
    @Test void olderInFlightApprovalCannotSaveAfterRejectionAdvancesGeneration() {
        var evidence = mock(MergeRequestEvidenceRepository.class);
        var generation = mock(MergeRequestReviewGenerationRepository.class);
        var current = new java.util.concurrent.atomic.AtomicLong(0);
        when(generation.lockGeneration(3L, 101L, 4L)).thenAnswer(invocation -> current.get());
        when(generation.advanceGeneration(3L, 101L, 4L)).thenAnswer(invocation -> { current.incrementAndGet(); return 1; });
        var service = new MergeRequestApprovalInvalidationService(evidence, generation);
        long approvalStarted = service.beginReview(3L, 101L, 4L);
        service.invalidate(3L, 101L, 4L);
        assertThatThrownBy(() -> service.assertReviewStillCurrent(3L, 101L, 4L, approvalStarted))
            .hasMessageContaining("반려");
        service.assertReviewStillCurrent(3L, 101L, 4L, service.beginReview(3L, 101L, 4L));
    }

    @Test void reviewSaveGuardUsesCallerTransactionAndDatabaseRowLock() throws Exception {
        assertThat(MergeRequestApprovalInvalidationService.class.getMethod("assertReviewStillCurrent",
            Long.class, Long.class, Long.class, long.class).getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.MANDATORY);
        assertThat(String.join(" ", MergeRequestReviewGenerationRepository.class.getMethod("lockGeneration",
            Long.class, Long.class, Long.class).getAnnotation(org.apache.ibatis.annotations.Select.class).value()))
            .contains("FOR UPDATE");
    }

    @Test void approvalWithdrawalUsesIndependentTransactionAndScopesToExactRepositoryAndMr() throws Exception {
        var repository = mock(MergeRequestEvidenceRepository.class);
        var generation = mock(MergeRequestReviewGenerationRepository.class);
        when(generation.advanceGeneration(3L, 101L, 4L)).thenReturn(1);
        new MergeRequestApprovalInvalidationService(repository, generation).invalidate(3L, 101L, 4L);
        var ordered = inOrder(generation, repository);
        ordered.verify(generation).advanceGeneration(3L, 101L, 4L);
        ordered.verify(repository).invalidateApprovals(3L, 101L, 4L);
        verify(repository).invalidateApprovals(3L, 101L, 4L);
        assertThat(MergeRequestApprovalInvalidationService.class
            .getMethod("invalidate", Long.class, Long.class, Long.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
