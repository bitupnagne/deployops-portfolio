package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 원본을 변경하지 않는 별도 상태 확인의 durable 접수 기록. */
@Data
public class NexusBackupRecoveryCheck {
    private Long id;
    private Long requestId;
    private Long requestedById;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
