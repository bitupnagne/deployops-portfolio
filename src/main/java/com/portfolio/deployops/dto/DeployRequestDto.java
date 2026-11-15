package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.DeployRequest;
import com.portfolio.deployops.domain.DeployStatus;
import com.portfolio.deployops.domain.ApprovalDecisionSource;
import com.portfolio.deployops.domain.ExecutionTrigger;
import com.portfolio.deployops.domain.ReleaseVerificationStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DeployRequestDto {
    private Long id;
    private String reqNo;
    private String projectName;
    private String environment;
    private String branch;
    private String imageTag;
    private String imageDigest;
    private Long gitlabPipelineId;
    private String commitSha;
    private String expectedCommitSha;
    private Long sourceMrIid;
    private Long mrEvidenceId;
    private String immutableBuildRef;
    private DeployStatus status;
    private String withdrawalReason;
    private ApprovalDecisionSource approvalDecisionSource;
    private ExecutionTrigger executionTrigger;
    private ReleaseVerificationStatus releaseVerificationStatus;
    private Long releaseVerificationJobId;
    private String releaseVerificationRepository;
    private LocalDateTime releaseVerifiedAt;
    private String releaseVerificationEvidenceHash;
    private LocalDateTime autoDispatchBlockedAt;
    private String autoDispatchBlockReason;
    private String requestedByName;
    private LocalDateTime createdAt;

    public static DeployRequestDto from(DeployRequest request) {
        return DeployRequestDto.builder()
            .id(request.getId())
            .reqNo(request.getReqNo())
            .projectName(request.getProject().getName())
            .environment(request.getEnvironment().name())
            .branch(request.getBranch())
            .imageTag(request.getImageTag())
            .imageDigest(request.getImageDigest())
            .gitlabPipelineId(request.getGitlabPipelineId())
            .commitSha(request.getCommitSha())
            .expectedCommitSha(request.getExpectedCommitSha())
            .sourceMrIid(request.getSourceMrIid())
            .mrEvidenceId(request.getMrEvidenceId())
            .immutableBuildRef(request.getImmutableBuildRef())
            .status(request.getStatus())
            .withdrawalReason(request.getWithdrawalReason())
            .approvalDecisionSource(request.getApprovalDecisionSource())
            .executionTrigger(request.getExecutionTrigger())
            .releaseVerificationStatus(request.getReleaseVerificationStatus())
            .releaseVerificationJobId(request.getReleaseVerificationJobId())
            .releaseVerificationRepository(request.getReleaseVerificationRepository())
            .releaseVerifiedAt(request.getReleaseVerifiedAt())
            .releaseVerificationEvidenceHash(request.getReleaseVerificationEvidenceHash())
            .autoDispatchBlockedAt(request.getAutoDispatchBlockedAt())
            .autoDispatchBlockReason(request.getAutoDispatchBlockReason())
            .requestedByName(request.getRequestedBy() != null ? request.getRequestedBy().getName() : "")
            .createdAt(request.getCreatedAt())
            .build();
    }
}
