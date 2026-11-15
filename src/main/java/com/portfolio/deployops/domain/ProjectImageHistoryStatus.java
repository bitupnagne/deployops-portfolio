package com.portfolio.deployops.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProjectImageHistoryStatus {
    BUILD_COMPLETED("빌드 완료"),
    TEST_DEPLOYED("TEST 배포됨"),
    PROD_DEPLOYED("운영 배포됨"),
    ROLLED_BACK("롤백됨");

    private final String displayName;
}
