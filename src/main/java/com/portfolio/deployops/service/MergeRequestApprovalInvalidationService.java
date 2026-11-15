package com.portfolio.deployops.service;

import com.portfolio.deployops.repository.MergeRequestEvidenceRepository;
import com.portfolio.deployops.repository.MergeRequestReviewGenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rejection withdraws local approval before a non-transactional GitLab close attempt. */
@Service
@RequiredArgsConstructor
public class MergeRequestApprovalInvalidationService {
    private final MergeRequestEvidenceRepository evidenceRepository;
    private final MergeRequestReviewGenerationRepository generationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long beginReview(Long projectId, Long gitlabProjectId, Long mergeRequestIid) {
        generationRepository.ensureExists(projectId, gitlabProjectId, mergeRequestIid);
        Long generation = generationRepository.lockGeneration(projectId, gitlabProjectId, mergeRequestIid);
        if (generation == null) throw new IllegalStateException("MR 검토 세대를 확인할 수 없습니다.");
        return generation;
    }

    /** Lock stays held through the caller's evidence save and commit, across DeployOps instances. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertReviewStillCurrent(Long projectId, Long gitlabProjectId, Long mergeRequestIid, long expected) {
        Long generation = generationRepository.lockGeneration(projectId, gitlabProjectId, mergeRequestIid);
        if (generation == null || generation != expected) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "검토 중 MR이 반려되어 기존 승인이 철회됐습니다. 다시 검토·승인해 주세요.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidate(Long projectId, Long gitlabProjectId, Long mergeRequestIid) {
        generationRepository.ensureExists(projectId, gitlabProjectId, mergeRequestIid);
        if (generationRepository.advanceGeneration(projectId, gitlabProjectId, mergeRequestIid) != 1) {
            throw new IllegalStateException("MR 검토 세대를 변경할 수 없습니다.");
        }
        evidenceRepository.invalidateApprovals(projectId, gitlabProjectId, mergeRequestIid);
    }
}
