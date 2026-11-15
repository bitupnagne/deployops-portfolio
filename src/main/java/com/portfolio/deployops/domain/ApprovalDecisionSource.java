package com.portfolio.deployops.domain;

/** PROD 요청이 승인 완료 상태가 된 근거. */
public enum ApprovalDecisionSource {
    MANUAL,
    AUTO_VERIFIED_PROMOTION,
    NOT_REQUIRED
}
