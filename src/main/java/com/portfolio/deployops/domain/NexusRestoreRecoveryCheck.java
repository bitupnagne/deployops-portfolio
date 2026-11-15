package com.portfolio.deployops.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 복원을 재실행하지 않는 실행 전 실패 상태 재확인 접수. */
@Data
public class NexusRestoreRecoveryCheck {
    private Long id, requestId, requestedById;
    private String status, reasonCode;
    private LocalDateTime createdAt, completedAt;
}
