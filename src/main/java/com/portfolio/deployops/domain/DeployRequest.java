package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeployRequest {

    private Long id;
    private String reqNo;
    private Project project;
    private DeployEnvironment environment;

    /** Explicitly selected, project-mapped groups. Null is reserved for legacy requests. */
    private List<Long> selectedServerGroupIds;

    @Builder.Default
    private DeployScope deployScope = DeployScope.ALL;

    @Builder.Default
    private DeployStrategy deployStrategy = DeployStrategy.SAFE;

    private String branch;
    private String imageTag;
    private String imageDigest;
    private Long gitlabPipelineId;
    private String commitSha;
    private String expectedCommitSha;
    private Long sourceMrIid;
    private Long mrEvidenceId;
    private String immutableBuildRef;
    private String reason;
    /** 마지막 실행 실패의 진단 요약. 비밀 마스킹 후 저장한다. */
    private String failureReason;
    private String withdrawalReason;

    /** 요청 당시 서버·컴포넌트·런타임·승인 정책을 고정한 시크릿 없는 JSON. */
    private String planSnapshotJson;
    /** {@link #planSnapshotJson} 원문의 SHA-256. */
    private String planSnapshotHash;
    private Integer planSnapshotVersion;

    /** 승인 완료 결정의 출처. 승인 대기·반려·구 데이터는 null일 수 있다. */
    private ApprovalDecisionSource approvalDecisionSource;
    /** 실제 실행 선점 시 기록되는 수동/자동 실행 구분. */
    private ExecutionTrigger executionTrigger;
    /** 새 실행 경로가 원격 호출 전 durable attempt를 기록하기 시작한 시각. 구 요청은 null. */
    private LocalDateTime targetAttemptTrackingStartedAt;

    /** 서버가 GitLab artifact와 Nexus manifest를 교차 검증한 provenance 상태. */
    @Builder.Default
    private ReleaseVerificationStatus releaseVerificationStatus = ReleaseVerificationStatus.UNVERIFIED;
    private Long releaseVerificationJobId;
    private String releaseVerificationRepository;
    private LocalDateTime releaseVerifiedAt;
    private String releaseVerificationEvidenceHash;
    /** Canonical complete CI component manifest, absent for legacy single-image releases. */
    private String releaseManifestSha256;

    /** 영구적인 snapshot/plan 오류로 자동 스캔에서 제외된 시각과 사유. */
    private LocalDateTime autoDispatchBlockedAt;
    private String autoDispatchBlockReason;

    @Builder.Default
    private DeployStatus status = DeployStatus.TEST_REQUESTED;

    private User requestedBy;
    private User testConfirmedBy;
    private LocalDateTime testConfirmedAt;
    private User executedBy;
    private DeployRequest promotedFromRequest;

    @Builder.Default
    private List<DeployRequestItem> items = new ArrayList<>();

    @Builder.Default
    private List<ApprovalRecord> approvalRecords = new ArrayList<>();

    @Builder.Default
    private List<StatusHistory> statusHistories = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
