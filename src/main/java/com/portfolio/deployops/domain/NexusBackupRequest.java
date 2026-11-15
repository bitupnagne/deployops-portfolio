package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class NexusBackupRequest {
    private Long activeCheckId;
    private Long id;
    private Long requestedById;
    private Long approvedById;
    private String requestedBy;
    private String approvedBy;
    private String reason;
    private String rejectionReason;
    private String status;
    private String stage;
    private String errorCode;
    private String artifactId;
    private String manifestSha256;
    private boolean submitted;
    private LocalDateTime createdAt;
    private LocalDateTime approvedAt;
    private LocalDateTime completedAt;
}
