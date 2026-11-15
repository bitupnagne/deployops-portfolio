package com.portfolio.deployops.domain;

public enum NginxChangeStatus {
    PENDING("승인 대기"),
    APPLYING("적용 중"),
    APPLIED("적용 완료"),
    FAILED("적용 실패"),
    RESULT_UNKNOWN("실행 결과 확인 필요"),
    REJECTED("반려"),
    ROLLED_BACK("복구 완료");

    private final String label;

    NginxChangeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
