package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 검증한 격리 복원본을 정식 Nexus로 전환하는 별도 승인 기록. */
@Data
public class NexusPromotionRequest {
    private Long id, restoreRequestId, requestedById, approvedById;
    private String reason, rejectionReason, status, canonicalContainerId, rollbackContainerId, errorCode;
    private String requestedBy, approvedBy;
    private boolean submitted;
    private LocalDateTime createdAt, approvedAt, completedAt;
}
