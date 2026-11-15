package com.portfolio.deployops.domain;

/** 배포 산출물 값이 어떤 신뢰 경로에서 만들어졌는지 나타낸다. */
public enum ReleaseVerificationStatus {
    /** API·화면 입력 또는 기존 데이터처럼 CI 산출물과 Nexus를 서버가 교차 검증하지 않은 값. */
    UNVERIFIED,
    /** GitLab의 정확한 pipeline/job artifact와 Nexus manifest digest를 서버가 교차 검증한 값. */
    VERIFIED_CI_ARTIFACT
}
