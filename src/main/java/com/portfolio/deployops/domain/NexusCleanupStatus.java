package com.portfolio.deployops.domain;

public enum NexusCleanupStatus {
    PENDING("승인 대기"),
    DELETING("삭제 중"),
    COMPLETED("삭제 완료"),
    RECONCILING("결과 확인 필요"),
    REJECTED("반려"),
    FAILED("삭제 실패");

    private final String label;

    NexusCleanupStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
